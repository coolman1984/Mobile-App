package com.pixelplus.owner.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 is the notebook. Version 2 adds the audit chain and undo rows.
 *
 * Partial unique indexes and guards that Room cannot express are applied when
 * the file is created and again whenever it is opened. Room compares indexes
 * after a migration, so the partial indexes are dropped for that check and
 * put back when the file opens. A missing migration must fail and leave the
 * file in place. Do not wipe or recreate this database.
 */
internal object OwnerMigrations {
    val ALL: Array<Migration>
        get() = arrayOf(MIGRATION_1_2)

    /**
     * Indexes Room did not declare. They are recreated by [applyConstraints].
     */
    val PARTIAL_INDEXES: List<String> = listOf(
        "index_hub_profiles_active_url",
        "index_customers_active_token",
        "index_products_active_code",
        "index_customer_links_active_remote",
        "index_branches_one_default",
    )

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            PARTIAL_INDEXES.forEach { name -> db.execSQL("DROP INDEX IF EXISTS `$name`") }
            AUDIT_TABLES.forEach { db.execSQL(it) }
        }
    }

    fun applyConstraints(db: SupportSQLiteDatabase) {
        STATEMENTS.forEach { db.execSQL(it) }
    }

    val STATEMENTS: List<String> by lazy {
        buildList {
        listOf(
            "workspaces",
            "hub_profiles",
            "customers",
            "branches",
            "products",
            "customer_links",
        ).forEach { table ->
            add(
                """
                CREATE TRIGGER IF NOT EXISTS ${table}_no_delete
                BEFORE DELETE ON $table
                BEGIN
                SELECT RAISE(ABORT, 'physical_delete');
                END
                """.trimIndent(),
            )
        }
        add(insertClock("workspaces"))
        add(updateClock("workspaces", hasWorkspace = false, hasDeleted = false))
        add(
            """
            CREATE TRIGGER IF NOT EXISTS workspaces_shape
            BEFORE INSERT ON workspaces
            WHEN length(NEW.name) < 1 OR length(NEW.name) > ${RecordRules.LABEL_MAX}
            OR length(NEW.defaultCurrency) != 3
            OR NEW.defaultCurrency GLOB '*[^A-Z]*'
            BEGIN
            SELECT RAISE(ABORT, 'bad_workspace');
            END
            """.trimIndent(),
        )
        add(
            """
            CREATE TRIGGER IF NOT EXISTS workspaces_currency_frozen
            BEFORE UPDATE ON workspaces
            WHEN NEW.defaultCurrency != OLD.defaultCurrency
            OR length(NEW.name) < 1 OR length(NEW.name) > ${RecordRules.LABEL_MAX}
            BEGIN
            SELECT RAISE(ABORT, 'immutable_column');
            END
            """.trimIndent(),
        )
        add(insertClock("hub_profiles"))
        add(updateClock("hub_profiles"))
        add(urlShape("INSERT", "hub_profiles_insert_url"))
        add(urlShape("UPDATE", "hub_profiles_update_url"))
        add(
            """
            CREATE TRIGGER IF NOT EXISTS hub_profiles_origin
            BEFORE UPDATE ON hub_profiles
            WHEN NEW.normalizedUrl != OLD.normalizedUrl AND NEW.originEpoch <= OLD.originEpoch
            BEGIN
            SELECT RAISE(ABORT, 'origin_epoch');
            END
            """.trimIndent(),
        )
        add(capabilities("INSERT"))
        add(capabilities("UPDATE"))
        add(insertClock("customers"))
        add(updateClock("customers"))
        add(cipher("customers", "INSERT", "encryptedDisplayName", RecordRules.NAME_CIPHER_MAX, phone = true))
        add(cipher("customers", "UPDATE", "encryptedDisplayName", RecordRules.NAME_CIPHER_MAX, phone = true))
        add(token("INSERT"))
        add(token("UPDATE"))
        add(status("INSERT"))
        add(status("UPDATE"))
        add(insertClock("branches"))
        add(updateClock("branches"))
        add(parentScope("branches", "INSERT", "customerId", "customers"))
        add(parentScope("branches", "UPDATE", "customerId", "customers"))
        add(
            """
            CREATE TRIGGER IF NOT EXISTS branches_customer_frozen
            BEFORE UPDATE ON branches
            WHEN NEW.customerId != OLD.customerId
            BEGIN
            SELECT RAISE(ABORT, 'immutable_column');
            END
            """.trimIndent(),
        )
        add(cipher("branches", "INSERT", "encryptedLabel", RecordRules.NAME_CIPHER_MAX, phone = false))
        add(cipher("branches", "UPDATE", "encryptedLabel", RecordRules.NAME_CIPHER_MAX, phone = false))
        add(insertClock("products"))
        add(updateClock("products"))
        add(trial("INSERT"))
        add(trial("UPDATE"))
        add(productCode("INSERT"))
        add(
            """
            CREATE TRIGGER IF NOT EXISTS products_code_frozen
            BEFORE UPDATE ON products
            WHEN NEW.productCode != OLD.productCode
            OR NEW.source NOT IN ('${RecordRules.SOURCE_LOCAL}', '${RecordRules.SOURCE_HUB}')
            OR length(NEW.displayName) < 1 OR length(NEW.displayName) > ${RecordRules.LABEL_MAX}
            BEGIN
            SELECT RAISE(ABORT, 'immutable_column');
            END
            """.trimIndent(),
        )
        add(insertClock("customer_links"))
        add(updateClock("customer_links"))
        add(linkScope("INSERT"))
        add(linkScope("UPDATE"))
        add(
            """
            CREATE TRIGGER IF NOT EXISTS customer_links_identity_frozen
            BEFORE UPDATE ON customer_links
            WHEN NEW.customerId != OLD.customerId
            OR NEW.hubProfileId != OLD.hubProfileId
            OR NEW.remoteType != OLD.remoteType
            OR NEW.remoteId != OLD.remoteId
            OR NEW.linkedBy != OLD.linkedBy
            BEGIN
            SELECT RAISE(ABORT, 'immutable_column');
            END
            """.trimIndent(),
        )
        add(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS index_hub_profiles_active_url
            ON hub_profiles(workspaceId, normalizedUrl)
            WHERE deletedAtUtcMs IS NULL
            """.trimIndent(),
        )
        add(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS index_customers_active_token
            ON customers(workspaceId, normalizedSearchToken)
            WHERE deletedAtUtcMs IS NULL AND normalizedSearchToken IS NOT NULL
            """.trimIndent(),
        )
        add(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS index_products_active_code
            ON products(workspaceId, productCode)
            WHERE deletedAtUtcMs IS NULL
            """.trimIndent(),
        )
        add(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS index_customer_links_active_remote
            ON customer_links(hubProfileId, remoteType, remoteId)
            WHERE deletedAtUtcMs IS NULL
            """.trimIndent(),
        )
        add(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS index_branches_one_default
            ON branches(customerId)
            WHERE isDefault = 1 AND deletedAtUtcMs IS NULL
            """.trimIndent(),
        )
        addAll(AUDIT_GUARDS)
        }
    }

    private val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

    private val AUDIT_TABLES: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `audit_chain` (`singleton` INTEGER NOT NULL, `chainId` TEXT NOT NULL, `deviceInstanceId` TEXT NOT NULL, `actorType` TEXT NOT NULL, `createdAtUtcMs` INTEGER NOT NULL, PRIMARY KEY(`singleton`))
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `audit_entries` (`eventId` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `chainId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, `deviceInstanceId` TEXT NOT NULL, `actorType` TEXT NOT NULL, `workspaceId` TEXT NOT NULL, `hubProfileId` TEXT, `occurredAtUtcMs` INTEGER NOT NULL, `cairoLocalIso` TEXT NOT NULL, `cairoOffsetSeconds` INTEGER NOT NULL, `zoneId` TEXT NOT NULL, `screen` TEXT NOT NULL, `action` TEXT NOT NULL, `entityType` TEXT NOT NULL, `entityId` TEXT, `beforeSafe` TEXT NOT NULL, `afterSafe` TEXT NOT NULL, `result` TEXT NOT NULL, `reasonCode` TEXT, `explanationAr` TEXT NOT NULL, `explanationEn` TEXT NOT NULL, `explanationTemplateVersion` INTEGER NOT NULL, `correlationId` TEXT NOT NULL, `originalEventId` TEXT, `operationId` TEXT, `previousHash` TEXT NOT NULL, `entryHash` TEXT NOT NULL, `canonicalVersion` INTEGER NOT NULL, `canonicalCiphertext` BLOB NOT NULL, `keyAliasVersion` INTEGER NOT NULL, PRIMARY KEY(`eventId`), FOREIGN KEY(`workspaceId`) REFERENCES `workspaces`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT , FOREIGN KEY(`hubProfileId`) REFERENCES `hub_profiles`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_audit_entries_time` ON `audit_entries` (`occurredAtUtcMs`)",
        "CREATE INDEX IF NOT EXISTS `index_audit_entries_screen` ON `audit_entries` (`screen`)",
        "CREATE INDEX IF NOT EXISTS `index_audit_entries_action` ON `audit_entries` (`action`)",
        "CREATE INDEX IF NOT EXISTS `index_audit_entries_entity` ON `audit_entries` (`entityType`, `entityId`)",
        "CREATE INDEX IF NOT EXISTS `index_audit_entries_result` ON `audit_entries` (`result`)",
        "CREATE INDEX IF NOT EXISTS `index_audit_entries_workspace` ON `audit_entries` (`workspaceId`)",
        "CREATE INDEX IF NOT EXISTS `index_audit_entries_hub` ON `audit_entries` (`hubProfileId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_audit_entries_chain_sequence` ON `audit_entries` (`chainId`, `sequence`)",
        """
        CREATE TABLE IF NOT EXISTS `undo_records` (`operationId` TEXT NOT NULL, `eventId` TEXT NOT NULL, `workspaceId` TEXT NOT NULL, `entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, `inverseCiphertext` BLOB NOT NULL, `expectedRevision` INTEGER NOT NULL, `expiresAtUtcMs` INTEGER NOT NULL, `usedAtUtcMs` INTEGER, `invalidatedAtUtcMs` INTEGER, `undoClass` TEXT NOT NULL, `createdAtUtcMs` INTEGER NOT NULL, `keyAliasVersion` INTEGER NOT NULL, PRIMARY KEY(`operationId`), FOREIGN KEY(`eventId`) REFERENCES `audit_entries`(`eventId`) ON UPDATE RESTRICT ON DELETE RESTRICT , FOREIGN KEY(`workspaceId`) REFERENCES `workspaces`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_undo_records_expiry` ON `undo_records` (`expiresAtUtcMs`)",
        "CREATE INDEX IF NOT EXISTS `index_undo_records_event` ON `undo_records` (`eventId`)",
        "CREATE INDEX IF NOT EXISTS `index_undo_records_workspace` ON `undo_records` (`workspaceId`)",
        "CREATE INDEX IF NOT EXISTS `index_undo_records_entity` ON `undo_records` (`entityType`, `entityId`)",
    )

    private val AUDIT_GUARDS: List<String> = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS audit_chain_no_update
        BEFORE UPDATE ON audit_chain
        BEGIN
        SELECT RAISE(ABORT, 'audit_immutable');
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS audit_chain_no_delete
        BEFORE DELETE ON audit_chain
        BEGIN
        SELECT RAISE(ABORT, 'audit_immutable');
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS audit_chain_shape
        BEFORE INSERT ON audit_chain
        WHEN NEW.singleton != 1
        OR NEW.actorType != 'owner'
        OR length(NEW.chainId) != 36
        OR length(NEW.deviceInstanceId) != 36
        BEGIN
        SELECT RAISE(ABORT, 'bad_chain');
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS audit_entries_no_update
        BEFORE UPDATE ON audit_entries
        BEGIN
        SELECT RAISE(ABORT, 'audit_immutable');
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS audit_entries_no_delete
        BEFORE DELETE ON audit_entries
        BEGIN
        SELECT RAISE(ABORT, 'audit_immutable');
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS audit_entries_link
        BEFORE INSERT ON audit_entries
        WHEN NEW.sequence < 1
        OR length(NEW.previousHash) != 64
        OR length(NEW.entryHash) != 64
        OR NEW.previousHash GLOB '*[^0-9a-f]*'
        OR NEW.entryHash GLOB '*[^0-9a-f]*'
        OR (NEW.sequence = 1 AND NEW.previousHash != '$GENESIS')
        OR (
        NEW.sequence > 1
        AND NOT EXISTS (
        SELECT 1 FROM audit_entries
        WHERE chainId = NEW.chainId
        AND sequence = NEW.sequence - 1
        AND entryHash = NEW.previousHash
        )
        )
        BEGIN
        SELECT RAISE(ABORT, 'audit_link');
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS undo_records_no_delete
        BEFORE DELETE ON undo_records
        BEGIN
        SELECT RAISE(ABORT, 'audit_immutable');
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS undo_records_frozen
        BEFORE UPDATE ON undo_records
        WHEN NEW.operationId != OLD.operationId
        OR NEW.eventId != OLD.eventId
        OR NEW.workspaceId != OLD.workspaceId
        OR NEW.entityType != OLD.entityType
        OR NEW.entityId != OLD.entityId
        OR NEW.inverseCiphertext != OLD.inverseCiphertext
        OR NEW.expectedRevision != OLD.expectedRevision
        OR NEW.expiresAtUtcMs != OLD.expiresAtUtcMs
        OR NEW.undoClass != OLD.undoClass
        OR NEW.createdAtUtcMs != OLD.createdAtUtcMs
        OR NEW.keyAliasVersion != OLD.keyAliasVersion
        OR (OLD.usedAtUtcMs IS NOT NULL AND NEW.usedAtUtcMs IS NOT OLD.usedAtUtcMs)
        OR (OLD.invalidatedAtUtcMs IS NOT NULL AND NEW.invalidatedAtUtcMs IS NOT OLD.invalidatedAtUtcMs)
        OR (
        NEW.usedAtUtcMs IS NOT NULL
        AND NEW.invalidatedAtUtcMs IS NOT NULL
        AND OLD.usedAtUtcMs IS NULL
        AND OLD.invalidatedAtUtcMs IS NULL
        )
        BEGIN
        SELECT RAISE(ABORT, 'undo_frozen');
        END
        """.trimIndent(),
    )

    private fun insertClock(table: String): String {
        val deleted = if (table == "workspaces") "" else "\nOR NEW.deletedAtUtcMs IS NOT NULL"
        return """
            CREATE TRIGGER IF NOT EXISTS ${table}_insert_clock
            BEFORE INSERT ON $table
            WHEN NEW.revision != 1
            OR NEW.createdAtUtcMs < 0
            OR NEW.updatedAtUtcMs != NEW.createdAtUtcMs$deleted
            BEGIN
            SELECT RAISE(ABORT, 'bad_timestamp');
            END
        """.trimIndent()
    }

    private fun updateClock(table: String, hasWorkspace: Boolean = true, hasDeleted: Boolean = true): String {
        val workspace = if (hasWorkspace) "\nOR NEW.workspaceId != OLD.workspaceId" else ""
        val deleted = if (hasDeleted) {
            "\nOR (NEW.deletedAtUtcMs IS NOT NULL AND NEW.deletedAtUtcMs < NEW.createdAtUtcMs)"
        } else {
            ""
        }
        return """
            CREATE TRIGGER IF NOT EXISTS ${table}_update_clock
            BEFORE UPDATE ON $table
            WHEN NEW.id != OLD.id
            OR NEW.createdAtUtcMs != OLD.createdAtUtcMs
            OR NEW.revision != OLD.revision + 1
            OR NEW.updatedAtUtcMs < NEW.createdAtUtcMs$workspace$deleted
            BEGIN
            SELECT RAISE(ABORT, 'bad_timestamp');
            END
        """.trimIndent()
    }

    private fun urlShape(event: String, name: String): String = """
        CREATE TRIGGER IF NOT EXISTS $name
        BEFORE $event ON hub_profiles
        WHEN NEW.normalizedUrl NOT GLOB 'https://*'
        OR NEW.normalizedUrl GLOB '*[@?# ]*'
        OR NEW.normalizedUrl GLOB 'https://*/*'
        OR length(NEW.normalizedUrl) < 12
        OR length(NEW.normalizedUrl) > ${RecordRules.URL_MAX}
        OR NEW.originEpoch < 1
        BEGIN
        SELECT RAISE(ABORT, 'bad_url');
        END
    """.trimIndent()

    private fun capabilities(event: String): String = """
        CREATE TRIGGER IF NOT EXISTS hub_profiles_${event.lowercase()}_capabilities
        BEFORE $event ON hub_profiles
        WHEN NEW.capabilitiesJson NOT GLOB '{*}'
        OR length(NEW.capabilitiesJson) > ${RecordRules.CAPABILITIES_MAX}
        OR lower(NEW.capabilitiesJson) GLOB '*token*'
        OR lower(NEW.capabilitiesJson) GLOB '*secret*'
        OR lower(NEW.capabilitiesJson) GLOB '*password*'
        OR lower(NEW.capabilitiesJson) GLOB '*bearer*'
        OR lower(NEW.capabilitiesJson) GLOB '*auth*'
        OR lower(NEW.capabilitiesJson) GLOB '*cookie*'
        OR lower(NEW.capabilitiesJson) GLOB '*session*'
        OR lower(NEW.capabilitiesJson) GLOB '*credential*'
        BEGIN
        SELECT RAISE(ABORT, 'bad_capabilities');
        END
    """.trimIndent()

    private fun cipher(table: String, event: String, column: String, max: Int, phone: Boolean): String {
        val phoneCheck = if (phone) {
            """
            OR (NEW.encryptedPhone IS NOT NULL AND (
            length(NEW.encryptedPhone) < ${RecordRules.CIPHER_MIN}
            OR length(NEW.encryptedPhone) > ${RecordRules.PHONE_CIPHER_MAX}))
            """.trimIndent().prependIndent("\n")
        } else {
            ""
        }
        return """
            CREATE TRIGGER IF NOT EXISTS ${table}_${event.lowercase()}_cipher
            BEFORE $event ON $table
            WHEN length(NEW.$column) < ${RecordRules.CIPHER_MIN}
            OR length(NEW.$column) > $max$phoneCheck
            BEGIN
            SELECT RAISE(ABORT, 'bad_ciphertext');
            END
        """.trimIndent()
    }

    private fun token(event: String): String = """
        CREATE TRIGGER IF NOT EXISTS customers_${event.lowercase()}_token
        BEFORE $event ON customers
        WHEN NEW.normalizedSearchToken IS NOT NULL AND (
        length(NEW.normalizedSearchToken) < ${RecordRules.TOKEN_MIN}
        OR length(NEW.normalizedSearchToken) > ${RecordRules.TOKEN_MAX}
        OR NEW.normalizedSearchToken GLOB '*[^0-9a-f]*')
        BEGIN
        SELECT RAISE(ABORT, 'bad_token');
        END
    """.trimIndent()

    private fun status(event: String): String = """
        CREATE TRIGGER IF NOT EXISTS customers_${event.lowercase()}_status
        BEFORE $event ON customers
        WHEN NEW.localStatus NOT IN ('${RecordRules.STATUS_ACTIVE}', '${RecordRules.STATUS_PAUSED}')
        BEGIN
        SELECT RAISE(ABORT, 'bad_status');
        END
    """.trimIndent()

    private fun parentScope(table: String, event: String, column: String, parent: String): String = """
        CREATE TRIGGER IF NOT EXISTS ${table}_${event.lowercase()}_parent
        BEFORE $event ON $table
        WHEN (
        EXISTS (SELECT 1 FROM $parent WHERE id = NEW.$column)
        AND (SELECT workspaceId FROM $parent WHERE id = NEW.$column) != NEW.workspaceId
        )
        OR (
        NEW.deletedAtUtcMs IS NULL
        AND EXISTS (SELECT 1 FROM $parent WHERE id = NEW.$column AND deletedAtUtcMs IS NOT NULL)
        )
        BEGIN
        SELECT CASE
        WHEN EXISTS (
        SELECT 1 FROM $parent WHERE id = NEW.$column AND workspaceId != NEW.workspaceId
        ) THEN RAISE(ABORT, 'workspace_mismatch')
        ELSE RAISE(ABORT, 'parent_inactive')
        END;
        END
    """.trimIndent()

    private fun trial(event: String): String = """
        CREATE TRIGGER IF NOT EXISTS products_${event.lowercase()}_trial
        BEFORE $event ON products
        WHEN NEW.localTrialDays < ${RecordRules.TRIAL_MIN}
        OR NEW.localTrialDays > ${RecordRules.TRIAL_MAX}
        OR NEW.remoteTrialDays < ${RecordRules.TRIAL_MIN}
        OR NEW.remoteTrialDays > ${RecordRules.TRIAL_MAX}
        OR (NEW.policyVersion IS NOT NULL AND NEW.policyVersion < 1)
        OR NEW.source NOT IN ('${RecordRules.SOURCE_LOCAL}', '${RecordRules.SOURCE_HUB}')
        OR length(NEW.displayName) < 1 OR length(NEW.displayName) > ${RecordRules.LABEL_MAX}
        OR NEW.productCode GLOB '*[^a-z0-9._-]*'
        OR length(NEW.productCode) < 1 OR length(NEW.productCode) > 64
        BEGIN
        SELECT RAISE(ABORT, 'trial_days');
        END
    """.trimIndent()

    private fun productCode(event: String): String = """
        CREATE TRIGGER IF NOT EXISTS products_${event.lowercase()}_code
        BEFORE $event ON products
        WHEN NEW.productCode NOT GLOB '[a-z0-9]*'
        BEGIN
        SELECT RAISE(ABORT, 'bad_product_code');
        END
    """.trimIndent()

    private fun linkScope(event: String): String = """
        CREATE TRIGGER IF NOT EXISTS customer_links_${event.lowercase()}_scope
        BEFORE $event ON customer_links
        WHEN NEW.remoteType NOT IN ('${RecordRules.REMOTE_INSTALL}', '${RecordRules.REMOTE_LICENCE_REQUEST}')
        OR length(NEW.remoteId) < 1 OR length(NEW.remoteId) > 128
        OR NEW.remoteId GLOB '*[^A-Za-z0-9._:-]*'
        OR NEW.linkedBy NOT GLOB '[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]-*'
        OR (
        EXISTS (SELECT 1 FROM customers WHERE id = NEW.customerId)
        AND (SELECT workspaceId FROM customers WHERE id = NEW.customerId) != NEW.workspaceId
        )
        OR (
        EXISTS (SELECT 1 FROM hub_profiles WHERE id = NEW.hubProfileId)
        AND (SELECT workspaceId FROM hub_profiles WHERE id = NEW.hubProfileId) != NEW.workspaceId
        )
        OR (
        NEW.deletedAtUtcMs IS NULL
        AND EXISTS (SELECT 1 FROM customers WHERE id = NEW.customerId AND deletedAtUtcMs IS NOT NULL)
        )
        OR (
        NEW.deletedAtUtcMs IS NULL
        AND EXISTS (SELECT 1 FROM hub_profiles WHERE id = NEW.hubProfileId AND deletedAtUtcMs IS NOT NULL)
        )
        BEGIN
        SELECT CASE
        WHEN EXISTS (SELECT 1 FROM customers WHERE id = NEW.customerId AND workspaceId != NEW.workspaceId)
        OR EXISTS (SELECT 1 FROM hub_profiles WHERE id = NEW.hubProfileId AND workspaceId != NEW.workspaceId)
        THEN RAISE(ABORT, 'workspace_mismatch')
        WHEN NEW.deletedAtUtcMs IS NULL AND (
        EXISTS (SELECT 1 FROM customers WHERE id = NEW.customerId AND deletedAtUtcMs IS NOT NULL)
        OR EXISTS (SELECT 1 FROM hub_profiles WHERE id = NEW.hubProfileId AND deletedAtUtcMs IS NOT NULL)
        )
        THEN RAISE(ABORT, 'parent_inactive')
        ELSE RAISE(ABORT, 'bad_remote')
        END;
        END
    """.trimIndent()
}
