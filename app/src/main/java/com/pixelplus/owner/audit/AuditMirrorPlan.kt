package com.pixelplus.owner.audit

/**
 * What to send to the hub's audit mirror, decided from the phone's chain and the hub's head alone. Pure.
 *
 * The hub's head is the truth about what it holds, so there is no phone-side cursor to lose or to get wrong. Entries go in order, a
 * limited batch at a time. A hub that holds something the phone does not (restore, another phone) or a different hash is a stop that is
 * shown to the owner, never repaired by sending more.
 */
sealed class MirrorStep {
    data object UpToDate : MirrorStep()
    data class Send(val links: List<ChainLink>) : MirrorStep()
    data class Stop(val state: AnchorState) : MirrorStep()
}

object AuditMirrorPlan {
    const val BATCH = 100

    /** [links] is the whole local chain in sequence order starting at 1 (the caller verified it). */
    fun next(links: List<ChainLink>, remote: ChainHead, batch: Int = BATCH): MirrorStep {
        val localHead = links.lastOrNull()?.let { ChainHead(links.size.toLong(), it.entryHash) } ?: ChainHead(0, AuditHash.GENESIS)
        val state = AuditAnchor.compare(localHead, remote) { n -> links.getOrNull((n - 1).toInt())?.entryHash }
        return when (state) {
            AnchorState.IN_SYNC -> MirrorStep.UpToDate
            AnchorState.HUB_BEHIND -> MirrorStep.Send(links.subList(remote.sequence.toInt(), minOf(links.size, remote.sequence.toInt() + batch.coerceAtLeast(1))))
            else -> MirrorStep.Stop(state)
        }
    }
}
