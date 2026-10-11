package com.pixelplus.owner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class DomainTest {
    private fun utc(y: Int, m: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, m, d, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun fact(id: String, status: String, decision: String? = null, at: Long = 1_800_000_000L) = RequestFact(id, status, decision, at)

    // ---- M10 ----

    @Test fun approvalIsNeverIssuedAndAnExpiredApprovalCanBeDecidedAgain() {
        assertEquals(RequestTab.WAITING, RequestTabs.of(fact("a", "pending")))
        assertEquals(RequestTab.APPROVED_WAITING_FOR_PC, RequestTabs.of(fact("a", "pending", "approved")))
        assertEquals(RequestTab.APPROVAL_EXPIRED, RequestTabs.of(fact("a", "pending", "expired")))
        assertEquals(RequestTab.REFUSED, RequestTabs.of(fact("a", "pending", "denied")))
        assertEquals(RequestTab.REFUSED, RequestTabs.of(fact("a", "refused")))
        assertEquals(RequestTab.ISSUED, RequestTabs.of(fact("a", "delivered")))
        assertEquals(RequestTab.EXPIRED, RequestTabs.of(fact("a", "expired")))
        assertEquals(RequestTab.UNKNOWN, RequestTabs.of(fact("a", "weird")))
        assertEquals(RequestTab.UNKNOWN, RequestTabs.of(fact("a", "pending", "maybe")))
        assertTrue(RequestTabs.canDecide(fact("a", "pending")))
        assertTrue(RequestTabs.canDecide(fact("a", "pending", "expired")))
        assertFalse(RequestTabs.canDecide(fact("a", "pending", "approved")))
        assertFalse(RequestTabs.canDecide(fact("a", "issued")))
    }

    @Test fun kpisCountEachRequestOnceAndUnknownSeparately() {
        val now = utc(2026, 10, 11)
        val facts = listOf(
            fact("1", "pending", at = now / 1000), fact("2", "pending", "approved", at = now / 1000), fact("3", "issued", at = now / 1000 - 3 * 86400),
            fact("2", "pending", "approved", at = now / 1000), fact("4", "mystery", at = now / 1000),
        )
        val k = Kpi.summarize(facts, now)
        assertEquals(4, k.total)
        assertEquals(1, k.duplicates)
        assertEquals(1, k.unknown)
        assertEquals(3, k.createdToday)
        assertEquals(k.total, k.byTab.values.sum())
    }

    @Test fun todayFollowsCairoMidnightNotUtc() {
        val now = utc(2026, 7, 1, 21).let { it + 30 * 60_000 }       // 21:30 UTC = 00:30 on 2 July in Cairo (summer, +3)
        val before = (now - 60 * 60_000) / 1000                     // 23:30 on 1 July in Cairo
        val after = (now) / 1000
        val k = Kpi.summarize(listOf(fact("old", "pending", at = before), fact("new", "pending", at = after)), now)
        assertEquals(1, k.createdToday)
    }

    @Test fun chartsNeedRealData() {
        assertFalse(ChartData.valid(emptyList())); assertFalse(ChartData.valid(listOf(5L))); assertFalse(ChartData.valid(listOf(0L, 0L)))
        assertFalse(ChartData.valid(listOf(1L, -1L))); assertTrue(ChartData.valid(listOf(0L, 3L)))
    }

    // ---- M11 ----

    @Test fun arabicSearchIgnoresMarksVariantsAndDigits() {
        assertEquals(ArabicSearch.normalize("أحمد"), ArabicSearch.normalize("احمد"))
        assertEquals(ArabicSearch.normalize("مُحَمَّد"), ArabicSearch.normalize("محمد"))
        assertEquals(ArabicSearch.normalize("مدرسة"), ArabicSearch.normalize("مدرسه"))
        assertEquals(ArabicSearch.normalize("على"), ArabicSearch.normalize("علي"))
        assertEquals("shop 12", ArabicSearch.normalize("  SHOP   ١٢ "))
        assertEquals("shop 12", ArabicSearch.normalize("Shop ۱۲"))
        assertTrue(ArabicSearch.matches("احمد ", "الأستاذ أحمد للتجارة"))
        assertTrue(ArabicSearch.matches("", "anything"))
        assertFalse(ArabicSearch.matches("خالد", "أحمد"))
        assertTrue(ArabicSearch.matches("ahmed trade", "Trade house of AHMED"))
    }

    @Test fun filterSortedOrdersByNormalizedName() {
        val names = listOf("حسن", "أحمد", "Ali", "احمد زكي")
        assertEquals(listOf("أحمد", "احمد زكي"), ArabicSearch.filterSorted(names, "احمد") { it })
        assertEquals(4, ArabicSearch.filterSorted(names, "") { it }.size)
    }

    @Test fun followUpsHandleSummerTimeAndBadOffsets() {
        // Cairo's clock moved forward on the last Friday of April 2026 (24 April): 00:00 does not exist that day
        val skipped = FollowUps.schedule(LocalDateTime.of(2026, 4, 24, 0, 30), 15)!!
        assertTrue(skipped.shiftedBecauseSkipped)
        val normal = FollowUps.schedule(LocalDateTime.of(2026, 3, 10, 9, 0), 60)!!
        assertFalse(normal.shiftedBecauseSkipped)
        assertEquals(normal.dueUtcMs - 3_600_000, normal.remindUtcMs)
        assertEquals(LocalDateTime.of(2026, 3, 10, 7, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), normal.dueUtcMs)   // winter: Cairo is UTC+2
        val repeated = FollowUps.schedule(LocalDateTime.of(2026, 10, 29, 23, 30), 0)!!   // the clock goes back from 24:00 to 23:00: 23:30 happens twice
        assertFalse(repeated.shiftedBecauseSkipped)
        assertEquals(LocalDateTime.of(2026, 10, 29, 20, 30).toInstant(ZoneOffset.UTC).toEpochMilli(), repeated.dueUtcMs)   // the first one, still UTC+3
        assertNull(FollowUps.schedule(LocalDateTime.of(2026, 3, 10, 9, 0), -1))
        assertNull(FollowUps.schedule(LocalDateTime.of(2026, 3, 10, 9, 0), FollowUps.MAX_OFFSET_MIN + 1))
    }

    @Test fun onlyACallOrAPlainWebPageMayBeOffered() {
        assertEquals("tel:+201001234567", IntentUri.safe(" tel:+201001234567 "))
        assertEquals("https://example.com/page?x=1", IntentUri.safe("https://example.com/page?x=1"))
        val refused = listOf(
            "https://wa.me/201001234567", "https://api.whatsapp.com/send?phone=1", "https://web.whatsapp.com/", "https://x.wa.me/1",
            "whatsapp://send?phone=1", "intent://x#Intent;end", "javascript:alert(1)", "file:///etc/passwd", "content://x", "http://example.com",
            "sms:+201001234567", "tel:abc", "tel:+1", "https://user@example.com/", "https://example.com:8080/", "https://localhost/", "https://exa mple.com",
            "https://example.com/\u0000", "", "https://" + "a".repeat(400) + ".com",
        )
        for (r in refused) assertNull(r, IntentUri.safe(r))
    }

    // ---- M12 ----

    @Test fun moneyIsExactAndPerCurrency() {
        assertEquals("1250.50", Money.plain(125050, "EGP"))
        assertEquals("-3.05", Money.plain(-305, "EGP"))
        assertEquals("0.05", Money.plain(5, "USD"))
        assertEquals("500", Money.plain(500, "JPY"))
        assertEquals("1.234", Money.plain(1234, "KWD"))
        assertNull(Money.plain(1, "XXQ")); assertNull(Money.plain(Long.MIN_VALUE, "EGP"))
        assertEquals(125050L, Money.parse("1,250.5", "EGP"))
        assertEquals(125050L, Money.parse("١٢٥٠٫٥٠", "EGP"))
        assertEquals(-305L, Money.parse("-3.05", "EGP"))
        assertEquals(500L, Money.parse("500", "JPY"))
        assertNull(Money.parse("1.234", "EGP")); assertNull(Money.parse("1.5", "JPY")); assertNull(Money.parse("abc", "EGP"))
        assertNull(Money.parse("9999999999999999", "EGP")); assertNull(Money.parse("", "EGP"))
    }

    @Test fun monthlyTotalsUseCairoMonthsKeepCurrenciesApartAndKeepCorrections() {
        fun p(id: String, minor: Long, cur: String, at: Long, rev: String? = null) = TimedPosting(CashPosting(id, "c", minor, cur, rev), at)
        val julyStart = CairoClock.monthStartUtcMs(2026, 7)
        val rows = listOf(
            p("a", 10_000, "EGP", julyStart), p("b", 2_500, "USD", julyStart + 1), p("c", 700, "EGP", julyStart - 1),
            p("d", -10_000, "EGP", julyStart + 86_400_000, rev = "a"),
        )
        assertEquals(mapOf("EGP" to 0L, "USD" to 2_500L), MonthlyTotals.byCurrency(rows, 2026, 7))
        assertEquals(mapOf("EGP" to 700L), MonthlyTotals.byCurrency(rows, 2026, 6))
        assertEquals(emptyMap<String, Long>(), MonthlyTotals.byCurrency(rows, 2026, 8))
        assertEquals(10_000L, rows[0].posting.amountMinor)   // the original is untouched
        assertEquals(rows.size, rows.map { it.posting.id }.toSet().size)
    }

    @Test fun expiryBucketsNeverInventADate() {
        val today = LocalDate.of(2026, 10, 11)
        fun b(last: LocalDate?, grace: Int = 0, perpetual: Boolean = false) = Expiry.bucket(LicenceTerms(perpetual, last, grace), today)
        assertEquals(ExpiryBucket.PERPETUAL, b(null, perpetual = true))
        assertEquals(ExpiryBucket.UNKNOWN, b(null))
        assertEquals(ExpiryBucket.DUE_7_DAYS, b(today))
        assertEquals(ExpiryBucket.DUE_7_DAYS, b(today.plusDays(7)))
        assertEquals(ExpiryBucket.DUE_30_DAYS, b(today.plusDays(8)))
        assertEquals(ExpiryBucket.DUE_30_DAYS, b(today.plusDays(30)))
        assertEquals(ExpiryBucket.LATER, b(today.plusDays(31)))
        assertEquals(ExpiryBucket.IN_GRACE, b(today.minusDays(3), grace = 3))
        assertEquals(ExpiryBucket.EXPIRED, b(today.minusDays(4), grace = 3))
        assertEquals(ExpiryBucket.EXPIRED, b(today.minusDays(1)))
        assertEquals(ExpiryBucket.IN_GRACE, b(today.minusDays(60), grace = 500))
        assertEquals(ExpiryBucket.EXPIRED, b(today.minusDays(61), grace = 500))
        // a leap day is an ordinary day
        assertEquals(ExpiryBucket.DUE_7_DAYS, Expiry.bucket(LicenceTerms(false, LocalDate.of(2028, 3, 1)), LocalDate.of(2028, 2, 28)))
    }

    @Test fun aTrialDraftIsLocalAndBounded() {
        assertEquals(TrialCheck.Ok(TrialDraft("al-store", 14)), Trials.draft("al-store", 14))
        assertTrue((Trials.draft("al-store", 14) as TrialCheck.Ok).draft.local)
        assertEquals(TrialCheck.Refused("days"), Trials.draft("al-store", 0))
        assertEquals(TrialCheck.Refused("days"), Trials.draft("al-store", 61))
        assertTrue(Trials.draft("al-store", 1) is TrialCheck.Ok && Trials.draft("al-store", 60) is TrialCheck.Ok)
        assertEquals(TrialCheck.Refused("product"), Trials.draft("Al Store", 14))
        assertEquals(TrialCheck.Refused("product"), Trials.draft("", 14))
    }
}
