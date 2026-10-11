package com.pixelplus.owner.backup

import com.pixelplus.owner.LicenceRequest
import com.pixelplus.owner.OwnerAction
import com.pixelplus.owner.audit.AesGcmAuditSealer
import com.pixelplus.owner.audit.AuditRuntime
import com.pixelplus.owner.audit.RandomIdSource
import com.pixelplus.owner.data.db.OwnerDatabase
import com.pixelplus.owner.data.db.RecordResult
import com.pixelplus.owner.sync.QueueOutcome
import com.pixelplus.owner.sync.SyncStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** Restore on a real SQLite file: the swap, the safety copy, the quarantine of the queue, and the failure paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class AndroidRestoreTest {
    private val context get() = RuntimeEnvironment.getApplication() as android.content.Context
    private val name = "restore-${UUID.randomUUID()}.db"
    private val runtime = AuditRuntime(RandomIdSource, AesGcmAuditSealer.ephemeral())
    private val sealer = AesGcmBackupSealer.ephemeral()
    private val ws = "11111111-1111-4111-8111-111111111111"
    private val now = 1_800_000_000_000L
    private lateinit var db: OwnerDatabase

    @Before fun open() {
        db = OwnerDatabase.open(context, name, allowMainThreadQueries = true, runtime = runtime)
    }

    @After fun close() {
        runCatching { db.close() }
        context.deleteDatabase(name)
        context.filesDir.listFiles()?.filter { it.name.startsWith("pre-restore-") }?.forEach { it.delete() }
    }

    private fun plan(dbBytes: ByteArray, version: Int = OwnerDatabase.VERSION): RestorePlan {
        val built = BackupArchive.build(
            "77777777-7777-4777-8777-777777777777", now, version, "t", emptyMap(), emptySet(),
            mapOf(BackupLimits.DATABASE to dbBytes, BackupLimits.LOGICAL to "{}".toByteArray()), sealer,
        ) as ArchiveResult.Built
        return (RestorePlanner.check(built.bytes, sealer, OwnerDatabase.VERSION) as RestoreCheck.Ready).plan
    }

    /** A checkpointed copy of the live file, as the backup would hold it. */
    private fun snapshot(): ByteArray {
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        return context.getDatabasePath(name).readBytes()
    }

    private fun reopen() {
        db = OwnerDatabase.open(context, name, allowMainThreadQueries = true, runtime = runtime)
    }

    @Test fun restoreBringsBackTheBackupKeepsASafetyCopyAndRecordsIt() {
        assertEquals(RecordResult.Applied, db.records().createWorkspace(ws, "Before", now))
        val backup = snapshot()
        assertEquals(RecordResult.Applied, db.records().renameWorkspace(ws, "After the backup", now + 1))
        db.close()
        val result = AndroidRestore.apply(context, plan(backup), name, runtime, now + 2) as AndroidRestore.Applied.Done
        reopen()
        assertEquals("Before", db.ownerDao().workspace(ws)!!.name)
        assertTrue(db.verifyAudit().ok)
        val safety = File(context.filesDir, result.safetyCopy)
        assertTrue(safety.exists() && safety.length() > 0)
        val rows = db.auditDao().entries(db.auditDao().chain()!!.chainId)
        assertTrue(rows.last().action == "restore" && rows.last().reasonCode == "backup_restore")
    }

    @Test fun theSafetyCopyHoldsTheDataThatWasOnThePhoneBeforeTheRestore() {
        db.records().createWorkspace(ws, "Old", now)
        val backup = snapshot()
        db.records().renameWorkspace(ws, "Newer", now + 1)
        db.close()
        val done = AndroidRestore.apply(context, plan(backup), name, runtime, now + 2) as AndroidRestore.Applied.Done
        val copy = File(context.filesDir, done.safetyCopy)
        val raw = android.database.sqlite.SQLiteDatabase.openDatabase(copy.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        try {
            raw.rawQuery("SELECT name FROM workspaces", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("Newer", it.getString(0))
            }
        } finally {
            raw.close()
        }
    }

    @Test fun queuedDecisionsComeBackAsNeedsReviewAndDispatchIsPaused() {
        db.records().createWorkspace(ws, "Office", now)
        val store = SyncStore(db)
        val profile = store.ensureProfile("https://hub.example", now)!!
        val request = LicenceRequest("00000000-0000-4000-8000-000000000001", "store", "trial", "ABCDE12345", "Shop", "1", now / 1000, null)
        assertTrue(store.enqueue(profile, request, OwnerAction.APPROVE, now) is QueueOutcome.Queued)
        val backup = snapshot()
        db.close()
        val done = AndroidRestore.apply(context, plan(backup), name, runtime, now + 2) as AndroidRestore.Applied.Done
        assertEquals(1, done.quarantined)
        reopen()
        val statuses = db.openHelper.writableDatabase.query("SELECT status, lastErrorCode FROM outbox").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }
        assertEquals(listOf("NEEDS_REVIEW" to "restored"), statuses)
        db.openHelper.writableDatabase.query("SELECT dispatchPaused FROM hub_profiles").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
    }

    @Test fun aBrokenDatabaseInsideAGoodArchiveChangesNothing() {
        db.records().createWorkspace(ws, "Keep me", now)
        db.close()
        val garbage = ByteArray(5000) { (it % 251).toByte() }
        val failed = AndroidRestore.apply(context, plan(garbage), name, runtime, now + 2)
        assertTrue(failed is AndroidRestore.Applied.Failed)
        reopen()
        assertEquals("Keep me", db.ownerDao().workspace(ws)!!.name)
        assertFalse(File(context.getDatabasePath(name).parentFile, "$name.restore-candidate").exists())
    }

    @Test fun aDatabaseOfTheWrongVersionIsRefusedBeforeTheSwap() {
        db.records().createWorkspace(ws, "Keep me", now)
        val backup = snapshot()
        db.close()
        val failed = AndroidRestore.apply(context, plan(backup, version = OwnerDatabase.VERSION - 1), name, runtime, now + 2)
        assertTrue(failed is AndroidRestore.Applied.Failed)
        reopen()
        assertEquals("Keep me", db.ownerDao().workspace(ws)!!.name)
    }

    @Test fun anArchiveFromAnotherPhoneIsRefusedAtPrepare() {
        db.records().createWorkspace(ws, "Office", now)
        val built = BackupArchive.build(
            "77777777-7777-4777-8777-777777777777", now, OwnerDatabase.VERSION, "t", emptyMap(), emptySet(),
            mapOf(BackupLimits.DATABASE to snapshot(), BackupLimits.LOGICAL to "{}".toByteArray()), sealer,
        ) as ArchiveResult.Built
        val other = AesGcmBackupSealer.ephemeral()
        assertEquals(AndroidRestore.Prepared.Refused(BackupFault.WRONG_KEY_OR_TAMPERED), AndroidRestore.prepare(built.bytes, other))
        assertEquals(AndroidRestore.Prepared.Refused(BackupFault.WRONG_KEY_OR_TAMPERED), AndroidRestore.prepare(built.bytes, null))
        assertTrue(AndroidRestore.prepare(built.bytes, sealer) is AndroidRestore.Prepared.Ready)
    }
}
