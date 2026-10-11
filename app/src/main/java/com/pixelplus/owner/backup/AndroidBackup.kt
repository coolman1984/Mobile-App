package com.pixelplus.owner.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import com.pixelplus.owner.audit.ActivityReader
import com.pixelplus.owner.data.db.OwnerDatabase
import java.io.File

/**
 * Backup now (M06 on a phone). Writes one sealed archive to a file the owner chose with the system file picker.
 *
 * The database copy is taken with SQLite's `VACUUM INTO`, which reads one consistent snapshot while the app keeps running, so no writer
 * has to be stopped (Android 11 and up; older phones are told it is not available). The archive holds the database copy and the
 * activity export, sealed with the device-bound key. There is **no restore** in the app yet, and the archive cannot be opened on another
 * phone: the screen says both. Plaintext is only ever in the app's private cache, and is deleted at once.
 */
object AndroidBackup {
    const val MIN_SDK = 30

    sealed class Result {
        data class Done(val bytes: Int) : Result()
        data class Failed(val reasonAr: String, val reasonEn: String) : Result()
    }

    fun run(context: Context, target: Uri, nowUtcMs: Long, newId: () -> String): Result {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < MIN_SDK) return Result.Failed("النسخ الاحتياطي يحتاج أندرويد 11 أو أحدث.", "Backup needs Android 11 or newer.")
        val sealer = KeystoreBackupKey.resolve() ?: return Result.Failed("مفتاح الحماية غير متاح على هذا الهاتف. لم يُكتب شيء.", "The protection key is not available on this phone. Nothing was written.")
        val source = object : SnapshotSource {
            override fun <T> quiesced(block: (Snapshot) -> T): T = block(takeSnapshot(app))
        }
        val temp = File(app.cacheDir, "backup-out.tmp")
        val sink = object : BackupSink {
            override fun writeTemp(name: String, sealed: ByteArray): Boolean = runCatching { temp.writeBytes(sealed); true }.getOrDefault(false)
            override fun commit(name: String): Boolean = runCatching {
                app.contentResolver.openOutputStream(target, "wt")!!.use { out -> temp.inputStream().use { it.copyTo(out) } }
                true
            }.getOrDefault(false)
            override fun discardTemp(name: String) { temp.delete() }
        }
        return try {
            when (val outcome = BackupCoordinator(source, sealer, sink, { nowUtcMs }, newId).run()) {
                is BackupOutcome.Done -> Result.Done(outcome.sizeBytes)
                is BackupOutcome.Failed -> when (outcome.stage) {
                    Stage.SNAPSHOT -> Result.Failed("تعذر أخذ نسخة من قاعدة البيانات.", "Could not take a copy of the database.")
                    Stage.SEAL -> Result.Failed(if (outcome.fault == BackupFault.TOO_LARGE) "النسخة أكبر من الحد المسموح (64 ميجابايت)." else "تعذر تشفير النسخة.", if (outcome.fault == BackupFault.TOO_LARGE) "The copy is larger than the allowed 64 MB." else "Could not seal the copy.")
                    Stage.WRITE, Stage.COMMIT -> Result.Failed("تعذر الكتابة في الملف الذي اخترته.", "Could not write to the file you chose.")
                }
            }
        } finally {
            temp.delete()
        }
    }

    private fun takeSnapshot(app: Context): Snapshot {
        val copy = File(app.cacheDir, "backup-snapshot.db")
        copy.delete()
        val database = OwnerDatabase.open(app)
        try {
            // the path is inside the app's own cache directory and contains no quote; VACUUM INTO takes a text literal
            val path = copy.absolutePath
            require(!path.contains('\'')) { "path" }
            database.openHelper.writableDatabase.execSQL("VACUUM INTO '$path'")
            val db = copy.readBytes()
            val activity = ActivityReader.read(app)
            val dao = database.auditDao()
            val chain = dao.chain()
            val head = chain?.let { c -> dao.head(c.chainId)?.let { c.chainId to com.pixelplus.owner.backup.AuditHeadEntry(it.sequence, it.entryHash) } }
            val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "unknown"
            return Snapshot(
                dbSchemaVersion = OwnerDatabase.VERSION,
                appVersion = version,
                databaseCopy = db,
                logicalJson = activity?.json ?: "{}".toByteArray(),
                auditHeads = head?.let { mapOf(it) } ?: emptyMap(),
                hubProfileIds = emptySet(),
            )
        } finally {
            database.close()
            copy.delete()
        }
    }
}
