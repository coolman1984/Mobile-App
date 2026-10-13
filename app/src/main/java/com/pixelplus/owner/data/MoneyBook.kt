package com.pixelplus.owner.data

import android.content.Context
import com.pixelplus.owner.data.db.RecordResult
import com.pixelplus.owner.domain.CairoClock
import com.pixelplus.owner.domain.CashPosting
import com.pixelplus.owner.domain.FollowUps
import com.pixelplus.owner.domain.Money
import com.pixelplus.owner.domain.MonthlyTotals
import com.pixelplus.owner.domain.TimedPosting
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import com.pixelplus.owner.ui.refused
import com.pixelplus.owner.ui.text
import java.util.UUID

/**
 * Cash entries (M12) and follow-ups (M11) on the phone. Local only: nothing is sent to the hub, nothing here issues or promises a
 * licence, and a posted entry is never edited: a mistake is corrected by an opposite entry. References and titles are sealed with the
 * contacts key; amounts are stored as whole minor units.
 */
data class MoneyEntry(
    val id: String,
    val customerName: String,
    val amountMinor: Long,
    val currency: String,
    val receivedAtUtcMs: Long,
    val isReversal: Boolean,
    val reversed: Boolean,
)

data class MoneyOverview(val entries: List<MoneyEntry>, val monthTotals: Map<String, Long>, val customers: List<CustomerRow>, val monthLabel: String)

object MoneyBook {
    fun load(context: Context, nowUtcMs: Long): MoneyOverview? {
        val customers = CustomerBook.load(context) ?: return null
        val names = (customers.active + customers.hidden).associate { it.id to it.name }
        val zoned = Instant.ofEpochMilli(nowUtcMs).atZone(CairoClock.zone)
        return CustomerBook.withBook(context) { db, workspace ->
            val dao = db.ownerDao()
            val reversedIds = dao.recentPayments(workspace, 500).mapNotNull { it.reversalOfId }.toSet()
            val entries = dao.recentPayments(workspace, 100).map {
                MoneyEntry(it.id, names[it.customerId] ?: context.applicationContext.text("unknown_value"), it.amountMinor, it.currency, it.receivedAtUtcMs, it.reversalOfId != null, it.id in reversedIds)
            }
            val from = CairoClock.monthStartUtcMs(zoned.year, zoned.monthValue)
            val next = if (zoned.monthValue == 12) CairoClock.monthStartUtcMs(zoned.year + 1, 1) else CairoClock.monthStartUtcMs(zoned.year, zoned.monthValue + 1)
            val rows = dao.paymentsBetween(workspace, from, next).map {
                TimedPosting(CashPosting(it.id, it.customerId, it.amountMinor, it.currency, it.reversalOfId, it.correctionGroupId), it.receivedAtUtcMs)
            }
            MoneyOverview(entries, MonthlyTotals.byCurrency(rows, zoned.year, zoned.monthValue), customers.active, "%04d-%02d".format(zoned.year, zoned.monthValue))
        }
    }

    fun post(context: Context, customerId: String, amountText: String, currencyRaw: String, reference: String): BookResult {
        val app = context.applicationContext
        val currency = currencyRaw.trim().uppercase()
        val minor = Money.parse(amountText, currency)
            ?: return app.refused("book_amount_invalid")
        if (minor == 0L) return app.refused("book_amount_zero")
        if (reference.length > 120) return app.refused("book_reference_long")
        val sealer = ContactSeal.resolve() ?: return app.refused("book_no_key")
        val now = System.currentTimeMillis()
        val result = CustomerBook.withBook(context) { db, workspace ->
            db.records().postPayment(
                UUID.randomUUID().toString(), workspace, customerId, minor, currency, now,
                reference.trim().takeIf { it.isNotEmpty() }?.let { sealer.seal(it.toByteArray(Charsets.UTF_8)) }, now,
            )
        }
        return if (result == RecordResult.Applied) BookResult.Ok else app.refused("book_payment_failed")
    }

    fun reverse(context: Context, id: String): BookResult {
        val app = context.applicationContext
        val result = CustomerBook.withBook(context) { db, _ ->
            db.records().reversePayment(id, UUID.randomUUID().toString(), UUID.randomUUID().toString(), System.currentTimeMillis())
        }
        return when (result) {
            RecordResult.Applied -> BookResult.Ok
            RecordResult.Conflict -> app.refused("book_already_reversed")
            else -> app.refused("book_reverse_failed")
        }
    }
}

data class FollowUpItem(val id: String, val title: String, val customerName: String?, val dueAtUtcMs: Long, val reminderOffsetMin: Int, val done: Boolean)

object FollowUpBook {
    fun load(context: Context): List<FollowUpItem>? {
        val sealer = ContactSeal.resolve() ?: return null
        val customers = CustomerBook.load(context) ?: return null
        val names = (customers.active + customers.hidden).associate { it.id to it.name }
        return CustomerBook.withBook(context) { db, workspace ->
            db.ownerDao().activeFollowUps(workspace).map {
                FollowUpItem(
                    it.id, runCatching { String(sealer.open(it.encryptedTitle), Charsets.UTF_8) }.getOrDefault(context.applicationContext.text("unknown_value")),
                    it.customerId?.let(names::get), it.dueAtUtcMs, it.reminderOffsetMin, it.completedAtUtcMs != null,
                )
            }
        }
    }

    /** [dateText] is `yyyy-MM-dd` and [timeText] is `HH:mm`, both in Cairo time. A skipped time moves forward and is reported. */
    fun add(context: Context, title: String, customerId: String?, dateText: String, timeText: String, offsetMin: Int): BookResult {
        val app = context.applicationContext
        val clean = title.trim()
        if (clean.isEmpty() || clean.length > 120) return app.refused("book_title_invalid")
        val date = runCatching { LocalDate.parse(ArabicDigits.ascii(dateText).trim()) }.getOrNull()
        val time = runCatching { LocalTime.parse(ArabicDigits.ascii(timeText).trim()) }.getOrNull()
        if (date == null || time == null) return app.refused("book_datetime_invalid")
        val when_ = FollowUps.schedule(LocalDateTime.of(date, time), offsetMin) ?: return app.refused("book_reminder_invalid")
        val sealer = ContactSeal.resolve() ?: return app.refused("book_no_key")
        val result = CustomerBook.withBook(context) { db, workspace ->
            db.records().createFollowUp(UUID.randomUUID().toString(), workspace, customerId, sealer.seal(clean.toByteArray(Charsets.UTF_8)), when_.dueUtcMs, offsetMin, System.currentTimeMillis())
        }
        return if (result == RecordResult.Applied) BookResult.Ok else app.refused("book_followup_failed")
    }

    fun complete(context: Context, id: String): BookResult {
        val app = context.applicationContext
        val result = CustomerBook.withBook(context) { db, _ -> db.records().completeFollowUp(id, System.currentTimeMillis()) }
        return if (result == RecordResult.Applied) BookResult.Ok else app.refused("book_no_change")
    }

    fun hide(context: Context, id: String): BookResult {
        val app = context.applicationContext
        val result = CustomerBook.withBook(context) { db, _ -> db.records().softDeleteFollowUp(id, System.currentTimeMillis()) }
        return if (result == RecordResult.Applied) BookResult.Ok else app.refused("book_no_change")
    }
}

internal object ArabicDigits {
    fun ascii(text: String): String = text.map { c ->
        when (c) {
            in '٠'..'٩' -> '0' + (c - '٠')
            in '۰'..'۹' -> '0' + (c - '۰')
            else -> c
        }
    }.joinToString("")
}
