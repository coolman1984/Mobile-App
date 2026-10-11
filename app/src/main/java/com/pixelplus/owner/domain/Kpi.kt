package com.pixelplus.owner.domain

import java.time.Instant

/**
 * M10: what the home screen and the request tabs may say. Pure.
 *
 * Honesty rules: an owner's approval is a recorded decision, never an issued licence; an expired approval is shown as such; a status
 * the phone does not know is counted as unknown, not guessed into a tab; a request seen twice counts once.
 */
data class RequestFact(
    val id: String,
    /** The hub's status: pending | issued | refused | delivered | expired, or anything else. */
    val status: String,
    /** approved | denied | expired | null */
    val ownerDecision: String?,
    val createdAtSec: Long,
)

enum class RequestTab { WAITING, APPROVED_WAITING_FOR_PC, APPROVAL_EXPIRED, REFUSED, ISSUED, EXPIRED, UNKNOWN }

object RequestTabs {
    fun of(fact: RequestFact): RequestTab = when (fact.status) {
        "pending" -> when (fact.ownerDecision) {
            null, "" -> RequestTab.WAITING
            "approved" -> RequestTab.APPROVED_WAITING_FOR_PC
            "expired" -> RequestTab.APPROVAL_EXPIRED
            "denied" -> RequestTab.REFUSED
            else -> RequestTab.UNKNOWN
        }
        "refused" -> RequestTab.REFUSED
        "issued", "delivered" -> RequestTab.ISSUED
        "expired" -> RequestTab.EXPIRED
        else -> RequestTab.UNKNOWN
    }

    /** Only a waiting request, or one whose approval expired, can still be decided from the phone. */
    fun canDecide(fact: RequestFact): Boolean = of(fact).let { it == RequestTab.WAITING || it == RequestTab.APPROVAL_EXPIRED }
}

data class KpiSummary(
    val total: Int,
    val byTab: Map<RequestTab, Int>,
    val createdToday: Int,
    /** Rows that were repeated in the input and ignored. */
    val duplicates: Int,
) {
    val unknown: Int get() = byTab[RequestTab.UNKNOWN] ?: 0
}

object Kpi {
    /** [nowUtcMs] decides "today" in Africa/Cairo, DST included. A newer duplicate of an id replaces an older one only if it is later. */
    fun summarize(facts: List<RequestFact>, nowUtcMs: Long): KpiSummary {
        val unique = LinkedHashMap<String, RequestFact>()
        var duplicates = 0
        for (f in facts) {
            val seen = unique[f.id]
            if (seen == null) unique[f.id] = f else { duplicates++; if (f.createdAtSec > seen.createdAtSec) unique[f.id] = f }
        }
        val today = Instant.ofEpochMilli(nowUtcMs).atZone(CairoClock.zone).toLocalDate()
        val byTab = RequestTab.entries.associateWith { 0 }.toMutableMap()
        var createdToday = 0
        for (f in unique.values) {
            byTab[RequestTabs.of(f)] = byTab.getValue(RequestTabs.of(f)) + 1
            if (Instant.ofEpochSecond(f.createdAtSec).atZone(CairoClock.zone).toLocalDate() == today) createdToday++
        }
        return KpiSummary(unique.size, byTab, createdToday, duplicates)
    }
}

/** A small chart is drawn only from real, complete numbers. */
object ChartData {
    fun valid(points: List<Long>): Boolean = points.size >= 2 && points.all { it >= 0 } && points.any { it > 0 }
}
