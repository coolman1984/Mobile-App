package com.pixelplus.owner

import org.junit.Assert.assertEquals
import org.junit.Test

class FollowUpRemindersTest {
    private val now = 1_800_000_000_000L

    @Test fun aReminderIsDueFromItsOffsetBeforeTheDueTime() {
        val rows = listOf(
            Triple("a", now + 30 * 60_000L, 30),       // exactly 30 minutes before: due now
            Triple("b", now + 31 * 60_000L, 30),       // one minute early
            Triple("c", now - 5 * 60_000L, 0),         // already overdue
            Triple("d", now + 1_000_000L, 0),          // far away
        )
        assertEquals(listOf("a", "c"), FollowUpReminders.due(rows, now, emptySet()))
    }

    @Test fun aFollowUpRemindsOnlyOnce() {
        val rows = listOf(Triple("a", now - 1, 0), Triple("b", now - 1, 0))
        assertEquals(listOf("b"), FollowUpReminders.due(rows, now, setOf("a")))
        assertEquals(emptyList<String>(), FollowUpReminders.due(rows, now, setOf("a", "b")))
        assertEquals(emptyList<String>(), FollowUpReminders.due(emptyList(), now, emptySet()))
    }
}
