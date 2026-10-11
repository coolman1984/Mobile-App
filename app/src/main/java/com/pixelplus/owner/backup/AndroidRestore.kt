package com.pixelplus.owner.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.pixelplus.owner.audit.AuditRuntime
import com.pixelplus.owner.audit.AuditVocab
import com.pixelplus.owner.audit.JsonValue
import com.pixelplus.owner.data.db.AuditSpec
import com.pixelplus.owner.data.db.AuditWriter
import com.pixelplus.owner.data.db.OwnerDatabase
import java.io.File

/**
 * Restore (M06 on a phone). Two steps, so nothing changes before the owner has seen what will happen:
 *
 * [prepare] opens the archive with this phone's key and checks everything ([RestorePlanner]). [apply] then works on a candidate copy:
 * integrity and foreign-key checks and the version, a safety copy of the current notebook (kept; it is never deleted by the app),
 * an atomic rename over the live file, a reopen that runs the normal migrations, and only then the quarantine of queued decisions.
 * Any failure puts the safety copy back and says so. Hub tokens are not in an archive and are left as they are.
 */
object AndroidRestore {
    sealed class Prepared {
        data class Ready(val plan: RestorePlan) : Prepared()
        data class Refused(val fault: BackupFault) : Prepared()
    }

    sealed class Applied {
        data class Done(val safetyCopy: String, val quarantined: Int) : Applied()
        data class Failed(val reasonAr: String, val reasonEn: String) : Applied()
    }

    private val ACTIVE_STATUSES = "'CONFIRMED_LOCAL','QUEUED','LEASED','SENT','RETRY_WAIT'"

    fun prepare(archive: ByteArray, sealer: BackupSealer? = KeystoreBackupKey.resolve()): Prepared {
        sealer ?: return Prepared.Refused(BackupFault.WRONG_KEY_OR_TAMPERED)
        return when (val check = RestorePlanner.check(archive, sealer, OwnerDatabase.VERSION)) {
            is RestoreCheck.Ready -> Prepared.Ready(check.plan)
            is RestoreCheck.Refused -> Prepared.Refused(check.reason)
        }
    }

    fun apply(
        context: Context,
        plan: RestorePlan,
        dbName: String = OwnerDatabase.DATABASE_NAME,
        runtime: AuditRuntime? = null,
        nowUtcMs: Long = System.currentTimeMillis(),
    ): Applied = synchronized(com.pixelplus.owner.sync.HubSession.lock) {
        val app = context.applicationContext
        val live = app.getDatabasePath(dbName)
        live.parentFile?.mkdirs()
        val candidate = File(live.parentFile, "$dbName.restore-candidate")
        val safety = File(app.filesDir, "pre-restore-$nowUtcMs.db")
        fun fail(ar: String, en: String): Applied.Failed {
            candidate.delete()
            return Applied.Failed(ar, en)
        }
        val bytes = plan.files[BackupLimits.DATABASE] ?: return fail("الملف لا يحتوي قاعدة البيانات.", "The file holds no database.")
        runCatching { candidate.writeBytes(bytes) }.getOrElse { return fail("تعذر تجهيز النسخة للاستعادة.", "Could not prepare the copy.") }
        verifyCandidate(candidate, plan.manifest.dbSchemaVersion)?.let { return fail(it.first, it.second) }

        // 1. keep what is on the phone now
        if (live.exists()) {
            val ok = runCatching {
                OwnerDatabase.open(app, dbName, runtime = runtime).let { db ->
                    try { db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } } finally { db.close() }
                }
                live.copyTo(safety, overwrite = true)
                true
            }.getOrDefault(false)
            if (!ok) { safety.delete(); return fail("تعذر حفظ نسخة أمان من البيانات الحالية، فلم تتغير.", "Could not keep a safety copy of the current data, so nothing changed.") }
        }

        // 2. swap
        fun putBack() {
            File(live.path + "-wal").delete(); File(live.path + "-shm").delete(); File(live.path + "-journal").delete()
            live.delete()
            if (safety.exists()) safety.copyTo(live, overwrite = true)
        }
        val swapped = runCatching {
            File(live.path + "-wal").delete(); File(live.path + "-shm").delete(); File(live.path + "-journal").delete()
            if (live.exists() && !live.delete()) error("live")
            if (!candidate.renameTo(live)) { candidate.copyTo(live, overwrite = true); candidate.delete() }
        }.isSuccess
        if (!swapped) { putBack(); return fail("تعذر استبدال البيانات. أُعيدت البيانات السابقة.", "Could not replace the data. The previous data was put back.") }

        // 3. reopen (runs the normal migrations), quarantine the queue, record the restore
        val quarantined = runCatching {
            val db = OwnerDatabase.open(app, dbName, runtime = runtime)
            try {
                val raw = db.openHelper.writableDatabase
                var count = 0
                db.runInTransaction {
                    raw.query("SELECT count(*) FROM outbox WHERE status IN ($ACTIVE_STATUSES)").use { if (it.moveToFirst()) count = it.getInt(0) }
                    raw.execSQL("UPDATE outbox SET status = 'NEEDS_REVIEW', lastErrorCode = 'restored', leaseUntil = NULL WHERE status IN ($ACTIVE_STATUSES)")
                    raw.execSQL("UPDATE hub_profiles SET dispatchPaused = 1")
                    val workspace = raw.query("SELECT id FROM workspaces ORDER BY createdAtUtcMs LIMIT 1").use { if (it.moveToFirst()) it.getString(0) else null }
                    if (workspace != null) {
                        AuditWriter(db).append(
                            AuditSpec(
                                screen = AuditVocab.SCREEN_NOTEBOOK, action = AuditVocab.ACTION_RESTORE, entityType = AuditVocab.ENTITY_WORKSPACE,
                                entityId = workspace, workspaceId = workspace, hubProfileId = null, nowUtcMs = nowUtcMs,
                                before = emptyMap(), after = mapOf("status" to JsonValue.Text("restored")),
                                result = AuditVocab.RESULT_SUCCESS, reasonCode = "backup_restore",
                            ),
                        )
                    }
                }
                count
            } finally {
                db.close()
            }
        }.getOrElse {
            putBack()
            return Applied.Failed("تعذر فتح البيانات المستعادة. أُعيدت البيانات السابقة.", "The restored data could not be opened. The previous data was put back.")
        }
        return Applied.Done(safety.name, quarantined)
    }

    /** Null when the candidate is a sound notebook of the stated version; otherwise the reason to show. */
    private fun verifyCandidate(file: File, expectedVersion: Int): Pair<String, String>? {
        val bad = "الملف المستعاد غير سليم. لم يتغير شيء." to "The restored file is not sound. Nothing changed."
        val db = runCatching { SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull() ?: return bad
        try {
            val integrity = db.rawQuery("PRAGMA integrity_check", null).use { if (it.moveToFirst()) it.getString(0) else "?" }
            if (integrity != "ok") return bad
            val fk = db.rawQuery("PRAGMA foreign_key_check", null).use { it.count }
            if (fk != 0) return bad
            val version = db.rawQuery("PRAGMA user_version", null).use { if (it.moveToFirst()) it.getInt(0) else -1 }
            if (version != expectedVersion || version > OwnerDatabase.VERSION) return bad
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            if (!tables.containsAll(listOf("workspaces", "audit_entries", "audit_chain", "customers"))) return bad
        } finally {
            db.close()
        }
        return null
    }
}
