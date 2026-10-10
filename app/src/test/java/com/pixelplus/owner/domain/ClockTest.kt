package com.pixelplus.owner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.zone.ZoneOffsetTransition
import java.util.Locale

class ClockTest {
    @Test fun fixedClockDoesNotMove() {
        val clock = FixedClock(1_735_689_600_000L)
        assertEquals(clock.nowUtcMs(), clock.nowUtcMs())
        val stamp = CairoClock.stamp(clock)
        assertEquals(clock.nowUtcMs(), stamp.occurredAtUtcMs)
        assertEquals(stamp, CairoClock.stamp(clock.nowUtcMs()))
    }

    @Test fun systemClockIsNearTheJvmClock() {
        val delta = kotlin.math.abs(SystemUtcClock.nowUtcMs() - System.currentTimeMillis())
        assertTrue(delta < 10_000)
    }

    @Test fun winterIsTwoHoursAhead() {
        val stamp = CairoClock.stamp(utc("2026-01-15T10:00:00Z"))
        assertEquals("2026-01-15T12:00:00+02:00", stamp.cairoLocalIso)
        assertEquals(7_200, stamp.cairoOffsetSeconds)
        agrees(stamp)
    }

    @Test fun july2026IsDaylightAndJuly2022IsNot() {
        val summer = CairoClock.stamp(utc("2026-07-15T10:00:00Z"))
        assertEquals("2026-07-15T13:00:00+03:00", summer.cairoLocalIso)
        assertEquals(10_800, summer.cairoOffsetSeconds)
        agrees(summer)

        val oldSummer = CairoClock.stamp(utc("2022-07-15T10:00:00Z"))
        assertEquals("2022-07-15T12:00:00+02:00", oldSummer.cairoLocalIso)
        assertEquals(7_200, oldSummer.cairoOffsetSeconds)
        agrees(oldSummer)
    }

    @Test fun springForwardSkipsAnHourAndRejectsIt() {
        val at = utc("2026-04-23T22:00:00Z")
        assertEquals("2026-04-23T23:59:59.999+02:00", CairoClock.stamp(at - 1).cairoLocalIso)
        assertEquals("2026-04-24T01:00:00+03:00", CairoClock.stamp(at).cairoLocalIso)
        agrees(CairoClock.stamp(at - 1))
        agrees(CairoClock.stamp(at))

        val missing = LocalDateTime.of(2026, 4, 24, 0, 30)
        assertTrue(CairoClock.classify(missing) is CairoLocalTime.Gap)
        assertNull(CairoClock.resolveUtcMs(missing))
        assertNull(CairoClock.resolveUtcMs(LocalDateTime.of(2026, 4, 24, 0, 0)))
        assertEquals(at, CairoClock.resolveUtcMs(LocalDateTime.of(2026, 4, 24, 1, 0)))

        val shifted = CairoClock.resolveUtcMs(missing, gap = GapPolicy.SHIFT_FORWARD)
        assertEquals(utc("2026-04-23T22:30:00Z"), shifted)
        assertEquals(
            CairoClock.resolveUtcMs(LocalDateTime.of(2026, 4, 24, 1, 30)),
            shifted,
        )
    }

    @Test fun fallBackRepeatsAnHourAndKeepsBothInstants() {
        val local = LocalDateTime.of(2026, 10, 29, 23, 30)
        val found = CairoClock.classify(local)
        assertTrue(found is CairoLocalTime.Overlap)
        val earlier = CairoClock.resolveUtcMs(local, overlap = OverlapPolicy.EARLIER)
        val later = CairoClock.resolveUtcMs(local, overlap = OverlapPolicy.LATER)
        assertEquals(utc("2026-10-29T20:30:00Z"), earlier)
        assertEquals(utc("2026-10-29T21:30:00Z"), later)
        assertEquals(earlier, CairoClock.resolveUtcMs(local))

        val first = CairoClock.stamp(earlier!!)
        val second = CairoClock.stamp(later!!)
        assertEquals("2026-10-29T23:30:00+03:00", first.cairoLocalIso)
        assertEquals("2026-10-29T23:30:00+02:00", second.cairoLocalIso)
        assertEquals(first.cairoLocalIso.substring(0, 19), second.cairoLocalIso.substring(0, 19))
        assertTrue(first.cairoOffsetSeconds > second.cairoOffsetSeconds)
        agrees(first)
        agrees(second)

        val change = utc("2026-10-29T21:00:00Z")
        assertEquals("2026-10-29T23:59:59.999+03:00", CairoClock.stamp(change - 1).cairoLocalIso)
        assertEquals("2026-10-29T23:00:00+02:00", CairoClock.stamp(change).cairoLocalIso)
        assertEquals(
            utc("2026-10-29T22:00:00Z"),
            CairoClock.resolveUtcMs(LocalDateTime.of(2026, 10, 30, 0, 0)),
        )
    }

    @Test fun pinnedTransitionsMatchTheIanaRules() {
        assertTransition(
            "2023-04-27T22:00:00Z",
            gap = true,
            beforeLocal = "2023-04-28T00:00",
            afterLocal = "2023-04-28T01:00",
        )
        assertTransition(
            "2023-10-26T21:00:00Z",
            gap = false,
            beforeLocal = "2023-10-27T00:00",
            afterLocal = "2023-10-26T23:00",
        )
        assertTransition(
            "2024-04-25T22:00:00Z",
            gap = true,
            beforeLocal = "2024-04-26T00:00",
            afterLocal = "2024-04-26T01:00",
        )
        assertTransition(
            "2024-10-31T21:00:00Z",
            gap = false,
            beforeLocal = "2024-11-01T00:00",
            afterLocal = "2024-10-31T23:00",
        )
        assertTransition(
            "2026-04-23T22:00:00Z",
            gap = true,
            beforeLocal = "2026-04-24T00:00",
            afterLocal = "2026-04-24T01:00",
        )
        assertTransition(
            "2026-10-29T21:00:00Z",
            gap = false,
            beforeLocal = "2026-10-30T00:00",
            afterLocal = "2026-10-29T23:00",
        )
    }

    @Test fun everyTransitionFrom2023Through2027AgreesWithTheClock() {
        val rules = ZoneId.of(CairoClock.ZONE_ID).rules
        var cursor = Instant.parse("2023-01-01T00:00:00Z")
        val end = Instant.parse("2028-01-01T00:00:00Z")
        var gaps = 0
        var overlaps = 0
        while (true) {
            val transition = rules.nextTransition(cursor) ?: break
            if (!transition.instant.isBefore(end)) break
            val at = transition.instant.toEpochMilli()
            assertEquals(transition.offsetAfter.totalSeconds, CairoClock.stamp(at).cairoOffsetSeconds)
            assertEquals(transition.offsetBefore.totalSeconds, CairoClock.stamp(at - 1).cairoOffsetSeconds)
            if (transition.isGap) {
                gaps += 1
                assertTrue(CairoClock.classify(transition.dateTimeBefore) is CairoLocalTime.Gap)
                assertNull(CairoClock.resolveUtcMs(transition.dateTimeBefore.plusSeconds(1)))
                assertEquals(at, CairoClock.resolveUtcMs(transition.dateTimeAfter))
            } else {
                overlaps += 1
                val inside = transition.dateTimeAfter.plusSeconds(1)
                val earlier = CairoClock.resolveUtcMs(inside, overlap = OverlapPolicy.EARLIER)
                val later = CairoClock.resolveUtcMs(inside, overlap = OverlapPolicy.LATER)
                assertNotNull(earlier)
                assertNotNull(later)
                assertTrue(earlier!! < later!!)
                assertEquals(transition.offsetBefore.totalSeconds, CairoClock.stamp(earlier).cairoOffsetSeconds)
                assertEquals(transition.offsetAfter.totalSeconds, CairoClock.stamp(later).cairoOffsetSeconds)
                assertTrue(CairoClock.classify(transition.dateTimeBefore) is CairoLocalTime.Unique)
            }
            cursor = transition.instant
        }
        assertEquals(5, gaps)
        assertEquals(5, overlaps)
    }

    @Test fun cairoMonthStartsAtLocalMidnightAcrossTheDaylightYears() {
        assertEquals(utc("2025-12-31T22:00:00Z"), CairoClock.monthStartUtcMs(2026, 1))
        assertEquals(utc("2026-04-30T21:00:00Z"), CairoClock.monthStartUtcMs(2026, 5))
        assertEquals(utc("2026-07-31T21:00:00Z"), CairoClock.monthStartUtcMs(2026, 8))
        assertEquals(utc("2026-10-31T22:00:00Z"), CairoClock.monthStartUtcMs(2026, 11))
        assertEquals(utc("2024-10-31T22:00:00Z"), CairoClock.monthStartUtcMs(2024, 11))
        assertEquals(utc("2022-07-31T22:00:00Z"), CairoClock.monthStartUtcMs(2022, 8))

        for (year in 2022..2027) {
            for (month in 1..12) {
                val local = LocalDateTime.of(year, month, 1, 0, 0)
                assertTrue(
                    "$year-$month midnight is ambiguous or missing",
                    CairoClock.classify(local) is CairoLocalTime.Unique,
                )
                val start = CairoClock.monthStartUtcMs(year, month)
                val stamp = CairoClock.stamp(start)
                val monthText = String.format(Locale.ROOT, "%02d", month)
                assertTrue(stamp.cairoLocalIso.startsWith("$year-$monthText-01T00:00:00"))
                val previous = CairoClock.stamp(start - 1).cairoLocalIso
                assertFalse(previous.startsWith("$year-$monthText-01T"))
                agrees(stamp)
            }
        }
    }

    @Test fun leapDayExistsAndTheMissingDayIsRejectedByTheCalendar() {
        val leap = CairoClock.resolveUtcMs(LocalDateTime.of(2024, 2, 29, 0, 0))
        assertEquals(utc("2024-02-28T22:00:00Z"), leap)
        assertEquals("2024-02-29T00:00:00+02:00", CairoClock.stamp(leap!!).cairoLocalIso)
        var rejected = false
        try {
            LocalDateTime.of(2023, 2, 29, 0, 0)
        } catch (e: DateTimeException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test fun badMonthIsRejected() {
        for (month in listOf(0, 13)) {
            var rejected = false
            try {
                CairoClock.monthStartUtcMs(2026, month)
            } catch (e: DateTimeException) {
                rejected = true
            }
            assertTrue(rejected)
        }
    }

    @Test fun arabicLocaleStillPrintsAsciiDigits() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale("ar", "EG"))
        try {
            val stamp = CairoClock.stamp(utc("2026-01-15T10:00:00.123Z"))
            assertEquals("2026-01-15T12:00:00.123+02:00", stamp.cairoLocalIso)
            assertFalse(stamp.cairoLocalIso.any { it in '٠'..'٩' })
            assertEquals(CairoClock.ZONE_ID, stamp.zoneId)
            agrees(stamp)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun clockSourceStaysFreeOfAndroid() {
        val source = listOf(
            File("src/main/java/com/pixelplus/owner/domain/Clock.kt"),
            File("app/src/main/java/com/pixelplus/owner/domain/Clock.kt"),
        ).firstOrNull { it.isFile } ?: error("Clock.kt not found from ${File(".").absolutePath}")
        val text = source.readText()
        assertFalse(text.contains("import android."))
        assertFalse(text.contains("import androidx."))
        assertTrue(text.contains("Africa/Cairo"))
    }

    private fun utc(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun agrees(stamp: CairoStamp) {
        val parsed = OffsetDateTime.parse(stamp.cairoLocalIso)
        assertEquals(stamp.cairoOffsetSeconds, parsed.offset.totalSeconds)
        assertEquals(stamp.occurredAtUtcMs, parsed.toInstant().toEpochMilli())
        assertEquals(CairoClock.ZONE_ID, stamp.zoneId)
        assertEquals(CairoClock.zone.id, stamp.zoneId)
        assertFalse(stamp.cairoLocalIso.contains("["))
        assertFalse(stamp.cairoLocalIso.any { it in '٠'..'٩' })
    }

    private fun assertTransition(instant: String, gap: Boolean, beforeLocal: String, afterLocal: String) {
        val rules = ZoneId.of(CairoClock.ZONE_ID).rules
        val at = Instant.parse(instant)
        val transition = rules.nextTransition(at.minusSeconds(1))
        assertNotNull(transition)
        val found: ZoneOffsetTransition = transition!!
        assertEquals(at, found.instant)
        assertEquals(gap, found.isGap)
        assertEquals(!gap, found.isOverlap)
        assertEquals(LocalDateTime.parse(beforeLocal), found.dateTimeBefore)
        assertEquals(LocalDateTime.parse(afterLocal), found.dateTimeAfter)
        assertEquals(found.offsetAfter.totalSeconds, CairoClock.stamp(at.toEpochMilli()).cairoOffsetSeconds)
        assertEquals(found.offsetBefore.totalSeconds, CairoClock.stamp(at.toEpochMilli() - 1).cairoOffsetSeconds)
    }
}
