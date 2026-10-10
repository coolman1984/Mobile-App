package com.pixelplus.owner.domain

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Device clock and Africa/Cairo civil time.
 *
 * Later audit, cash months and reminders must use this instead of a fixed
 * offset. Egypt's daylight-saving rules move; the JDK IANA database is the
 * source of those rules. A spring-forward gap has no local time. A fall-back
 * overlap has two. The device clock is not server time.
 *
 * Java 8 `java.time` only, so API 26 can run this without desugaring.
 * No Android types.
 */
fun interface Clock {
    fun nowUtcMs(): Long
}

/** Wall clock of the phone, as UTC milliseconds. Not a server timestamp. */
object SystemUtcClock : Clock {
    override fun nowUtcMs(): Long = System.currentTimeMillis()
}

/** Test and reducer clock. It does not move unless the caller builds another one. */
data class FixedClock(private val utcMs: Long) : Clock {
    override fun nowUtcMs(): Long = utcMs
}

/** What to do with a local time that Cairo skipped. */
enum class GapPolicy {
    /** The time does not exist. Callers must say so, not invent an instant. */
    REJECT,
    /** Move forward by the gap. Used only when a caller asks for it. */
    SHIFT_FORWARD,
}

/** Which of the two real instants to keep when Cairo repeats an hour. */
enum class OverlapPolicy {
    /** First occurrence. This matches `java.time` during a fall back. */
    EARLIER,
    /** Second occurrence, after the clock has changed. */
    LATER,
}

data class CairoStamp(
    val occurredAtUtcMs: Long,
    val cairoLocalIso: String,
    val cairoOffsetSeconds: Int,
    val zoneId: String,
)

/** Classification of one Cairo local date-time. */
sealed class CairoLocalTime {
    data class Unique(val utcMs: Long) : CairoLocalTime()

    data class Gap(
        val local: LocalDateTime,
        val shiftedForwardUtcMs: Long,
    ) : CairoLocalTime()

    data class Overlap(
        val earlierUtcMs: Long,
        val laterUtcMs: Long,
    ) : CairoLocalTime()
}

object CairoClock {
    const val ZONE_ID: String = "Africa/Cairo"

    val zone: ZoneId = ZoneId.of(ZONE_ID)

    private val iso: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun stamp(utcMs: Long): CairoStamp {
        val zoned = Instant.ofEpochMilli(utcMs).atZone(zone)
        return CairoStamp(
            occurredAtUtcMs = utcMs,
            cairoLocalIso = iso.format(zoned),
            cairoOffsetSeconds = zoned.offset.totalSeconds,
            zoneId = ZONE_ID,
        )
    }

    fun stamp(clock: Clock): CairoStamp = stamp(clock.nowUtcMs())

    fun classify(local: LocalDateTime): CairoLocalTime {
        val rules = zone.rules
        val offsets = rules.getValidOffsets(local)
        if (offsets.size == 1) {
            return CairoLocalTime.Unique(local.atOffset(offsets[0]).toInstant().toEpochMilli())
        }
        val transition = rules.getTransition(local)
            ?: throw DateTimeException("Africa/Cairo has no single offset for $local")
        if (offsets.isEmpty() || transition.isGap) {
            if (transition.duration.isNegative) {
                throw DateTimeException("Africa/Cairo gap duration was negative for $local")
            }
            val shifted = local.plus(transition.duration).atOffset(transition.offsetAfter)
            return CairoLocalTime.Gap(local, shifted.toInstant().toEpochMilli())
        }
        val earlier = local.atOffset(transition.offsetBefore).toInstant().toEpochMilli()
        val later = local.atOffset(transition.offsetAfter).toInstant().toEpochMilli()
        return CairoLocalTime.Overlap(earlier, later)
    }

    /**
     * Convert a Cairo local date-time to UTC millis.
     * Returns null only when [gap] is [GapPolicy.REJECT] and the local time was skipped.
     * An overlap uses [overlap]. A normal time ignores both policies.
     */
    fun resolveUtcMs(
        local: LocalDateTime,
        gap: GapPolicy = GapPolicy.REJECT,
        overlap: OverlapPolicy = OverlapPolicy.EARLIER,
    ): Long? = when (val found = classify(local)) {
        is CairoLocalTime.Unique -> found.utcMs
        is CairoLocalTime.Gap -> if (gap == GapPolicy.REJECT) null else found.shiftedForwardUtcMs
        is CairoLocalTime.Overlap -> when (overlap) {
            OverlapPolicy.EARLIER -> found.earlierUtcMs
            OverlapPolicy.LATER -> found.laterUtcMs
        }
    }

    /**
     * UTC millis of 00:00:00.000 on the first day of this Cairo month.
     * If that midnight is ever repeated, the earlier instant is the boundary.
     * A midnight that falls in a gap is refused rather than moved.
     */
    fun monthStartUtcMs(year: Int, month: Int): Long {
        if (month !in 1..12) throw DateTimeException("Cairo month is out of range: $month")
        val local = LocalDateTime.of(year, month, 1, 0, 0)
        return when (val found = classify(local)) {
            is CairoLocalTime.Unique -> found.utcMs
            is CairoLocalTime.Overlap -> found.earlierUtcMs
            is CairoLocalTime.Gap -> throw DateTimeException("Cairo month start $local does not exist")
        }
    }
}
