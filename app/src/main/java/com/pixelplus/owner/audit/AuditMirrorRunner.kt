package com.pixelplus.owner.audit

import android.content.Context
import com.pixelplus.owner.AndroidHubTransport
import com.pixelplus.owner.HubConfig
import com.pixelplus.owner.data.db.OwnerDatabase

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
        data class Blocked(val reasonAr: String, val reasonEn: String) : Result()
    }

    fun runOnce(context: Context, config: HubConfig): Result {
        if (!config.isLive) return Result.Blocked("لم يُضبط المركز.", "The hub is not set.")
        val client = AuditMirrorClient(AndroidHubTransport())
        val database = runCatching { OwnerDatabase.open(context.applicationContext) }.getOrNull()
            ?: return Result.Blocked("تعذر فتح سجل النشاط.", "Could not open the activity log.")
        try {
            val chain = database.auditDao().chain() ?: return Result.NothingToSend
            val sealer = database.auditRuntime.sealer
            val links = database.auditDao().entries(chain.chainId).map { row ->
                val body = runCatching { check(row.keyAliasVersion == sealer.keyAliasVersion); sealer.open(row.canonicalCiphertext) }.getOrDefault(ByteArray(0))
                ChainLink(row.previousHash, row.entryHash, body)
            }
            if (!AuditCanonical.verify(links).ok) return Result.Blocked("سجل النشاط المحلي غير سليم. لن يُرسل.", "The local activity chain does not verify. It will not be sent.")
            val remote = when (val r = client.recent(config, chain.chainId, null, 1)) {
                is MirrorResult.Ok -> r.value.head
                MirrorResult.NotAvailable -> return Result.NotAvailable
                MirrorResult.AuthBlocked -> return Result.Blocked("رفض المركز رمز المالك.", "The hub refused the owner token.")
                is MirrorResult.Retry -> return Result.Blocked("تعذر الاتصال بالمركز. حاول لاحقًا.", "Could not reach the hub. Try later.")
                else -> return Result.Blocked("رد المركز غير مفهوم.", "The hub's reply could not be trusted.")
            }
            return when (val step = AuditMirrorPlan.next(links, remote)) {
                MirrorStep.UpToDate -> Result.UpToDate
                is MirrorStep.Stop -> Result.Blocked(
                    if (step.state == AnchorState.HUB_AHEAD) "المركز يحمل أكثر مما على هذا الهاتف. لن يُرسل شيء." else "سجل المركز يختلف عن سجل الهاتف. لن يُرسل شيء.",
                    if (step.state == AnchorState.HUB_AHEAD) "The hub holds more than this phone. Nothing was sent." else "The hub's chain differs from the phone's. Nothing was sent.",
                )
                is MirrorStep.Send -> {
                    var sent = 0
                    for (link in step.links) {
                        when (val r = client.append(config, link)) {
                            is MirrorResult.Ok -> sent++
                            MirrorResult.NotAvailable -> return Result.NotAvailable
                            is MirrorResult.Retry -> return Result.Blocked("انقطع الاتصال بعد إرسال $sent.", "The connection dropped after $sent.")
                            else -> return Result.Blocked("رفض المركز السطر رقم ${sent + 1}. توقف الإرسال.", "The hub refused entry ${sent + 1}. Sending stopped.")
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
