package com.pixelplus.owner.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Offline intents and the hub pages kept beside them.
 *
 * The payload is sealed. A token, a licence code and a mailbox body are not columns.
 * A delivered row stays. Nothing here is removed.
 */

@Entity(
    tableName = "outbox",
    foreignKeys = [
        ForeignKey(
            entity = HubProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = OutboxOperationEntity::class,
            parentColumns = ["operationId"],
            childColumns = ["dependsOn"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["profileId", "idempotencyKey"], unique = true, name = "index_outbox_idempotency"),
        Index(value = ["status", "nextAttemptAt"], name = "index_outbox_status_attempt"),
        Index(value = ["profileId"], name = "index_outbox_profile"),
        Index(value = ["dependsOn"], name = "index_outbox_depends"),
    ],
)
data class OutboxOperationEntity(
    @PrimaryKey val operationId: String,
    val profileId: String,
    val originEpoch: Long,
    val credentialEpoch: Long,
    val operationType: String,
    val entityId: String,
    val payloadCiphertext: ByteArray,
    val payloadHash: String,
    val idempotencyKey: String,
    val expectedStateHash: String,
    val dependsOn: String?,
    val status: String,
    val attempts: Int,
    val nextAttemptAt: Long,
    val leaseUntil: Long?,
    val lastErrorCode: String?,
    val expiresAt: Long,
    val confirmedAt: Long?,
    val createdAtUtcMs: Long,
    val keyAliasVersion: Int,
)

@Entity(
    tableName = "sync_cursors",
    primaryKeys = ["profileId", "stream"],
    foreignKeys = [
        ForeignKey(
            entity = HubProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["profileId"], name = "index_sync_cursors_profile"),
    ],
)
data class SyncCursorEntity(
    val profileId: String,
    val stream: String,
    val cursor: String,
    val lastSuccess: Long?,
    val lastAttempt: Long,
    val coverageStart: Long?,
    val coverageEnd: Long?,
    val complete: Int,
)

@Entity(
    tableName = "licence_requests",
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
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["hubProfileId", "remoteId"], unique = true, name = "index_licence_requests_remote"),
        Index(
            value = ["hubProfileId", "serverStatus", "createdServerAt", "id"],
            name = "index_licence_requests_status",
        ),
        Index(value = ["workspaceId"], name = "index_licence_requests_workspace"),
        Index(value = ["customerId"], name = "index_licence_requests_customer"),
    ],
)
data class LicenceRequestEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
    @ColumnInfo(defaultValue = "1") val revision: Long,
    val deletedAtUtcMs: Long?,
    val hubProfileId: String,
    val remoteId: String,
    val productCode: String,
    val deviceCode: String,
    val customerId: String?,
    val kind: String,
    val serverStatus: String,
    val ownerDecision: String?,
    val ownerDecidedAt: Long?,
    val createdServerAt: Long,
    val fetchedAt: Long,
    val snapshotHash: String,
)

@Entity(
    tableName = "hub_events",
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
            childColumns = ["profileId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["profileId", "remoteEventId"], unique = true, name = "index_hub_events_remote"),
        Index(value = ["eventAt"], name = "index_hub_events_time"),
        Index(value = ["workspaceId"], name = "index_hub_events_workspace"),
    ],
)
data class HubEventEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
    @ColumnInfo(defaultValue = "1") val revision: Long,
    val deletedAtUtcMs: Long?,
    val profileId: String,
    val remoteEventId: String,
    val requestRemoteId: String?,
    val eventType: String,
    val eventAt: Long,
    val safeDetail: String,
)

@Entity(
    tableName = "mailbox_receipts",
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
            childColumns = ["profileId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["profileId", "batchId"], unique = true, name = "index_mailbox_receipts_batch"),
        Index(value = ["workspaceId"], name = "index_mailbox_receipts_workspace"),
    ],
)
data class MailboxReceiptEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
    @ColumnInfo(defaultValue = "1") val revision: Long,
    val deletedAtUtcMs: Long?,
    val profileId: String,
    val batchId: String,
    val installRemoteId: String,
    val receivedAt: Long,
    val byteCount: Long,
    val known: Int,
    val payloadDigest: String,
)

@Entity(
    tableName = "notification_receipts",
    foreignKeys = [
        ForeignKey(
            entity = HubProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["stableEventKey"], unique = true, name = "index_notification_receipts_key"),
        Index(value = ["profileId"], name = "index_notification_receipts_profile"),
    ],
)
data class NotificationReceiptEntity(
    @PrimaryKey val stableEventKey: String,
    val profileId: String,
    val scheduledAt: Long,
    val deliveredAt: Long?,
    val channel: String,
)
