package com.pixelplus.owner.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * M12: money in minor units, expiry buckets and local trial drafts. Pure. No floating point anywhere near an amount.
 * Nothing here issues, signs or promises a licence: a trial draft is a local note and says so.
 */
object Money {
    /** Digits after the decimal point for a currency, from the JDK's ISO table. */
    fun exponent(currency: String): Int? = runCatching { java.util.Currency.getInstance(currency).defaultFractionDigits }
        .getOrNull()?.takeIf { it >= 0 }

    /** Plain ASCII text such as "1250.50" or "-3.00". The screen applies the locale. Null for an unknown currency. */
    fun plain(amountMinor: Long, currency: String): String? {
        val digits = exponent(currency) ?: return null
        if (amountMinor == Long.MIN_VALUE) return null
        val negative = amountMinor < 0
        val abs = if (negative) -amountMinor else amountMinor
        if (digits == 0) return (if (negative) "-" else "") + abs
        val text = abs.toString().padStart(digits + 1, '0')
        return (if (negative) "-" else "") + text.dropLast(digits) + "." + text.takeLast(digits)
    }

    /** Parses what the owner typed ("1250.5", "1,250.50", Arabic-Indic digits) into minor units. Null if it has more places than the currency. */
    fun parse(text: String, currency: String): Long? {
        val digits = exponent(currency) ?: return null
        val s = ArabicSearch.normalize(text).replace(",", "").replace("٫", ".").replace("٬", "")
        val m = Regex("^(-?)([0-9]{1,15})(?:\\.([0-9]{1,6}))?$").matchEntire(s) ?: return null
        val fraction = m.groupValues[3]
        if (fraction.length > digits) return null
        val minor = (m.groupValues[2] + fraction.padEnd(digits, '0')).toLongOrNull() ?: return null
        return if (m.groupValues[1] == "-") -minor else minor
    }
}

data class TimedPosting(val posting: CashPosting, val receivedAtUtcMs: Long)

object MonthlyTotals {
    /**
     * Totals of one Cairo calendar month, one number per currency, never mixed. A correction belongs to the month in which it was
     * posted; the original stays in its own month, unchanged.
     */
    fun byCurrency(postings: List<TimedPosting>, year: Int, month: Int): Map<String, Long> {
        val from = CairoClock.monthStartUtcMs(year, month)
        val next = if (month == 12) CairoClock.monthStartUtcMs(year + 1, 1) else CairoClock.monthStartUtcMs(year, month + 1)
        return postings.filter { it.receivedAtUtcMs in from until next }
            .groupBy { it.posting.currency }
            .mapValues { (_, rows) -> rows.sumOf { it.posting.amountMinor } }
            .toSortedMap()
    }
}

enum class ExpiryBucket { PERPETUAL, UNKNOWN, EXPIRED, IN_GRACE, DUE_7_DAYS, DUE_30_DAYS, LATER }

data class LicenceTerms(
    val perpetual: Boolean = false,
    /** Inclusive last day of the licence, if the hub or the owner knows it. Never guessed from a request date. */
    val lastDay: LocalDate? = null,
    val graceDays: Int = 0,
)

object Expiry {
    const val MAX_GRACE = 60

    fun bucket(terms: LicenceTerms, today: LocalDate): ExpiryBucket {
        if (terms.perpetual) return ExpiryBucket.PERPETUAL
        val last = terms.lastDay ?: return ExpiryBucket.UNKNOWN
        val grace = terms.graceDays.coerceIn(0, MAX_GRACE)
        val daysLeft = ChronoUnit.DAYS.between(today, last)
        return when {
            daysLeft >= 31 -> ExpiryBucket.LATER
            daysLeft >= 8 -> ExpiryBucket.DUE_30_DAYS
            daysLeft >= 0 -> ExpiryBucket.DUE_7_DAYS
            -daysLeft <= grace -> ExpiryBucket.IN_GRACE
            else -> ExpiryBucket.EXPIRED
        }
    }
}

/** A trial the owner writes down on the phone. It is local, issues nothing and says so. */
data class TrialDraft(val productCode: String, val days: Int, val local: Boolean = true)

sealed class TrialCheck {
    data class Ok(val draft: TrialDraft) : TrialCheck()
    data class Refused(val reason: String) : TrialCheck()
}

object Trials {
    const val MIN_DAYS = 1
    const val MAX_DAYS = 60
    private val PRODUCT = Regex("^[a-z0-9][a-z0-9-]{0,39}$")

    fun draft(productCode: String, days: Int): TrialCheck = when {
        !PRODUCT.matches(productCode) -> TrialCheck.Refused("product")
        days !in MIN_DAYS..MAX_DAYS -> TrialCheck.Refused("days")
        else -> TrialCheck.Ok(TrialDraft(productCode, days))
    }
}
