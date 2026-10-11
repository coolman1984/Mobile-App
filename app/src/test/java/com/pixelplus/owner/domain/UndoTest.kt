package com.pixelplus.owner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoTest {
    @Test fun snackbarStopsAtTenSecondsAndActivityCanContinue() {
        val open = request(now = START + Undo.WINDOW_MS - 1, channel = UndoChannel.SNACKBAR)
        assertTrue(Undo.decide(open) is UndoDecision.Apply)
        val closed = request(now = START + Undo.WINDOW_MS, channel = UndoChannel.SNACKBAR)
        val expired = Undo.decide(closed) as UndoDecision.Refused
        assertEquals("expired", expired.reason)
        assertTrue(expired.arabic.contains("مهلة"))
        assertTrue(expired.english.contains("window"))
        val later = request(now = START + 86_400_000, channel = UndoChannel.ACTIVITY)
        assertTrue(Undo.decide(later) is UndoDecision.Apply)
    }

    @Test fun revisionGuardAndDependentChangeRefuseUndo() {
        val moved = request(current = 4)
        val refused = Undo.decide(moved) as UndoDecision.Refused
        assertEquals("revision", refused.reason)
        assertTrue(refused.arabic.contains("تغيّر"))
        assertTrue(refused.english.contains("changed"))
        val dependent = request(channel = UndoChannel.ACTIVITY, now = START + 60_000, dependent = true)
        val blocked = Undo.decide(dependent) as UndoDecision.Refused
        assertEquals("dependent", blocked.reason)
        assertTrue(Undo.decide(request(used = true)) is UndoDecision.AlreadyUsed)
        assertTrue(Undo.decide(request(invalidated = true)) is UndoDecision.Invalidated)
        assertTrue(Undo.decide(request(current = null)) is UndoDecision.MissingRecord)
    }

    @Test fun sentDecisionCannotBeUndone() {
        val decision = Undo.decide(request(undoClass = Undo.CLASS_SENT)) as UndoDecision.Refused
        assertEquals(Undo.SENT_AR, decision.arabic)
        assertEquals(Undo.SENT_EN, decision.english)
        assertEquals("sent", decision.reason)
        val queued = Undo.cancelUnsent(DispatchPoint.QUEUED)
        assertTrue(queued is CancelDecision.Cancelled)
        val leased = Undo.cancelUnsent(DispatchPoint.LEASED) as CancelDecision.Refused
        assertTrue(leased.english.contains("started"))
        val sent = Undo.cancelUnsent(DispatchPoint.SENT) as CancelDecision.Refused
        assertEquals(Undo.SENT_EN, sent.english)
        val unknown = Undo.cancelUnsent(DispatchPoint.UNKNOWN) as CancelDecision.Unknown
        assertTrue(unknown.arabic.contains("غير معروفة"))
        assertTrue(unknown.english.contains("unknown"))
    }

    @Test fun reversalKeepsTheOriginalAndHidingACustomerDoesNotChangeTheTotal() {
        val original = CashPosting("pay-1", "customer-1", 2_500, "EGP")
        val posted = Undo.postReversal(original, "pay-2", "group-1", listOf(original)) as ReversalResult.Posted
        assertEquals(2_500, posted.original.amountMinor)
        assertEquals(-2_500, posted.reversal.amountMinor)
        assertEquals("pay-1", posted.reversal.reversalOfId)
        assertEquals("EGP", posted.reversal.currency)
        val rows = listOf(posted.original, posted.reversal)
        val hidden = LedgerView(rows, hiddenCustomerIds = setOf("customer-1"))
        assertEquals(0, Undo.totalMinor(hidden, "EGP"))
        assertEquals(2, hidden.postings.size)
        assertEquals(2_500, Undo.totalMinor(LedgerView(listOf(original)), "EGP"))
        assertEquals(0, Undo.totalMinor(hidden, "USD"))
        val again = Undo.postReversal(original, "pay-3", "group-1", rows) as ReversalResult.Rejected
        assertEquals("already_reversed", again.reason)
        val bad = Undo.postReversal(original.copy(amountMinor = 0), "pay-4", "group-2", emptyList()) as ReversalResult.Rejected
        assertEquals("amount", bad.reason)
        val other = CashPosting("pay-9", "customer-1", 700, "USD")
        val mixed = LedgerView(rows + other, hiddenCustomerIds = setOf("customer-1"))
        assertEquals(0, Undo.totalMinor(mixed, "EGP"))
        assertEquals(700, Undo.totalMinor(mixed, "USD"))
    }

    private fun request(
        now: Long = START,
        channel: UndoChannel = UndoChannel.SNACKBAR,
        current: Long? = 2,
        dependent: Boolean = false,
        used: Boolean = false,
        invalidated: Boolean = false,
        undoClass: String = Undo.CLASS_LOCAL,
    ) = UndoRequest(
        undoClass = undoClass,
        expectedRevision = 2,
        currentRevision = current,
        expiresAtUtcMs = START + Undo.WINDOW_MS,
        nowUtcMs = now,
        channel = channel,
        dependentChange = dependent,
        used = used,
        invalidated = invalidated,
    )

    private companion object {
        const val START = 1_700_000_000_000L
    }
}
