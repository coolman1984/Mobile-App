package com.pixelplus.owner.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One chain for this install of the notebook.
 * The ids are random. They are not a hardware serial.
 * The row is never updated or deleted.
 */
@Entity(tableName = "audit_chain")
data class AuditChainEntity(
    @PrimaryKey val singleton: Int,
    val chainId: String,
    val deviceInstanceId: String,
    val actorType: String,
    val createdAtUtcMs: Long,
)

/**
 * Append-only audit row. The canonical body is stored as ciphertext.
 * There is no update and no delete.
 */
@Entity(
    tableName = "audit_entries",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = HubProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["hubProfileId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["occurredAtUtcMs"], name = "index_audit_entries_time"),
        Index(value = ["screen"], name = "index_audit_entries_screen"),
        Index(value = ["action"], name = "index_audit_entries_action"),
        Index(value = ["entityType", "entityId"], name = "index_audit_entries_entity"),
        Index(value = ["result"], name = "index_audit_entries_result"),
        Index(value = ["workspaceId"], name = "index_audit_entries_workspace"),
        Index(value = ["hubProfileId"], name = "index_audit_entries_hub"),
        Index(
            value = ["chainId", "sequence"],
            unique = true,
            name = "index_audit_entries_chain_sequence",
        ),
    ],
)
data class AuditEntryEntity(
    @PrimaryKey val eventId: String,
    val schemaVersion: Int,
    val chainId: String,
    val sequence: Long,
    val deviceInstanceId: String,
    val actorType: String,
    val workspaceId: String,
    val hubProfileId: String?,
    val occurredAtUtcMs: Long,
    val cairoLocalIso: String,
    val cairoOffsetSeconds: Int,
    val zoneId: String,
    val screen: String,
    val action: String,
    val entityType: String,
    val entityId: String?,
    val beforeSafe: String,
    val afterSafe: String,
    val result: String,
    val reasonCode: String?,
    val explanationAr: String,
    val explanationEn: String,
    val explanationTemplateVersion: Int,
    val correlationId: String,
    val originalEventId: String?,
    val operationId: String?,
    val previousHash: String,
    val entryHash: String,
    val canonicalVersion: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val canonicalCiphertext: ByteArray,
    val keyAliasVersion: Int,
)

/**
 * One local inverse. Marking it used is the only change allowed.
 * It points at the audit event of the original action.
 */
@Entity(
    tableName = "undo_records",
    foreignKeys = [
        ForeignKey(
            entity = AuditEntryEntity::class,
            parentColumns = ["eventId"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["expiresAtUtcMs"], name = "index_undo_records_expiry"),
        Index(value = ["eventId"], name = "index_undo_records_event"),
        Index(value = ["workspaceId"], name = "index_undo_records_workspace"),
        Index(value = ["entityType", "entityId"], name = "index_undo_records_entity"),
    ],
)
data class UndoRecordEntity(
    @PrimaryKey val operationId: String,
    val eventId: String,
    val workspaceId: String,
    val entityType: String,
    val entityId: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val inverseCiphertext: ByteArray,
    val expectedRevision: Long,
    val expiresAtUtcMs: Long,
    val usedAtUtcMs: Long?,
    val invalidatedAtUtcMs: Long?,
    val undoClass: String,
    val createdAtUtcMs: Long,
    val keyAliasVersion: Int,
)
