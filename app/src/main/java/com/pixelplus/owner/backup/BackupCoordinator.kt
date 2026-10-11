package com.pixelplus.owner.backup

/**
 * Takes a snapshot, seals it and hands the sealed bytes to a destination. No Android types: the phone supplies the three small
 * interfaces. Room, SAF and WorkManager implementations are **NOT IMPLEMENTED** in this milestone.
 *
 * Rules the tests hold it to: writers are quiesced and the database is reopened in `finally`, even when sealing or writing fails;
 * the destination only ever receives sealed bytes; a failed write never replaces the last good artifact; no retry loop here.
 */
interface SnapshotSource {
    /**
     * Pause domain writers and the hub sync, checkpoint the WAL, close the database, run [block] with a consistent copy,
     * and reopen in a `finally`. [block] must not start new writes.
     */
    fun <T> quiesced(block: (Snapshot) -> T): T
}

class Snapshot(
    val dbSchemaVersion: Int,
    val appVersion: String,
    val databaseCopy: ByteArray,
    /** The logical JSON export, taken in the same quiesced window so both carry the same snapshot id. */
    val logicalJson: ByteArray,
    val auditHeads: Map<String, AuditHeadEntry>,
    val hubProfileIds: Set<String>,
)

interface BackupSink {
    /** Writes to a temporary name. The final name is untouched until [commit]. Returns false on any failure (disk full, provider offline). */
    fun writeTemp(name: String, sealed: ByteArray): Boolean

    /** Atomically makes the temporary file the artifact. A false return leaves the previous artifact in place. */
    fun commit(name: String): Boolean

    /** Removes a temporary file. Called after any failure so no half-written file stays behind. */
    fun discardTemp(name: String)
}

sealed class BackupOutcome {
    data class Done(val snapshotId: String, val sizeBytes: Int) : BackupOutcome()
    data class Failed(val stage: Stage, val fault: BackupFault? = null) : BackupOutcome()
}

enum class Stage { SNAPSHOT, SEAL, WRITE, COMMIT }

class BackupCoordinator(
    private val source: SnapshotSource,
    private val sealer: BackupSealer,
    private val sink: BackupSink,
    private val now: () -> Long,
    private val newId: () -> String,
) {
    fun run(name: String = DEFAULT_NAME): BackupOutcome {
        val id = newId()
        val sealed = try {
            source.quiesced { snap ->
                BackupArchive.build(
                    snapshotId = id, createdAtUtcMs = now(), dbSchemaVersion = snap.dbSchemaVersion, appVersion = snap.appVersion,
                    auditHeads = snap.auditHeads, hubProfileIds = snap.hubProfileIds,
                    files = mapOf(BackupLimits.DATABASE to snap.databaseCopy, BackupLimits.LOGICAL to snap.logicalJson),
                    sealer = sealer,
                )
            }
        } catch (e: Exception) {
            return BackupOutcome.Failed(Stage.SNAPSHOT)
        }
        val bytes = when (sealed) {
            is ArchiveResult.Refused -> return BackupOutcome.Failed(Stage.SEAL, sealed.reason)
            is ArchiveResult.Built -> sealed.bytes
        }
        val temp = "$name.tmp"
        if (!sink.writeTemp(temp, bytes)) {
            sink.discardTemp(temp)
            return BackupOutcome.Failed(Stage.WRITE)
        }
        if (!sink.commit(temp)) {
            sink.discardTemp(temp)
            return BackupOutcome.Failed(Stage.COMMIT)
        }
        return BackupOutcome.Done(id, bytes.size)
    }

    companion object {
        const val DEFAULT_NAME = "pixel-plus-owner.ppob"
    }
}
