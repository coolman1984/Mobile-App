package com.pixelplus.owner.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Append and read for the audit chain. Undo can be marked used.
 * There is no update and no delete for an audit row.
 */
@Dao
interface AuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertChain(row: AuditChainEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertEntry(row: AuditEntryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertUndo(row: UndoRecordEntity)

    @Query("SELECT * FROM audit_chain WHERE singleton = 1")
    fun chain(): AuditChainEntity?

    @Query(
        """
        SELECT * FROM audit_entries
        WHERE chainId = :chainId
        ORDER BY sequence DESC
        LIMIT 1
        """,
    )
    fun head(chainId: String): AuditEntryEntity?

    @Query(
        """
        SELECT * FROM audit_entries
        WHERE chainId = :chainId
        ORDER BY sequence ASC
        """,
    )
    fun entries(chainId: String): List<AuditEntryEntity>

    @Query("SELECT count(*) FROM audit_entries")
    fun entryCount(): Int

    @Query("SELECT * FROM audit_entries WHERE eventId = :eventId")
    fun entry(eventId: String): AuditEntryEntity?

    @Query("SELECT * FROM undo_records WHERE operationId = :operationId")
    fun undo(operationId: String): UndoRecordEntity?

    @Query(
        """
        SELECT * FROM undo_records
        ORDER BY createdAtUtcMs DESC, operationId DESC
        LIMIT 1
        """,
    )
    fun latestUndo(): UndoRecordEntity?

    @Query(
        """
        UPDATE undo_records
        SET usedAtUtcMs = :nowUtcMs
        WHERE operationId = :operationId
        AND usedAtUtcMs IS NULL
        AND invalidatedAtUtcMs IS NULL
        """,
    )
    fun markUndoUsed(operationId: String, nowUtcMs: Long): Int

    @Query(
        """
        UPDATE undo_records
        SET invalidatedAtUtcMs = :nowUtcMs
        WHERE operationId = :operationId
        AND usedAtUtcMs IS NULL
        AND invalidatedAtUtcMs IS NULL
        """,
    )
    fun markUndoInvalid(operationId: String, nowUtcMs: Long): Int

    @Query(
        """
        SELECT count(*) FROM audit_entries
        WHERE entityId = :entityId
        AND eventId != :eventId
        AND sequence > (SELECT sequence FROM audit_entries WHERE eventId = :eventId)
        """,
    )
    fun laterAudits(entityId: String, eventId: String): Int

    @Query(
        """
        SELECT count(*) FROM branches
        WHERE customerId = :customerId AND updatedAtUtcMs > :afterUtcMs
        """,
    )
    fun branchesTouchedAfter(customerId: String, afterUtcMs: Long): Int

    @Query(
        """
        SELECT count(*) FROM customer_links
        WHERE customerId = :customerId AND updatedAtUtcMs > :afterUtcMs
        """,
    )
    fun linksTouchedAfter(customerId: String, afterUtcMs: Long): Int

    @Query(
        """
        SELECT count(*) FROM customer_links
        WHERE hubProfileId = :hubProfileId AND updatedAtUtcMs > :afterUtcMs
        """,
    )
    fun hubLinksTouchedAfter(hubProfileId: String, afterUtcMs: Long): Int
}
