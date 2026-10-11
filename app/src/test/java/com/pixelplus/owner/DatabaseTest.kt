package com.pixelplus.owner

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import com.pixelplus.owner.data.db.CustomerEntity
import com.pixelplus.owner.data.db.OwnerDatabase
import com.pixelplus.owner.data.db.OwnerMigrations
import com.pixelplus.owner.data.db.ProductEntity
import com.pixelplus.owner.data.db.RecordResult
import com.pixelplus.owner.data.db.RecordRules
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

/**
 * The notebook is a real SQLite file: foreign keys, partial uniqueness,
 * soft-delete and a transaction that dies before commit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DatabaseTest {
    private lateinit var context: android.content.Context
    private lateinit var name: String
    private lateinit var db: OwnerDatabase

    @Before
    fun open() {
        context = RuntimeEnvironment.getApplication()
        name = "m02-${UUID.randomUUID()}.db"
        db = OwnerDatabase.open(context, name, allowMainThreadQueries = true)
    }

    @After
    fun close() {
        runCatching { db.close() }
        context.deleteDatabase(name)
    }

    @Test fun freshDatabaseIsEmptyAndDoesNotImportTheDemo() {
        val dao = db.ownerDao()
        assertEquals(0, dao.workspaceCount())
        assertEquals(0, dao.customerCount())
        assertEquals(0, dao.productCount())
        assertEquals(0, dao.linkCount())
        assertEquals(0, dao.branchCount())
        assertEquals(0, dao.hubCount())
        val stored = masterSql(db) + schemaFile().readText()
        assertFalse(stored.contains("شركة تجريبية"))
        assertFalse(stored.contains("demo-1"))
        assertFalse(stored.contains("DemoSeed"))
    }

    @Test fun journalModeIsWalAndForeignKeysStayOn() {
        records().createWorkspace(WORKSPACE, "المكتب", NOW)
        assertEquals("wal", pragma(db, "PRAGMA journal_mode"))
        assertEquals("1", pragma(db, "PRAGMA foreign_keys"))
        assertEquals("1", helperPragma(db, "PRAGMA foreign_keys"))
        db.close()
        db = OwnerDatabase.open(context, name, allowMainThreadQueries = true)
        assertEquals("wal", pragma(db, "PRAGMA journal_mode"))
        assertEquals("1", pragma(db, "PRAGMA foreign_keys"))
        assertEquals("1", helperPragma(db, "PRAGMA foreign_keys"))
        assertEquals("المكتب", db.ownerDao().workspace(WORKSPACE)!!.name)
        val wal = File(context.getDatabasePath(name).path + "-wal")
        assertTrue(pragma(db, "PRAGMA journal_mode") == "wal" || wal.exists())
    }

    @Test fun schemaExportMatchesTheOpenDatabase() {
        val file = schemaFile()
        val root = JSONObject(file.readText())
        val database = root.getJSONObject("database")
        assertEquals(1, root.getInt("formatVersion"))
        assertEquals(OwnerDatabase.VERSION, database.getInt("version"))
        val identity = database.getString("identityHash")
        assertTrue(Regex("^[0-9a-f]{32,64}$").matches(identity))
        assertEquals(identity, identityHash(db))
        val entities = database.getJSONArray("entities")
        assertEquals(OwnerDatabase.TABLE_NAMES.size, entities.length())
        val names = mutableSetOf<String>()
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            names += entity.getString("tableName")
            val fields = entity.getJSONArray("fields")
            for (fieldIndex in 0 until fields.length()) {
                val field = fields.getJSONObject(fieldIndex)
                assertNotEquals("REAL", field.getString("affinity"))
                val column = field.getString("columnName")
                assertFalse(column.equals("token", ignoreCase = true))
                assertFalse(column.equals("secret", ignoreCase = true))
                assertFalse(column.equals("password", ignoreCase = true))
                assertFalse(column.equals("phone", ignoreCase = true))
            }
            if (entity.getString("tableName") !in setOf("workspaces", "audit_chain")) {
                val foreignKeys = entity.getJSONArray("foreignKeys")
                assertTrue(foreignKeys.length() > 0)
                for (fk in 0 until foreignKeys.length()) {
                    assertEquals("RESTRICT", foreignKeys.getJSONObject(fk).getString("onDelete"))
                    assertEquals("RESTRICT", foreignKeys.getJSONObject(fk).getString("onUpdate"))
                }
            }
        }
        assertEquals(OwnerDatabase.TABLE_NAMES.toSet(), names)
        val setup = database.getJSONArray("setupQueries").join("\n")
        assertTrue(setup.contains("room_master_table"))
        assertFalse(setup.contains("customers"))
        val live = tableNames(db)
        assertTrue(live.containsAll(OwnerDatabase.TABLE_NAMES))
        assertTrue(live.contains("room_master_table"))
        val allowed = OwnerDatabase.TABLE_NAMES.toSet() + setOf("room_master_table", "android_metadata")
        assertTrue(live.all { it in allowed })
        val sql = masterSql(db)
        assertTrue(sql.contains("index_products_active_code"))
        assertTrue(sql.contains("index_customer_links_active_remote"))
        assertTrue(sql.contains("WHERE deletedAtUtcMs IS NULL"))
        assertFalse(file.readText().contains("fallbackToDestructive"))
    }

    @Test fun foreignKeyRejectsOrphansAndPhysicalDelete() {
        records().createWorkspace(WORKSPACE, "Office", NOW)
        val orphan = customer(workspaceId = OTHER)
        assertThrows(SQLiteConstraintException::class.java) { db.ownerDao().insertCustomer(orphan) }
        assertEquals(0, db.ownerDao().customerCount())
        assertEquals(RecordResult.Applied, records().createCustomer(CUSTOMER, WORKSPACE, cipher(), NOW))
        assertThrows(SQLiteConstraintException::class.java) {
            db.openHelper.writableDatabase.execSQL("DELETE FROM customers")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            db.openHelper.writableDatabase.execSQL("DELETE FROM workspaces")
        }
        assertEquals(1, db.ownerDao().customerCount())
        assertEquals(1, db.ownerDao().workspaceCount())
    }

    @Test fun activeIdentityIsUniqueAndATombstoneCanShareIt() {
        records().createWorkspace(WORKSPACE, "Office", NOW)
        assertEquals(
            RecordResult.Applied,
            records().createProduct(PRODUCT, WORKSPACE, "store", "الستور", 14, NOW),
        )
        assertEquals(RecordResult.Conflict, records().createProduct(PRODUCT_2, WORKSPACE, "store", "Store", 14, NOW))
        assertEquals(RecordResult.Applied, records().softDeleteProduct(PRODUCT, NOW + 1))
        assertEquals(RecordResult.Applied, records().createProduct(PRODUCT_2, WORKSPACE, "store", "Store", 30, NOW + 2))
        assertEquals(RecordResult.Conflict, records().restoreProduct(PRODUCT, NOW + 3))
        assertTrue(db.ownerDao().product(PRODUCT)!!.deletedAtUtcMs != null)
        assertEquals("Store", db.ownerDao().product(PRODUCT_2)!!.displayName)
        assertEquals(1, db.ownerDao().activeProducts(WORKSPACE).size)
    }

    @Test fun softDeleteHidesTheCustomerAndKeepsTheBranch() {
        office()
        assertEquals(RecordResult.Applied, records().createCustomer(CUSTOMER, WORKSPACE, cipher(), NOW, normalizedSearchToken = TOKEN))
        assertEquals(
            RecordResult.Applied,
            records().createBranch(BRANCH, WORKSPACE, CUSTOMER, cipher(), NOW, isDefault = true),
        )
        assertEquals(1, db.ownerDao().activeBranches(WORKSPACE).size)
        assertEquals(RecordResult.Applied, records().softDeleteCustomer(CUSTOMER, NOW + 5))
        assertTrue(db.ownerDao().activeCustomers(WORKSPACE).isEmpty())
        assertTrue(db.ownerDao().activeBranches(WORKSPACE).isEmpty())
        val branch = db.ownerDao().branch(BRANCH)!!
        assertNull(branch.deletedAtUtcMs)
        assertTrue(branch.encryptedLabel.contentEquals(cipher()))
        assertEquals(RecordResult.Applied, records().softDeleteBranch(BRANCH, NOW + 6))
        assertEquals(RecordResult.InactiveParent, records().restoreBranch(BRANCH, NOW + 7))
        assertTrue(db.ownerDao().branch(BRANCH)!!.deletedAtUtcMs != null)
        assertEquals(RecordResult.Applied, records().restoreCustomer(CUSTOMER, NOW + 7))
        assertTrue(db.ownerDao().activeBranches(WORKSPACE).isEmpty())
        assertEquals(RecordResult.Applied, records().restoreBranch(BRANCH, NOW + 8))
        assertEquals(1, db.ownerDao().activeBranches(WORKSPACE).size)
        assertEquals(3, db.ownerDao().customer(CUSTOMER)!!.revision)
    }

    @Test fun restoreRejectsAReusedSearchTokenAndDefaultBranch() {
        office()
        records().createCustomer(CUSTOMER, WORKSPACE, cipher(), NOW, normalizedSearchToken = TOKEN)
        records().softDeleteCustomer(CUSTOMER, NOW + 1)
        assertEquals(
            RecordResult.Applied,
            records().createCustomer(CUSTOMER_2, WORKSPACE, cipher(40), NOW + 2, normalizedSearchToken = TOKEN),
        )
        assertEquals(RecordResult.Conflict, records().restoreCustomer(CUSTOMER, NOW + 3))
        assertTrue(db.ownerDao().customer(CUSTOMER)!!.deletedAtUtcMs != null)
        records().createBranch(BRANCH, WORKSPACE, CUSTOMER_2, cipher(), NOW + 2, isDefault = true)
        records().softDeleteBranch(BRANCH, NOW + 3)
        records().createBranch(BRANCH_2, WORKSPACE, CUSTOMER_2, cipher(40), NOW + 4, isDefault = true)
        assertEquals(RecordResult.Conflict, records().restoreBranch(BRANCH, NOW + 5))
        assertEquals(BRANCH_2, db.ownerDao().activeBranches(WORKSPACE).single().id)
    }

    @Test fun linkCannotCrossWorkspacesAndRestoreNeedsBothParents() {
        office()
        records().createWorkspace(OTHER, "Other", NOW, "USD")
        records().createCustomer(CUSTOMER, WORKSPACE, cipher(), NOW)
        records().createCustomer(CUSTOMER_2, OTHER, cipher(), NOW)
        records().createHubProfile(HUB, WORKSPACE, "https://Factory.Example/ ", NOW)
        records().createHubProfile(HUB_2, OTHER, "https://other.example", NOW)
        assertEquals("https://factory.example", db.ownerDao().hubProfile(HUB)!!.normalizedUrl)
        assertEquals(
            RecordResult.WorkspaceMismatch,
            records().createLink(LINK, WORKSPACE, CUSTOMER_2, HUB, "install", "device-1", ACTOR, NOW),
        )
        assertEquals(
            RecordResult.WorkspaceMismatch,
            records().createLink(LINK, OTHER, CUSTOMER, HUB_2, "install", "device-1", ACTOR, NOW),
        )
        assertEquals(0, db.ownerDao().linkCount())
        assertEquals(
            RecordResult.Applied,
            records().createLink(LINK, WORKSPACE, CUSTOMER, HUB, "install", "device-1", ACTOR, NOW),
        )
        assertEquals(
            RecordResult.Conflict,
            records().createLink(LINK_2, WORKSPACE, CUSTOMER, HUB, "install", "device-1", ACTOR, NOW),
        )
        assertEquals(
            RecordResult.Applied,
            records().createLink(LINK_2, WORKSPACE, CUSTOMER, HUB, "licence_request", "device-1", ACTOR, NOW),
        )
        records().softDeleteLink(LINK, NOW + 1)
        records().softDeleteHub(HUB, NOW + 1)
        assertEquals(RecordResult.InactiveParent, records().restoreLink(LINK, NOW + 2))
        assertEquals(RecordResult.Applied, records().restoreHub(HUB, NOW + 2))
        records().softDeleteCustomer(CUSTOMER, NOW + 3)
        assertEquals(RecordResult.InactiveParent, records().restoreLink(LINK, NOW + 4))
        assertEquals(RecordResult.Applied, records().restoreCustomer(CUSTOMER, NOW + 4))
        assertEquals(RecordResult.Applied, records().restoreLink(LINK, NOW + 5))
        assertEquals(2, db.ownerDao().activeLinks(WORKSPACE).size)
        assertTrue(db.ownerDao().activeLinks(OTHER).isEmpty())
    }

    @Test fun killedTransactionRollsBackAndACommitSurvivesReopen() {
        val raw = db.openHelper.writableDatabase
        raw.beginTransaction()
        raw.execSQL(
            """
            INSERT INTO workspaces (id, name, defaultCurrency, createdAtUtcMs, updatedAtUtcMs, revision)
            VALUES ('$WORKSPACE', 'Temp', 'EGP', $NOW, $NOW, 1)
            """.trimIndent(),
        )
        raw.endTransaction()
        assertNull(db.ownerDao().workspace(WORKSPACE))

        assertEquals(RecordResult.Applied, records().createWorkspace(WORKSPACE, "Office", NOW))
        try {
            db.runInTransaction {
                db.ownerDao().insertCustomer(customer())
                db.ownerDao().insertProduct(product())
                throw IllegalStateException("killed")
            }
        } catch (error: IllegalStateException) {
            assertEquals("killed", error.message)
        }
        assertEquals(0, db.ownerDao().customerCount())
        assertEquals(0, db.ownerDao().productCount())
        assertEquals("Office", db.ownerDao().workspace(WORKSPACE)!!.name)

        db.close()
        val again = OwnerDatabase.open(context, name, allowMainThreadQueries = true)
        try {
            assertEquals("Office", again.ownerDao().workspace(WORKSPACE)!!.name)
            assertEquals(0, again.ownerDao().customerCount())
        } finally {
            again.close()
        }
    }

    @Test fun closingMidTransactionDropsTheUncommittedRow() {
        val path = context.getDatabasePath(name)
        val raw = db.openHelper.writableDatabase
        raw.beginTransaction()
        raw.execSQL(
            """
            INSERT INTO workspaces (id, name, defaultCurrency, createdAtUtcMs, updatedAtUtcMs, revision)
            VALUES ('$OTHER', 'Gone', 'EGP', $NOW, $NOW, 1)
            """.trimIndent(),
        )
        db.close()
        val check = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val cursor = check.rawQuery("SELECT count(*) FROM workspaces WHERE id = ?", arrayOf(OTHER))
            cursor.use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(0, row.getInt(0))
            }
        } finally {
            check.close()
        }
        db = OwnerDatabase.open(context, name, allowMainThreadQueries = true)
    }

    @Test fun aNewerFileIsRefusedAndLeftInPlace() {
        assertEquals(RecordResult.Applied, records().createWorkspace(WORKSPACE, "Office", NOW))
        val path = context.getDatabasePath(name)
        db.close()
        val raw = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE)
        raw.execSQL("PRAGMA user_version = 9")
        raw.close()
        val failed = OwnerDatabase.open(context, name, allowMainThreadQueries = true)
        val error = assertThrows(IllegalStateException::class.java) {
            failed.openHelper.writableDatabase
        }
        assertTrue(error.message.orEmpty().contains("migration", ignoreCase = true))
        runCatching { failed.close() }
        assertTrue(path.exists())
        val check = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val cursor = check.rawQuery("SELECT name FROM workspaces", null)
            cursor.use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("Office", row.getString(0))
            }
        } finally {
            check.close()
        }
        db = OwnerDatabase.open(context, name, allowMainThreadQueries = true)
    }

    @Test fun trialCurrencyUrlAndCipherRulesRejectBadRows() {
        office()
        assertEquals(RecordResult.Rejected, records().createProduct(PRODUCT, WORKSPACE, "Store", "Ledger", 14, NOW))
        assertEquals(RecordResult.Rejected, records().createProduct(PRODUCT, WORKSPACE, "store", "Ledger", 0, NOW))
        assertEquals(RecordResult.Rejected, records().createProduct(PRODUCT, WORKSPACE, "store", "Ledger", 61, NOW))
        assertEquals(RecordResult.Rejected, records().createProduct(PRODUCT, WORKSPACE, "store", "دفتر", 14, NOW, remoteTrialDays = 0))
        assertEquals(RecordResult.Applied, records().createProduct(PRODUCT, WORKSPACE, "store", "دفتر", 1, NOW, remoteTrialDays = 60))
        assertEquals("دفتر", db.ownerDao().product(PRODUCT)!!.displayName)
        assertEquals(RecordResult.Rejected, records().createWorkspace(OTHER, "Second", NOW, "egp"))
        assertEquals(RecordResult.Applied, records().createWorkspace(OTHER, "Second", NOW, "USD"))
        assertEquals("USD", db.ownerDao().workspace(OTHER)!!.defaultCurrency)
        assertEquals("EGP", db.ownerDao().workspace(WORKSPACE)!!.defaultCurrency)
        assertEquals(RecordResult.Rejected, records().createHubProfile(HUB, WORKSPACE, "http://factory.example", NOW))
        assertEquals(RecordResult.Rejected, records().createHubProfile(HUB, WORKSPACE, "https://user:secret@factory.example", NOW))
        assertEquals(RecordResult.Rejected, records().createHubProfile(HUB, WORKSPACE, "https://factory.example/licence", NOW))
        assertEquals(RecordResult.Rejected, records().createCustomer(CUSTOMER, WORKSPACE, cipher(4), NOW))
        assertEquals(RecordResult.Rejected, records().createCustomer(CUSTOMER, WORKSPACE, cipher(), NOW, normalizedSearchToken = "أحمد"))
        assertEquals(RecordResult.Applied, records().createHubProfile(HUB, WORKSPACE, "HTTPS://Factory.Example/", NOW, capabilitiesJson = """{"b":true,"a":1}"""))
        assertEquals("""{"a":1,"b":true}""", db.ownerDao().hubProfile(HUB)!!.capabilitiesJson)
        assertEquals(RecordResult.Rejected, records().createHubProfile(HUB_2, WORKSPACE, "https://two.example", NOW, capabilitiesJson = """{"token":true}"""))
        assertEquals(0, db.ownerDao().hubCount() - 1)
        val columns = columns(db, "hub_profiles")
        assertFalse(columns.any { it.contains("token", ignoreCase = true) })
        assertFalse(columns.any { it.contains("secret", ignoreCase = true) })
    }

    @Test fun pausedIsNotDeletionAndConstraintsCanBeAppliedTwice() {
        office()
        records().createCustomer(CUSTOMER, WORKSPACE, cipher(), NOW)
        assertEquals(RecordResult.Applied, records().setCustomerStatus(CUSTOMER, RecordRules.STATUS_PAUSED, NOW + 1))
        assertEquals(1, db.ownerDao().activeCustomers(WORKSPACE).size)
        assertEquals(RecordResult.AlreadyActive, records().restoreCustomer(CUSTOMER, NOW + 2))
        OwnerMigrations.applyConstraints(db.openHelper.writableDatabase)
        OwnerMigrations.applyConstraints(db.openHelper.writableDatabase)
        assertEquals(RecordRules.STATUS_PAUSED, db.ownerDao().customer(CUSTOMER)!!.localStatus)
    }

    @Test fun directInsertStillObeysTrialAndScopeGuards() {
        office()
        val badTrial = product().copy(localTrialDays = 0)
        assertThrows(SQLiteConstraintException::class.java) { db.ownerDao().insertProduct(badTrial) }
        records().createCustomer(CUSTOMER, WORKSPACE, cipher(), NOW)
        records().createHubProfile(HUB, WORKSPACE, "https://factory.example", NOW)
        records().softDeleteCustomer(CUSTOMER, NOW + 1)
        val link = com.pixelplus.owner.data.db.CustomerLinkEntity(
            id = LINK,
            workspaceId = WORKSPACE,
            createdAtUtcMs = NOW + 2,
            updatedAtUtcMs = NOW + 2,
            revision = 1,
            deletedAtUtcMs = null,
            customerId = CUSTOMER,
            hubProfileId = HUB,
            remoteType = "install",
            remoteId = "device-1",
            linkedBy = ACTOR,
        )
        val error = assertThrows(SQLiteConstraintException::class.java) { db.ownerDao().insertLink(link) }
        assertTrue(error.message.orEmpty().contains("parent_inactive"))
        assertEquals(0, db.ownerDao().linkCount())
    }

    @Test fun sourceDoesNotEnableDestructiveMigrationOrSeedTheDemo() {
        val text = listOf(
            "src/main/java/com/pixelplus/owner/data/db",
            "src/main/java/com/pixelplus/owner/audit",
            "src/main/java/com/pixelplus/owner/domain",
        ).flatMap { relative ->
            locate(relative).walk().filter { it.extension == "kt" }
        }.joinToString("\n") { it.readText() }
        assertFalse(text.contains("fallbackToDestructive"))
        assertFalse(text.contains("DemoSeed"))
        assertFalse(text.contains("شركة تجريبية"))
        assertTrue(text.contains("WRITE_AHEAD_LOGGING"))
        assertTrue(text.contains("exportSchema = true"))
    }

    private fun office() {
        assertEquals(RecordResult.Applied, records().createWorkspace(WORKSPACE, "المكتب", NOW))
    }

    private fun records() = db.records()

    private fun customer(workspaceId: String = WORKSPACE) = CustomerEntity(
        id = CUSTOMER,
        workspaceId = workspaceId,
        createdAtUtcMs = NOW,
        updatedAtUtcMs = NOW,
        revision = 1,
        deletedAtUtcMs = null,
        encryptedDisplayName = cipher(),
        encryptedPhone = null,
        normalizedSearchToken = null,
        localStatus = RecordRules.STATUS_ACTIVE,
    )

    private fun product() = ProductEntity(
        id = PRODUCT,
        workspaceId = WORKSPACE,
        createdAtUtcMs = NOW,
        updatedAtUtcMs = NOW,
        revision = 1,
        deletedAtUtcMs = null,
        productCode = "ledger",
        displayName = "Daily ledger",
        localTrialDays = 14,
        remoteTrialDays = null,
        policyVersion = null,
        source = RecordRules.SOURCE_LOCAL,
    )

    private fun cipher(size: Int = 32): ByteArray = ByteArray(size) { (it + 1).toByte() }

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

    companion object {
        private const val NOW = 1_700_000_000_000L
        private const val WORKSPACE = "11111111-1111-4111-8111-111111111111"
        private const val OTHER = "22222222-2222-4222-8222-222222222222"
        private const val CUSTOMER = "33333333-3333-4333-8333-333333333333"
        private const val CUSTOMER_2 = "44444444-4444-4444-8444-444444444444"
        private const val BRANCH = "55555555-5555-4555-8555-555555555555"
        private const val BRANCH_2 = "66666666-6666-4666-8666-666666666666"
        private const val PRODUCT = "77777777-7777-4777-8777-777777777777"
        private const val PRODUCT_2 = "88888888-8888-4888-8888-888888888888"
        private const val HUB = "99999999-9999-4999-8999-999999999999"
        private const val HUB_2 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        private const val LINK = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        private const val LINK_2 = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        private const val ACTOR = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        private const val TOKEN = "abcdef0123456789abcdef0123456789"

        private fun schemaFile(): File =
            locate("schemas/com.pixelplus.owner.data.db.OwnerDatabase/${OwnerDatabase.VERSION}.json")

        private fun locate(relative: String): File {
            val candidates = listOf(File(relative), File("app/$relative"), File("apps/pixel-plus-owner-mobile/app/$relative"))
            return candidates.firstOrNull { it.exists() }
                ?: error("missing $relative from ${File(".").absolutePath}; tried $candidates")
        }

        private fun helperPragma(db: OwnerDatabase, sql: String): String {
            val cursor = db.openHelper.writableDatabase.query(sql)
            try {
                check(cursor.moveToFirst())
                return cursor.getString(0)
            } finally {
                cursor.close()
            }
        }

        private fun pragma(db: OwnerDatabase, sql: String): String {
            val cursor = db.query(sql, null)
            try {
                check(cursor.moveToFirst())
                return cursor.getString(0)
            } finally {
                cursor.close()
            }
        }

        private fun identityHash(db: OwnerDatabase): String {
            val cursor = db.openHelper.writableDatabase.query(
                "SELECT identity_hash FROM room_master_table WHERE id = 42",
            )
            try {
                check(cursor.moveToFirst())
                return cursor.getString(0)
            } finally {
                cursor.close()
            }
        }

        private fun tableNames(db: OwnerDatabase): Set<String> {
            val cursor = db.openHelper.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table'",
            )
            val names = mutableSetOf<String>()
            try {
                while (cursor.moveToNext()) names += cursor.getString(0)
            } finally {
                cursor.close()
            }
            return names
        }

        private fun masterSql(db: OwnerDatabase): String {
            val cursor = db.openHelper.writableDatabase.query(
                "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL",
            )
            val out = StringBuilder()
            try {
                while (cursor.moveToNext()) {
                    out.append(cursor.getString(0)).append('\n')
                }
            } finally {
                cursor.close()
            }
            return out.toString()
        }

        private fun columns(db: OwnerDatabase, table: String): List<String> {
            val cursor = db.openHelper.writableDatabase.query("PRAGMA table_info($table)")
            val names = mutableListOf<String>()
            try {
                while (cursor.moveToNext()) names += cursor.getString(1)
            } finally {
                cursor.close()
            }
            return names
        }
    }
}

private fun android.database.Cursor.use(body: (android.database.Cursor) -> Unit) {
    try {
        body(this)
    } finally {
        close()
    }
}
