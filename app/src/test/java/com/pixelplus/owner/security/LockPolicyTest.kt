package com.pixelplus.owner.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPolicyTest {
    private val t0 = 1_000_000L

    @Test fun aColdStartIsLockedAndHidden() {
        val s = LockPolicy.coldStart()
        assertTrue(s.locked && s.contentHidden)
    }

    @Test fun unlockShowsContentAndLeavingHidesItAtOnce() {
        var s = LockPolicy.onUnlockSucceeded(LockPolicy.coldStart())
        assertFalse(s.locked || s.contentHidden)
        s = LockPolicy.onBackground(s, t0)
        assertTrue("hidden before any relock, so recents shows nothing", s.contentHidden)
        assertFalse(s.locked)
    }

    @Test fun theTimeoutDecidesWhetherItRelocks() {
        for ((timeout, edge) in listOf(LockTimeout.ONE_MINUTE to 60_000L, LockTimeout.FIVE_MINUTES to 300_000L, LockTimeout.FIFTEEN_MINUTES to 900_000L)) {
            val away = LockPolicy.onBackground(LockPolicy.onUnlockSucceeded(LockPolicy.coldStart()), t0)
            val early = LockPolicy.onForeground(away, t0 + edge - 1, timeout)
            assertFalse(timeout.name, early.locked); assertFalse(early.contentHidden)
            val late = LockPolicy.onForeground(away, t0 + edge, timeout)
            assertTrue(timeout.name, late.locked && late.contentHidden)
        }
    }

    @Test fun immediateAlwaysRelocks() {
        val away = LockPolicy.onBackground(LockPolicy.onUnlockSucceeded(LockPolicy.coldStart()), t0)
        assertTrue(LockPolicy.onForeground(away, t0, LockTimeout.IMMEDIATE).locked)
    }

    @Test fun aClockThatWentBackwardsLocks() {
        val away = LockPolicy.onBackground(LockPolicy.onUnlockSucceeded(LockPolicy.coldStart()), t0)
        assertTrue(LockPolicy.onForeground(away, t0 - 5, LockTimeout.FIFTEEN_MINUTES).locked)
    }

    @Test fun aLockedStateStaysLockedAndAFailedPromptChangesNothing() {
        val locked = LockPolicy.onBackground(LockPolicy.coldStart(), t0)
        assertEquals(locked, LockPolicy.onForeground(locked, t0 + 999_999_999, LockTimeout.FIFTEEN_MINUTES))
        val failed = LockPolicy.onUnlockFailed(locked)
        assertTrue(failed.locked && failed.contentHidden)
        assertTrue(LockPolicy.lockNow(LockPolicy.onUnlockSucceeded(locked)).locked)
        assertNull(LockPolicy.lockNow(LockPolicy.onUnlockSucceeded(locked)).leftAtElapsedMs)
    }

    @Test fun anUnknownSavedTimeoutIsTheStrictest() {
        assertEquals(LockTimeout.IMMEDIATE, LockTimeout.parse(null))
        assertEquals(LockTimeout.IMMEDIATE, LockTimeout.parse("NEVER"))
        assertEquals(LockTimeout.FIVE_MINUTES, LockTimeout.parse("FIVE_MINUTES"))
    }

    @Test fun noticesCarryOnlyCounts() {
        assertNull(GenericNotice.text(0, 0, true))
        assertNull(GenericNotice.text(-3, -1, false))
        assertEquals("1 new request", GenericNotice.text(1, 0, false))
        assertEquals("3 new requests · 2 new alerts", GenericNotice.text(3, 2, false))
        assertEquals("3 طلب جديد · 2 تنبيه جديد", GenericNotice.text(3, 2, true))
    }
}
