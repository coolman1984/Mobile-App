package com.pixelplus.owner.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncDao {
    @Query("SELECT * FROM workspaces ORDER BY createdAtUtcMs, id LIMIT 1")
    fun firstWorkspace(): WorkspaceEntity?

    @Query(
        """
        SELECT * FROM hub_profiles
        WHERE workspaceId = :workspaceId AND normalizedUrl = :url AND deletedAtUtcMs IS NULL
        LIMIT 1
        """,
    )
    fun activeHub(workspaceId: String, url: String): HubProfileEntity?

    @Query("SELECT * FROM hub_profiles WHERE id = :id")
    fun hub(id: String): HubProfileEntity?

    @Query(
        """
        UPDATE hub_profiles
        SET credentialEpoch = credentialEpoch + 1,
            dispatchPaused = 1,
            updatedAtUtcMs = :nowUtcMs,
            revision = revision + 1
        WHERE id = :id AND deletedAtUtcMs IS NULL
        """,
    )
    fun pauseCredential(id: String, nowUtcMs: Long): Int

    @Query(
        """
        UPDATE hub_profiles
        SET dispatchPaused = :paused,
            updatedAtUtcMs = :nowUtcMs,
            revision = revision + 1
        WHERE id = :id AND deletedAtUtcMs IS NULL
        """,
    )
    fun setPaused(id: String, paused: Int, nowUtcMs: Long): Int

    @Query(
        """
        UPDATE hub_profiles
        SET originEpoch = :originEpoch,
            normalizedUrl = :url,
            updatedAtUtcMs = :nowUtcMs,
            revision = revision + 1
        WHERE id = :id AND deletedAtUtcMs IS NULL
        """,
    )
    fun changeOrigin(id: String, url: String, originEpoch: Long, nowUtcMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertOutbox(row: OutboxOperationEntity)

    @Query("SELECT * FROM outbox WHERE operationId = :id")
    fun outbox(id: String): OutboxOperationEntity?

    @Query(
        """
        SELECT * FROM outbox
        WHERE profileId = :profileId
        AND entityId = :entityId
        AND operationType = :type
        AND status IN (:active)
        AND idempotencyKey LIKE :prefix
        ORDER BY createdAtUtcMs DESC
        LIMIT 1
        """,
    )
    fun activeDecision(
        profileId: String,
        entityId: String,
        type: String,
        active: List<String>,
        prefix: String,
    ): OutboxOperationEntity?

    @Query(
        """
        SELECT * FROM outbox
        WHERE profileId = :profileId
        AND entityId = :entityId
        AND operationType = :type
        AND idempotencyKey LIKE :prefix
        ORDER BY createdAtUtcMs DESC
        LIMIT 1
        """,
    )
    fun latestDecision(
        profileId: String,
        entityId: String,
        type: String,
        prefix: String,
    ): OutboxOperationEntity?

    @Query(
        """
        SELECT COUNT(*) FROM outbox
        WHERE profileId = :profileId AND idempotencyKey LIKE :prefix
        """,
    )
    fun decisionCount(profileId: String, prefix: String): Int

    @Query(
        """
        SELECT * FROM outbox
        WHERE profileId = :profileId
        AND status IN ('QUEUED', 'RETRY_WAIT', 'SENT', 'LEASED', 'CONFIRMED_LOCAL', 'AUTH_BLOCKED')
        ORDER BY createdAtUtcMs, operationId
        """,
    )
    fun openForProfile(profileId: String): List<OutboxOperationEntity>

    @Query(
        """
        UPDATE outbox
        SET status = :status,
            attempts = :attempts,
            nextAttemptAt = :nextAttemptAt,
            leaseUntil = :leaseUntil,
            lastErrorCode = :lastErrorCode,
            confirmedAt = :confirmedAt,
            credentialEpoch = :credentialEpoch
        WHERE operationId = :id AND status = :expectedStatus AND attempts = :expectedAttempts
        AND leaseUntil IS :expectedLeaseUntil AND credentialEpoch = :expectedCredentialEpoch
        """,
    )
    fun compareAndWrite(
        id: String,
        expectedStatus: String,
        expectedAttempts: Int,
        expectedLeaseUntil: Long?,
        expectedCredentialEpoch: Long,
        status: String,
        attempts: Int,
        nextAttemptAt: Long,
        leaseUntil: Long?,
        lastErrorCode: String?,
        confirmedAt: Long?,
        credentialEpoch: Long,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM outbox
        WHERE profileId = :profileId
        AND status IN ('QUEUED', 'RETRY_WAIT', 'SENT', 'LEASED', 'CONFIRMED_LOCAL')
        """,
    )
    fun waitingCount(profileId: String): Int

    @Query(
        """
        SELECT MIN(createdAtUtcMs) FROM outbox
        WHERE profileId = :profileId
        AND status IN ('QUEUED', 'RETRY_WAIT', 'SENT', 'LEASED', 'CONFIRMED_LOCAL')
        """,
    )
    fun oldestWaitingAt(profileId: String): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertRequest(row: LicenceRequestEntity)

    @Query(
        """
        SELECT * FROM licence_requests
        WHERE hubProfileId = :profileId AND remoteId = :remoteId
        LIMIT 1
        """,
    )
    fun requestByRemote(profileId: String, remoteId: String): LicenceRequestEntity?

    @Query(
        """
        UPDATE licence_requests
        SET serverStatus = :serverStatus,
            ownerDecision = :ownerDecision,
            ownerDecidedAt = :ownerDecidedAt,
            createdServerAt = :createdServerAt,
            fetchedAt = :fetchedAt,
            snapshotHash = :snapshotHash,
            productCode = :productCode,
            deviceCode = :deviceCode,
            kind = :kind,
            updatedAtUtcMs = :nowUtcMs,
            revision = revision + 1
        WHERE id = :id AND deletedAtUtcMs IS NULL
        """,
    )
    fun updateRequest(
        id: String,
        serverStatus: String,
        ownerDecision: String?,
        ownerDecidedAt: Long?,
        createdServerAt: Long,
        fetchedAt: Long,
        snapshotHash: String,
        productCode: String,
        deviceCode: String,
        kind: String,
        nowUtcMs: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertEvent(row: HubEventEntity)

    @Query(
        """
        SELECT * FROM hub_events
        WHERE profileId = :profileId AND remoteEventId = :remoteId
        LIMIT 1
        """,
    )
    fun eventByRemote(profileId: String, remoteId: String): HubEventEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertMailbox(row: MailboxReceiptEntity)

    @Query(
        """
        SELECT * FROM mailbox_receipts
        WHERE profileId = :profileId AND batchId = :batchId
        LIMIT 1
        """,
    )
    fun mailboxByBatch(profileId: String, batchId: String): MailboxReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertCursor(row: SyncCursorEntity)

    @Query("SELECT * FROM sync_cursors WHERE profileId = :profileId AND stream = :stream")
    fun cursor(profileId: String, stream: String): SyncCursorEntity?

    @Query(
        """
        UPDATE sync_cursors
        SET cursor = :cursor,
            lastSuccess = :lastSuccess,
            lastAttempt = :lastAttempt,
            coverageStart = :coverageStart,
            coverageEnd = :coverageEnd,
            complete = :complete
        WHERE profileId = :profileId AND stream = :stream
        """,
    )
    fun updateCursor(
        profileId: String,
        stream: String,
        cursor: String,
        lastSuccess: Long?,
        lastAttempt: Long,
        coverageStart: Long?,
        coverageEnd: Long?,
        complete: Int,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertNotice(row: NotificationReceiptEntity): Long

    @Query(
        """
        UPDATE notification_receipts
        SET deliveredAt = :deliveredAt
        WHERE stableEventKey = :key AND deliveredAt IS NULL
        """,
    )
    fun markNoticeDelivered(key: String, deliveredAt: Long): Int

    @Query(
        """
        SELECT stableEventKey FROM notification_receipts
        WHERE profileId = :profileId
        """,
    )
    fun observedNotices(profileId: String): List<String>

    @Query("SELECT * FROM licence_requests WHERE hubProfileId = :profileId AND serverStatus = 'pending' AND deletedAtUtcMs IS NULL ORDER BY createdServerAt, remoteId")
    fun cachedPending(profileId: String): List<LicenceRequestEntity>

    @Query("SELECT COUNT(*) FROM outbox WHERE profileId = :profileId AND status IN ('CONFLICT', 'NEEDS_REVIEW', 'AUTH_BLOCKED')")
    fun blockedCount(profileId: String): Int

    @Query("SELECT COUNT(*) FROM outbox")
    fun outboxCount(): Int

    @Query("SELECT COUNT(*) FROM sync_cursors")
    fun cursorCount(): Int

    @Query("SELECT COUNT(*) FROM licence_requests")
    fun requestCount(): Int

    @Query("SELECT COUNT(*) FROM mailbox_receipts")
    fun mailboxCount(): Int

    @Query("SELECT COUNT(*) FROM notification_receipts")
    fun noticeCount(): Int
}
