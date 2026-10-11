package com.pixelplus.owner.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.pixelplus.owner.audit.OutboxSeal
import com.pixelplus.owner.audit.AuditRuntime
import com.pixelplus.owner.audit.AuditSealer
import com.pixelplus.owner.audit.ChainReport
import com.pixelplus.owner.audit.PlainAuditSealer
import com.pixelplus.owner.audit.RandomIdSource

/**
 * Private notebook. The demo in memory is not copied here.
 * Write-ahead logging is on. Foreign keys are on for every connection this class opens.
 * A newer file with no migration is refused. The file is not deleted or rebuilt.
 */
@Database(
    entities = [
        WorkspaceEntity::class,
        HubProfileEntity::class,
        CustomerEntity::class,
        BranchEntity::class,
        ProductEntity::class,
        CustomerLinkEntity::class,
        AuditChainEntity::class,
        AuditEntryEntity::class,
        UndoRecordEntity::class,
        OutboxOperationEntity::class,
        SyncCursorEntity::class,
        LicenceRequestEntity::class,
        HubEventEntity::class,
        MailboxReceiptEntity::class,
        NotificationReceiptEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class OwnerDatabase : RoomDatabase() {
    abstract fun ownerDao(): OwnerDao

    abstract fun auditDao(): AuditDao

    abstract fun syncDao(): SyncDao

    @Volatile
    internal var auditRuntime: AuditRuntime = AuditRuntime(RandomIdSource, PlainAuditSealer)

    @Volatile
    internal var outboxSealer: AuditSealer = PlainAuditSealer

    internal fun records(): OwnerRecords = OwnerRecords(this)

    internal fun verifyAudit(): ChainReport = AuditWriter(this).verify()

    companion object {
        const val DATABASE_NAME = "pixel-plus-owner.db"
        const val VERSION = 3

        val TABLE_NAMES: List<String> = listOf(
            "workspaces",
            "hub_profiles",
            "customers",
            "branches",
            "products",
            "customer_links",
            "audit_chain",
            "audit_entries",
            "undo_records",
            "outbox",
            "sync_cursors",
            "licence_requests",
            "hub_events",
            "mailbox_receipts",
            "notification_receipts",
        )

        fun open(
            context: Context,
            name: String = DATABASE_NAME,
            allowMainThreadQueries: Boolean = false,
            runtime: AuditRuntime? = null,
        ): OwnerDatabase {
            val builder = Room.databaseBuilder(context, OwnerDatabase::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .openHelperFactory(ForeignKeyOpenHelperFactory)
                .addMigrations(*OwnerMigrations.ALL)
                .addCallback(ConstraintCallback)
            if (allowMainThreadQueries) builder.allowMainThreadQueries()
            val database = builder.build()
            val resolved = runtime ?: AuditRuntime.resolve(context)
            database.auditRuntime = resolved
            database.outboxSealer = if (runtime != null) {
                resolved.sealer
            } else {
                OutboxSeal.resolve()
            }
            return database
        }
    }
}

/**
 * Room 2.7 does not turn foreign keys on. This helper does, on each connection,
 * before any transaction starts.
 */
private object ForeignKeyOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    private val delegate = FrameworkSQLiteOpenHelperFactory()

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val wrapped = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name)
            .callback(ForeignKeyCallback(configuration.callback))
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
            .build()
        return delegate.create(wrapped)
    }
}

private class ForeignKeyCallback(
    private val delegate: SupportSQLiteOpenHelper.Callback,
) : SupportSQLiteOpenHelper.Callback(delegate.version) {
    override fun onConfigure(db: SupportSQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        delegate.onConfigure(db)
    }

    override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)

    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        delegate.onUpgrade(db, oldVersion, newVersion)
    }

    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        delegate.onDowngrade(db, oldVersion, newVersion)
    }

    override fun onOpen(db: SupportSQLiteDatabase) = delegate.onOpen(db)

    override fun onCorruption(db: SupportSQLiteDatabase) = delegate.onCorruption(db)
}

private object ConstraintCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        // Room opens every connection with foreign keys already enabled.
        // They cannot be toggled from this callback: it runs inside a transaction.
        OwnerMigrations.applyConstraints(db)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        OwnerMigrations.applyConstraints(db)
    }
}
