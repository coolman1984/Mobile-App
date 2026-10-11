package com.pixelplus.owner.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditMirrorPlanTest {
    private fun links(count: Int): List<ChainLink> {
        val out = mutableListOf<ChainLink>()
        var previous = AuditHash.GENESIS
        for (n in 1..count) {
            val canonical = "[$n]".toByteArray()
            val hash = AuditHash.of(previous, canonical)
            out += ChainLink(previous, hash, canonical)
            previous = hash
        }
        return out
    }

    @Test fun anEmptyHubGetsTheFirstBatchInOrder() {
        val l = links(250)
        val step = AuditMirrorPlan.next(l, ChainHead(0, AuditHash.GENESIS)) as MirrorStep.Send
        assertEquals(100, step.links.size)
        assertEquals(l.first(), step.links.first())
        assertEquals(l[99], step.links.last())
    }

    @Test fun aHubThatIsBehindGetsOnlyWhatItLacks() {
        val l = links(7)
        val step = AuditMirrorPlan.next(l, ChainHead(5, l[4].entryHash)) as MirrorStep.Send
        assertEquals(listOf(l[5], l[6]), step.links)
        assertEquals(MirrorStep.UpToDate, AuditMirrorPlan.next(l, ChainHead(7, l[6].entryHash)))
        assertEquals(MirrorStep.UpToDate, AuditMirrorPlan.next(emptyList(), ChainHead(0, AuditHash.GENESIS)))
    }

    @Test fun aHubThatHoldsMoreOrSomethingDifferentIsAStopNotARepair() {
        val l = links(4)
        assertEquals(MirrorStep.Stop(AnchorState.HUB_AHEAD), AuditMirrorPlan.next(l, ChainHead(9, "cd".repeat(32))))
        assertEquals(MirrorStep.Stop(AnchorState.MISMATCH), AuditMirrorPlan.next(l, ChainHead(4, "cd".repeat(32))))
        assertEquals(MirrorStep.Stop(AnchorState.MISMATCH), AuditMirrorPlan.next(l, ChainHead(2, "cd".repeat(32))))
        assertTrue(AuditMirrorPlan.next(emptyList(), ChainHead(3, "cd".repeat(32))) is MirrorStep.Stop)
    }

    @Test fun theBatchIsBoundedAndNeverPastTheEnd() {
        val l = links(3)
        assertEquals(1, (AuditMirrorPlan.next(l, ChainHead(0, AuditHash.GENESIS), batch = 1) as MirrorStep.Send).links.size)
        assertEquals(1, (AuditMirrorPlan.next(l, ChainHead(0, AuditHash.GENESIS), batch = 0) as MirrorStep.Send).links.size)
        assertEquals(3, (AuditMirrorPlan.next(l, ChainHead(0, AuditHash.GENESIS), batch = 1000) as MirrorStep.Send).links.size)
    }
}
