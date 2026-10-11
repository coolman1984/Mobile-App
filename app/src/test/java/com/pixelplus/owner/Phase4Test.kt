package com.pixelplus.owner

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.pixelplus.owner.data.db.OwnerDatabase
import com.pixelplus.owner.data.db.OwnerMigrations
import com.pixelplus.owner.data.db.RecordResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/** Version 4: customer notes, follow-ups and cash entries on a real SQLite file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class Phase4Test {
    private lateinit var context: android.content.Context
    private lateinit var name: String
    private lateinit var db: OwnerDatabase

    private val ws = "11111111-1111-4111-8111-111111111111"
    private val cust = "22222222-2222-4222-8222-222222222222"
    private val now = 1_800_000_000_000L
    private fun id(n: Int) = "33333333-3333-4333-8333-%012d".format(n)
    private fun cipher(size: Int = 40) = ByteArray(size) { (it + 1).toByte() }
    private fun records() = db.records()

    @Before fun open() {
        context = RuntimeEnvironment.getApplication()
        name = "m-v4-${UUID.randomUUID()}.db"
        db = OwnerDatabase.open(context, name, allowMainThreadQueries = true)
        assertEquals(RecordResult.Applied, records().createWorkspace(ws, "Office", now))
        assertEquals(RecordResult.Applied, records().createCustomer(cust, ws, cipher(), now))
    }

    @After fun close() {
        runCatching { db.close() }
        context.deleteDatabase(name)
    }

    @Test fun aNoteIsStoredSealedAndHidingItKeepsIt() {
        assertEquals(RecordResult.Applied, records().createNote(id(1), ws, cust, cipher(60), now + 1))
        assertEquals(1, db.ownerDao().activeNotes(cust).size)
        assertEquals(RecordResult.Applied, records().softDeleteNote(id(1), now + 2))
        assertEquals(0, db.ownerDao().activeNotes(cust).size)
        assertNotNull(db.ownerDao().note(id(1)))
        assertEquals(RecordResult.Rejected, records().createNote(id(2), ws, cust, ByteArray(3), now + 3))
        assertEquals(RecordResult.Missing, records().createNote(id(2), ws, id(99), cipher(), now + 3))
    }

    @Test fun aNoteOnAHiddenCustomerIsRefused() {
        assertEquals(RecordResult.Applied, records().softDeleteCustomer(cust, now + 1))
        assertEquals(RecordResult.InactiveParent, records().createNote(id(1), ws, cust, cipher(), now + 2))
        assertEquals(RecordResult.InactiveParent, records().postPayment(id(2), ws, cust, 100, "EGP", now, null, now + 2))
    }

    @Test fun aFollowUpCanBeCompletedOnceAndHidden() {
        assertEquals(RecordResult.Applied, records().createFollowUp(id(1), ws, cust, cipher(), now + 86_400_000, 30, now))
        assertEquals(RecordResult.Applied, records().createFollowUp(id(2), ws, null, cipher(), now + 1000, 0, now))
        assertEquals(listOf(id(2), id(1)), db.ownerDao().activeFollowUps(ws).map { it.id })
        assertEquals(RecordResult.Applied, records().completeFollowUp(id(2), now + 5))
        assertEquals(RecordResult.AlreadyActive, records().completeFollowUp(id(2), now + 6))
        assertEquals(listOf(id(1), id(2)), db.ownerDao().activeFollowUps(ws).map { it.id })   // done ones go last
        assertEquals(RecordResult.Applied, records().softDeleteFollowUp(id(1), now + 7))
        assertEquals(1, db.ownerDao().activeFollowUps(ws).size)
        assertEquals(RecordResult.Rejected, records().createFollowUp(id(3), ws, null, cipher(), now, 99_999, now))
        assertEquals(RecordResult.Missing, records().completeFollowUp(id(77), now))
    }

    @Test fun cashIsReversedByAnOppositeEntryAndTheOriginalNeverChanges() {
        assertEquals(RecordResult.Applied, records().postPayment(id(1), ws, cust, 125_050, "EGP", now, cipher(), now))
        assertEquals(RecordResult.Applied, records().reversePayment(id(1), id(2), id(3), now + 10))
        val rows = db.ownerDao().paymentsOfCustomer(cust)
        assertEquals(listOf(125_050L, -125_050L), rows.map { it.amountMinor })
        assertEquals(id(1), rows[1].reversalOfId)
        assertEquals(0L, rows.sumOf { it.amountMinor })
        assertEquals(125_050L, db.ownerDao().payment(id(1))!!.amountMinor)
        assertEquals(RecordResult.Conflict, records().reversePayment(id(1), id(4), id(5), now + 11))
        assertEquals(RecordResult.Rejected, records().reversePayment(id(2), id(6), id(7), now + 12))   // a reversal is not reversed
        assertEquals(RecordResult.Missing, records().reversePayment(id(88), id(8), id(9), now + 13))
    }

    @Test fun cashRulesRefuseBadAmountsAndCurrencies() {
        assertEquals(RecordResult.Rejected, records().postPayment(id(1), ws, cust, 0, "EGP", now, null, now))
        assertEquals(RecordResult.Rejected, records().postPayment(id(1), ws, cust, Long.MIN_VALUE, "EGP", now, null, now))
        assertEquals(RecordResult.Rejected, records().postPayment(id(1), ws, cust, 5, "egp", now, null, now))
        assertEquals(RecordResult.Rejected, records().postPayment(id(1), ws, cust, 5, "EGP", -1, null, now))
        assertEquals(0, db.ownerDao().paymentsOfCustomer(cust).size)
    }

    @Test fun postedCashCannotBeEditedOrDeletedByAnyone() {
        assertEquals(RecordResult.Applied, records().postPayment(id(1), ws, cust, 500, "EGP", now, null, now))
        val raw = db.openHelper.writableDatabase
        assertThrowsSql { raw.execSQL("UPDATE payment_entries SET amountMinor = 1") }
        assertThrowsSql { raw.execSQL("DELETE FROM payment_entries") }
        assertEquals(500L, db.ownerDao().payment(id(1))!!.amountMinor)
        assertEquals(RecordResult.Applied, records().createNote(id(5), ws, cust, cipher(60), now + 1))
        assertEquals(RecordResult.Applied, records().createFollowUp(id(6), ws, null, cipher(), now + 9, 0, now + 1))
        assertThrowsSql { raw.execSQL("DELETE FROM notes") }
        assertThrowsSql { raw.execSQL("DELETE FROM follow_ups") }
        assertEquals(1, db.ownerDao().activeNotes(cust).size)
    }

    @Test fun neitherAmountNorTextEntersTheAuditChain() {
        records().createNote(id(1), ws, cust, cipher(60), now + 1)
        records().postPayment(id(2), ws, cust, 987_654_321, "EGP", now, cipher(), now + 2)
        records().reversePayment(id(2), id(3), id(4), now + 3)
        val chain = db.auditDao().chain()!!
        val sealer = db.auditRuntime.sealer
        val links = db.auditDao().entries(chain.chainId).map { com.pixelplus.owner.audit.ChainLink(it.previousHash, it.entryHash, sealer.open(it.canonicalCiphertext)) }
        val report = com.pixelplus.owner.audit.AuditCanonical.verify(links)
        val text = com.pixelplus.owner.audit.AuditExport.markdown(links, report) + com.pixelplus.owner.audit.AuditExport.json(links, report)
        assertTrue(report.ok)
        assertFalse(text.contains("987654321") || text.contains("987_654_321"))
        assertTrue(text.contains("EGP"))
        assertTrue(links.any { String(it.canonicalBytes).contains("\"money\"") })
    }

    @Test fun theV4MigrationStatementsBuildTheSameTablesRoomCreates() {
        fun shape(sql: SQLiteDatabase, table: String): List<String> {
            val out = mutableListOf<String>()
            sql.rawQuery("PRAGMA table_info(`$table`)", null).use { c -> while (c.moveToNext()) out += listOf(c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getInt(5)).joinToString("|") }
            sql.rawQuery("PRAGMA index_list(`$table`)", null).use { c -> while (c.moveToNext()) if (!c.getString(1).startsWith("sqlite_")) out += "idx:" + c.getString(1) + ":" + c.getInt(2) }
            sql.rawQuery("PRAGMA foreign_key_list(`$table`)", null).use { c -> while (c.moveToNext()) out += "fk:" + c.getString(2) + ":" + c.getString(3) + ":" + c.getString(4) + ":" + c.getString(5) + ":" + c.getString(6) }
            return out.sorted()
        }
        val plain = SQLiteDatabase.create(null)
        try {
            OwnerMigrations.V4_TABLES.forEach { plain.execSQL(it) }
            val room = db.openHelper.writableDatabase
            for (table in listOf("notes", "follow_ups", "payment_entries")) {
                val fromRoom = room.query("PRAGMA table_info(`$table`)").use { c -> val l = mutableListOf<String>(); while (c.moveToNext()) l += listOf(c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getInt(5)).joinToString("|"); l }
                val fromMigration = mutableListOf<String>()
                plain.rawQuery("PRAGMA table_info(`$table`)", null).use { c -> while (c.moveToNext()) fromMigration += listOf(c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getInt(5)).joinToString("|") }
                assertEquals(table, fromRoom.sorted(), fromMigration.sorted())
                val roomIdx = room.query("PRAGMA index_list(`$table`)").use { c -> val l = mutableListOf<String>(); while (c.moveToNext()) if (!c.getString(1).startsWith("sqlite_")) l += c.getString(1) + ":" + c.getInt(2); l }
                assertEquals(table, roomIdx.sorted(), shape(plain, table).filter { it.startsWith("idx:") }.map { it.removePrefix("idx:") }.sorted())
            }
        } finally {
            plain.close()
        }
    }

    private fun assertThrowsSql(body: () -> Unit) {
        try {
            body()
            throw AssertionError("expected the database to refuse")
        } catch (e: SQLiteConstraintException) {
        } catch (e: SQLiteException) {
        }
    }
}
