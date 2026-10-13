package com.pixelplus.owner.audit

import android.content.Context
import com.pixelplus.owner.AndroidHubTransport
import com.pixelplus.owner.HubConfig
import com.pixelplus.owner.data.db.OwnerDatabase
import com.pixelplus.owner.ui.TextKey

/**
 * Sends the local activity chain to the hub's audit mirror, only when the owner presses the button. It sends what the Worker's own
 * head says it lacks, in order, at most one batch per press. Nothing in the chain is private (names, phones, amounts and notes never
 * enter it). An older Worker answers "not available" and nothing is sent. Run off the main thread.
 */
object AuditMirrorRunner {
    sealed class Result {
        data class Sent(val count: Int, val upToDate: Boolean) : Result()
        data object UpToDate : Result()
        data object NotAvailable : Result()
        data object NothingToSend : Result()
        data class Blocked(val reason: TextKey) : Result()
    }

    fun runOnce(context: Context, config: HubConfig): Result {
        if (!config.isLive) return Result.Blocked(TextKey.of("mirror_hub_not_configured"))
        val client = AuditMirrorClient(AndroidHubTransport())
        val database = runCatching { OwnerDatabase.open(context.applicationContext) }.getOrNull()
            ?: return Result.Blocked(TextKey.of("mirror_open_failed"))
        try {
            val chain = database.auditDao().chain() ?: return Result.NothingToSend
            val sealer = database.auditRuntime.sealer
            val links = database.auditDao().entries(chain.chainId).map { row ->
                val body = runCatching { check(row.keyAliasVersion == sealer.keyAliasVersion); sealer.open(row.canonicalCiphertext) }.getOrDefault(ByteArray(0))
                ChainLink(row.previousHash, row.entryHash, body)
            }
            if (!AuditCanonical.verify(links).ok) return Result.Blocked(TextKey.of("mirror_chain_invalid"))
            val remote = when (val r = client.recent(config, chain.chainId, null, 1)) {
                is MirrorResult.Ok -> r.value.head
                MirrorResult.NotAvailable -> return Result.NotAvailable
                MirrorResult.AuthBlocked -> return Result.Blocked(TextKey.of("hub_auth_blocked"))
                is MirrorResult.Retry -> return Result.Blocked(TextKey.of("hub_retry"))
                else -> return Result.Blocked(TextKey.of("mirror_bad_reply"))
            }
            return when (val step = AuditMirrorPlan.next(links, remote)) {
                MirrorStep.UpToDate -> Result.UpToDate
                is MirrorStep.Stop -> Result.Blocked(
                    TextKey.of(
                        if (step.state == AnchorState.HUB_AHEAD) "mirror_hub_ahead" else "mirror_chain_differs",
                    ),
                )
                is MirrorStep.Send -> {
                    var sent = 0
                    for (link in step.links) {
                        when (val r = client.append(config, link)) {
                            is MirrorResult.Ok -> sent++
                            MirrorResult.NotAvailable -> return Result.NotAvailable
                            is MirrorResult.Retry -> return Result.Blocked(TextKey.of("mirror_dropped_after", sent))
                            else -> return Result.Blocked(TextKey.of("mirror_entry_refused", sent + 1))
                        }
                    }
                    Result.Sent(sent, upToDate = step.links.size < AuditMirrorPlan.BATCH && remote.sequence + sent >= links.size)
                }
            }
        } finally {
            database.close()
        }
    }
}
