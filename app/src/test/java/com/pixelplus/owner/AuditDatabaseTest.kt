package com.pixelplus.owner

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import com.pixelplus.owner.audit.AesGcmAuditSealer
import com.pixelplus.owner.audit.AuditRuntime
import com.pixelplus.owner.audit.AuditVocab
import com.pixelplus.owner.audit.ChainFault
import com.pixelplus.owner.audit.IdSource
import com.pixelplus.owner.data.db.OwnerDatabase
import com.pixelplus.owner.data.db.OwnerMigrations
import com.pixelplus.owner.data.db.RecordResult
import com.pixelplus.owner.domain.UndoChannel
import com.pixelplus.owner.domain.UndoOutcome
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The chain is stored in the same SQLite file as the notebook.
 * Hiding a row can be undone only while its revision still matches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class AuditDatabaseTest {
    private lateinit var context: android.content.Context
    private lateinit var name: String
    private lateinit var db: OwnerDatabase
    private val ids = CountingIds()

    @Before
    fun open() {
        context = RuntimeEnvironment.getApplication()
        name = "m03-${UUID.randomUUID()}.db"
        db = openNamed(name)
    }

    @After
    fun close() {
        runCatching { db.close() }
        context.deleteDatabase(name)
    }

    @Test fun everySuccessfulWriteAppendsBothLanguages() {
        assertEquals(RecordResult.Applied, records().createWorkspace(WORKSPACE, "المكتب", NOW))
        assertEquals(RecordResult.Applied, records().renameWorkspace(WORKSPACE, "المكتب ٢", NOW + 1))
        assertEquals(RecordResult.Applied, records().createHubProfile(HUB, WORKSPACE, "https://factory.example", NOW))
        assertEquals(RecordResult.Applied, records().markHubTested(HUB, NOW, NOW + 1))
        val phone = ByteArray(40)
        SECRET.toByteArray().copyInto(phone)
        assertEquals(
            RecordResult.Applied,
            records().createCustomer(CUSTOMER, WORKSPACE, cipher(), NOW, encryptedPhone = phone, normalizedSearchToken = TOKEN),
        )
        assertEquals(RecordResult.Applied, records().setCustomerStatus(CUSTOMER, "paused", NOW + 1))
        assertEquals(RecordResult.Applied, records().createBranch(BRANCH, WORKSPACE, CUSTOMER, cipher(), NOW))
        assertEquals(RecordResult.Applied, records().createProduct(PRODUCT, WORKSPACE, "store", "الستور", 14, NOW))
        assertEquals(
            RecordResult.Applied,
            records().createLink(LINK, WORKSPACE, CUSTOMER, HUB, "install", "device-1", ACTOR, NOW),
        )
        val entries = db.auditDao().entries(db.auditDao().chain()!!.chainId)
        assertEquals(9, entries.size)
        assertEquals((1L..9L).toList(), entries.map { it.sequence })
        entries.forEach { entry ->
            assertTrue(entry.explanationAr.any { it.code > 127 })
            assertTrue(entry.explanationEn.any { it in 'A'..'z' })
            assertFalse(entry.explanationAr == entry.explanationEn)
            assertFalse(entry.beforeSafe.contains(TOKEN))
            assertFalse(entry.afterSafe.contains(TOKEN))
            assertFalse(entry.beforeSafe.contains(SECRET))
            assertFalse(entry.afterSafe.contains(SECRET))
            assertFalse(entry.explanationAr.contains(SECRET))
            assertFalse(entry.explanationEn.contains("المكتب"))
        }
        val opened = entries.joinToString("\n") { String(db.auditRuntime.sealer.open(it.canonicalCiphertext)) }
        assertFalse(opened.contains(TOKEN))
        assertFalse(opened.contains(SECRET))
        assertFalse(opened.contains("المكتب"))
        assertTrue(opened.contains("تم"))
        assertTrue(opened.contains("Workspace"))
        assertTrue(db.verifyAudit().ok)
        assertEquals(2, db.ownerDao().workspace(WORKSPACE)!!.revision)
        assertTrue(db.auditDao().latestUndo() != null)
    }

    @Test fun softDeleteRestoresThroughUndoUntilTheRevisionMoves() {
        records().createWorkspace(WORKSPACE, "Office", NOW)
        records().createCustomer(CUSTOMER, WORKSPACE, cipher(), NOW, normalizedSearchToken = TOKEN)
        val created = db.auditDao().latestUndo()!!
        assertEquals(RecordResult.Applied, records().setCustomerStatus(CUSTOMER, "paused", NOW + 1))
        val statusUndo = db.auditDao().latestUndo()!!
        val blocked = records().undo(created.operationId, NOW + 2, UndoChannel.SNACKBAR) as UndoOutcome.Blocked
        assertEquals("revision", blocked.reason)
        assertEquals("paused", db.ownerDao().customer(CUSTOMER)!!.localStatus)
        val undone = records().undo(statusUndo.operationId, NOW + 2, UndoChannel.SNACKBAR) as UndoOutcome.Done
        assertEquals(statusUndo.eventId, undone.originalEventId)
        assertEquals("active", db.ownerDao().customer(CUSTOMER)!!.localStatus)
        assertEquals(3, db.ownerDao().customer(CUSTOMER)!!.revision)
        val again = records().undo(statusUndo.operationId, NOW + 3, UndoChannel.ACTIVITY) as UndoOutcome.Blocked
        assertEquals("used", again.reason)

        assertEquals(RecordResult.Applied, records().softDeleteCustomer(CUSTOMER, NOW + 4))
        assertNull(db.ownerDao().activeCustomers(WORKSPACE).firstOrNull())
        val hidden = db.auditDao().latestUndo()!!
        assertEquals(AuditVocab.ACTION_SOFT_DELETE, db.auditDao().entry(hidden.eventId)!!.action)
        val restored = records().undo(hidden.operationId, NOW + 4 + 9_999, UndoChannel.SNACKBAR) as UndoOutcome.Done
        assertNotNull(restored)
        assertEquals(1, db.ownerDao().activeCustomers(WORKSPACE).size)
        val late = records().softDeleteCustomer(CUSTOMER, NOW + 6)
        assertEquals(RecordResult.Applied, late)
        val lateUndo = db.auditDao().latestUndo()!!
        val expired = records().undo(lateUndo.operationId, NOW + 6 + 10_000, UndoChannel.SNACKBAR) as UndoOutcome.Blocked
        assertEquals("expired", expired.reason)
        assertTrue(db.ownerDao().customer(CUSTOMER)!!.deletedAtUtcMs != null)
        val fromActivity = records().undo(lateUndo.operationId, NOW + 6 + 10_000, UndoChannel.ACTIVITY) as UndoOutcome.Done
        assertEquals(lateUndo.eventId, fromActivity.originalEventId)
        assertNull(db.ownerDao().customer(CUSTOMER)!!.deletedAtUtcMs)
        assertTrue(db.verifyAudit().ok)
    }

    @Test fun conflictingRestoreAndAChildChangeAreExplained() {
        records().createWorkspace(WORKSPACE, "Office", NOW)
        records().createCustomer(CUSTOMER, WORKSPACE, cipher(), NOW, normalizedSearchToken = TOKEN)
        records().createBranch(BRANCH, WORKSPACE, CUSTOMER, cipher(), NOW + 5)
        val created = db.auditDao().undo(db.auditDao().entries(db.auditDao().chain()!!.chainId).first { it.entityId == CUSTOMER && it.action == AuditVocab.ACTION_CREATE }.operationId!!)!!
        val dependent = records().undo(created.operationId, NOW + 80_000, UndoChannel.ACTIVITY) as UndoOutcome.Blocked
        assertEquals("dependent", dependent.reason)
        assertNull(db.ownerDao().customer(CUSTOMER)!!.deletedAtUtcMs)

        records().softDeleteCustomer(CUSTOMER, NOW + 6)
        records().createCustomer(CUSTOMER_2, WORKSPACE, cipher(40), NOW + 7, normalizedSearchToken = TOKEN)
        val undoId = db.auditDao().entries(db.auditDao().chain()!!.chainId)
            .last { it.entityId == CUSTOMER && it.action == AuditVocab.ACTION_SOFT_DELETE }
            .operationId!!
        val conflict = records().undo(undoId, NOW + 8, UndoChannel.SNACKBAR) as UndoOutcome.Blocked
        assertEquals("conflict", conflict.reason)
        assertTrue(conflict.arabic.isNotBlank())
        assertTrue(conflict.english.isNotBlank())
        assertTrue(db.ownerDao().customer(CUSTOMER)!!.deletedAtUtcMs != null)
        assertNull(db.auditDao().undo(undoId)!!.usedAtUtcMs)
    }

    @Test fun killedWriteRollsBackTheAuditToo() {
        try {
            db.runInTransaction {
                assertEquals(RecordResult.Applied, records().createWorkspace(WORKSPACE, "Office", NOW))
                throw IllegalStateException("killed")
            }
        } catch (error: IllegalStateException) {
            assertEquals("killed", error.message)
        }
        assertNull(db.ownerDao().workspace(WORKSPACE))
        assertEquals(0, db.auditDao().entryCount())
        assertNull(db.auditDao().chain())
    }

    @Test fun auditRowsCannotBeUpdatedOrDeletedAndAGapIsDetected() {
        records().createWorkspace(WORKSPACE, "Office", NOW)
        records().renameWorkspace(WORKSPACE, "Desk", NOW + 1)
        records().createProduct(PRODUCT, WORKSPACE, "store", "Store", 14, NOW)
        val sql = db.openHelper.writableDatabase
        val update = assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL("UPDATE audit_entries SET result = 'tampered'")
        }
        assertTrue(update.message.orEmpty().contains("audit_immutable"))
        val delete = assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL("DELETE FROM audit_entries WHERE sequence = 2")
        }
        assertTrue(delete.message.orEmpty().contains("audit_immutable"))
        val chainDelete = assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL("DELETE FROM audit_chain")
        }
        assertTrue(chainDelete.message.orEmpty().contains("audit_immutable"))
        assertThrows(SQLiteConstraintException::class.java) {
            sql.execSQL("DELETE FROM undo_records")
        }
        assertEquals(3, db.auditDao().entryCount())
        sql.execSQL("DROP TRIGGER audit_entries_no_delete")
        sql.execSQL("DELETE FROM audit_entries WHERE sequence = 2")
        val report = db.verifyAudit()
        assertTrue(ChainFault.GAP in report.faults)
        val dao = File("src/main/java/com/pixelplus/owner/data/db/AuditDao.kt")
            .let { if (it.exists()) it else File("app/$it") }
            .let { if (it.exists()) it else File("apps/pixel-plus-owner-mobile/app/$it") }
        val source = dao.readText()
        assertFalse(source.contains("@Delete"))
        assertFalse(source.contains("@Update"))
        assertFalse(source.contains("UPDATE audit_"))
        assertFalse(source.contains("DELETE FROM audit_"))
    }

    @Test fun concurrentWritersDoNotForkTheChain() {
        records().createWorkspace(WORKSPACE, "Office", NOW)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val errors = ConcurrentLinkedQueue<Throwable>()
        repeat(8) { index ->
            Thread {
                try {
                    start.await()
                    val id = productId(index)
                    val result = records().createProduct(id, WORKSPACE, "item$index", "Item $index", 14, NOW + index + 1)
                    if (result != RecordResult.Applied) errors += AssertionError("product $index was $result")
                } catch (error: Throwable) {
                    errors += error
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        assertTrue(errors.toList().joinToString("\n"), errors.isEmpty())
        val entries = db.auditDao().entries(db.auditDao().chain()!!.chainId)
        assertEquals(9, entries.size)
        assertEquals((1L..9L).toList(), entries.map { it.sequence })
        assertEquals(entries.map { it.eventId }.toSet().size, entries.size)
        assertTrue(db.verifyAudit().ok)
        assertEquals(8, db.ownerDao().activeProducts(WORKSPACE).size)
    }

    @Test fun version1FileMigratesAndKeepsTheWorkspace() {
        db.close()
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(path, null)
        val schema = JSONObject(locate("schemas/com.pixelplus.owner.data.db.OwnerDatabase/1.json").readText())
        val database = schema.getJSONObject("database")
        assertEquals(1, database.getInt("version"))
        val entities = database.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val table = entity.getString("tableName")
            raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.getJSONArray("indices")
            for (item in 0 until indices.length()) {
                raw.execSQL(indices.getJSONObject(item).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        OwnerMigrations.PARTIAL_INDEXES.forEach { index ->
            raw.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS $index ON workspaces(name)")
        }
        raw.execSQL("PRAGMA user_version = 1")
        raw.execSQL(
            "INSERT INTO workspaces (id, name, defaultCurrency, createdAtUtcMs, updatedAtUtcMs, revision) VALUES (?, ?, 'EGP', ?, ?, 1)",
            arrayOf<Any>(WORKSPACE, "Office", NOW, NOW),
        )
        raw.close()
        db = openNamed(name)
        assertEquals("Office", db.ownerDao().workspace(WORKSPACE)!!.name)
        assertEquals("2", pragma("PRAGMA user_version"))
        assertTrue(tableNames().containsAll(listOf("audit_chain", "audit_entries", "undo_records")))
        OwnerMigrations.PARTIAL_INDEXES.forEach { index ->
            assertTrue(masterSql().contains(index))
        }
        assertEquals(RecordResult.Applied, records().renameWorkspace(WORKSPACE, "Desk", NOW + 1))
        assertEquals("Desk", db.ownerDao().workspace(WORKSPACE)!!.name)
        assertTrue(db.verifyAudit().ok)
        assertEquals(RecordResult.Applied, records().createProduct(PRODUCT, WORKSPACE, "store", "Store", 14, NOW + 2))
        assertEquals(RecordResult.Conflict, records().createProduct(PRODUCT_2, WORKSPACE, "store", "Other", 14, NOW + 3))
        val migrated = JSONObject(locate("schemas/com.pixelplus.owner.data.db.OwnerDatabase/2.json").readText())
        assertEquals(2, migrated.getJSONObject("database").getInt("version"))
    }

    private fun openNamed(databaseName: String): OwnerDatabase =
        OwnerDatabase.open(
            context,
            databaseName,
            allowMainThreadQueries = true,
            runtime = AuditRuntime(ids, AesGcmAuditSealer.ephemeral()),
        )

    private fun records() = db.records()

    private fun cipher(size: Int = 32): ByteArray = ByteArray(size) { (it + 7).toByte() }

    private fun pragma(sql: String): String {
        val cursor = db.openHelper.writableDatabase.query(sql)
        try {
            check(cursor.moveToFirst())
            return cursor.getString(0)
        } finally {
            cursor.close()
        }
    }

    private fun tableNames(): Set<String> {
        val cursor = db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'")
        val names = mutableSetOf<String>()
        try {
            while (cursor.moveToNext()) names += cursor.getString(0)
        } finally {
            cursor.close()
        }
        return names
    }

    private fun masterSql(): String {
        val cursor = db.openHelper.writableDatabase.query("SELECT sql FROM sqlite_master WHERE sql IS NOT NULL")
        val out = StringBuilder()
        try {
            while (cursor.moveToNext()) out.append(cursor.getString(0)).append('\n')
        } finally {
            cursor.close()
        }
        return out.toString()
    }

    private fun locate(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"), File("apps/pixel-plus-owner-mobile/app/$relative"))
        return candidates.firstOrNull { it.exists() } ?: error("missing $relative")
    }

    private fun productId(index: Int): String {
        val tail = index.toString().padStart(12, '0')
        return "77777777-7777-4777-8777-$tail"
    }

    private fun <T : Throwable> assertThrows(type: Class<T>, body: () -> Unit): T {
        try {
            body()
        } catch (error: Throwable) {
            if (type.isInstance(error)) {
                @Suppress("UNCHECKED_CAST")
                return error as T
            }
            throw error
        }
        throw AssertionError("expected ${type.simpleName}")
    }

    private class CountingIds : IdSource {
        private val next = AtomicInteger(0)
        override fun next(): String {
            val value = next.incrementAndGet()
            return "abcdabcd-abcd-4abc-8abc-${value.toString().padStart(12, '0')}"
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val WORKSPACE = "11111111-1111-4111-8111-111111111111"
        const val CUSTOMER = "33333333-3333-4333-8333-333333333333"
        const val CUSTOMER_2 = "44444444-4444-4444-8444-444444444444"
        const val BRANCH = "55555555-5555-4555-8555-555555555555"
        const val PRODUCT = "77777777-7777-4777-8777-777777777777"
        const val PRODUCT_2 = "88888888-8888-4888-8888-888888888888"
        const val HUB = "99999999-9999-4999-8999-999999999999"
        const val LINK = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val ACTOR = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val TOKEN = "abcdef0123456789abcdef0123456789"
        const val SECRET = "010099988877"
    }
}
