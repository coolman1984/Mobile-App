package com.pixelplus.owner.backup

import com.pixelplus.owner.sync.OutboxMachine
import com.pixelplus.owner.sync.OutboxState
import com.pixelplus.owner.sync.OutboxStatus

/**
 * What a restore is allowed to do, decided before anything on the phone changes. No Android types.
 *
 * The caller (a later milestone) runs: pick a file, reauthenticate, [RestorePlanner.check], show [RestorePlan], take a backup of the
 * current state, replace atomically, reopen. Nothing here touches the live database. A failure at any step leaves it as it was.
 */
data class RestorePlan(
    val manifest: BackupManifest,
    val files: Map<String, ByteArray>,
    /** Always shown to the owner before they confirm. */
    val notices: List<RestoreNotice>,
)

enum class RestoreNotice {
    /** Tokens are never in an archive. The hub address and owner token must be entered again. */
    TOKENS_MUST_BE_ENTERED_AGAIN,

    /** Every queued decision comes back as "needs review". Nothing is sent until the owner confirms it again. */
    QUEUE_NEEDS_REVIEW,

    /** The restored audit chain is kept as it was. A new chain starts after it and refers to its verified head. */
    NEW_AUDIT_CHAIN_STARTS,

    /** Signing stays OFF. A restore never turns it on. */
    SIGNING_STAYS_OFF,

    /** The archive is from an older database version; the normal forward migrations run on the staged copy first. */
    OLDER_SCHEMA_WILL_MIGRATE,
}

sealed class RestoreCheck {
    data class Ready(val plan: RestorePlan) : RestoreCheck()
    data class Refused(val reason: BackupFault) : RestoreCheck()
}

object RestorePlanner {
    fun check(archive: ByteArray, sealer: BackupSealer, currentDbSchemaVersion: Int): RestoreCheck {
        // a newer database is refused before the key is even tried: the message is about the file, not the key
        val (peeked, fault) = BackupArchive.peek(archive)
        if (peeked == null) return RestoreCheck.Refused(fault ?: BackupFault.NOT_AN_ARCHIVE)
        if (peeked.dbSchemaVersion > currentDbSchemaVersion) return RestoreCheck.Refused(BackupFault.NEWER_SCHEMA)
        if (peeked.dbSchemaVersion < 1 || peeked.keyMode != "device") return RestoreCheck.Refused(BackupFault.BAD_MANIFEST)
        return when (val opened = BackupArchive.open(archive, sealer)) {
            is OpenResult.Refused -> RestoreCheck.Refused(opened.reason)
            is OpenResult.Opened -> {
                if (BackupLimits.DATABASE !in opened.files || BackupLimits.LOGICAL !in opened.files) {
                    return RestoreCheck.Refused(BackupFault.MISSING_FILE)
                }
                val notices = buildList {
                    add(RestoreNotice.TOKENS_MUST_BE_ENTERED_AGAIN)
                    add(RestoreNotice.QUEUE_NEEDS_REVIEW)
                    add(RestoreNotice.NEW_AUDIT_CHAIN_STARTS)
                    add(RestoreNotice.SIGNING_STAYS_OFF)
                    if (opened.manifest.dbSchemaVersion < currentDbSchemaVersion) add(RestoreNotice.OLDER_SCHEMA_WILL_MIGRATE)
                }
                RestoreCheck.Ready(RestorePlan(opened.manifest, opened.files, notices))
            }
        }
    }

    const val REASON_RESTORED = "restored"

    /**
     * Every decision that was still waiting or in flight in the backup comes back as NEEDS_REVIEW, never as QUEUED. A finished state is
     * left exactly as it was. The hub is asked again, and the owner confirms again, before anything is sent.
     */
    fun quarantine(queue: List<OutboxState>): List<OutboxState> = queue.map { state ->
        if (state.status.name !in com.pixelplus.owner.sync.OutboxRules.ACTIVE) {
            state
        } else {
            (OutboxMachine.review(state, REASON_RESTORED) as? com.pixelplus.owner.sync.MachineMove.Ok)?.state
                ?: state.copy(status = OutboxStatus.NEEDS_REVIEW, lastErrorCode = REASON_RESTORED, leaseUntil = null)
        }
    }
}
