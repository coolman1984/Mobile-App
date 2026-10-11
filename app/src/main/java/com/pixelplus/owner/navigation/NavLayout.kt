package com.pixelplus.owner.navigation

/** Bottom bar on a phone, rail from 600dp up. The same five roots in both. */
enum class NavMode { BOTTOM_BAR, RAIL }

object NavLayout {
    const val RAIL_FROM_DP = 600

    fun mode(widthDp: Int): NavMode = if (widthDp >= RAIL_FROM_DP) NavMode.RAIL else NavMode.BOTTOM_BAR

    /** Counts only. Never a name. `null` shows no badge; 100 or more shows the overflow text. */
    fun badges(pendingRequests: Int, unreadAlerts: Int, blockedQueue: Int): Map<RootTab, Int?> = mapOf(
        RootTab.REQUESTS to pendingRequests.coerceAtLeast(0).takeIf { it > 0 },
        RootTab.ALERTS to unreadAlerts.coerceAtLeast(0).takeIf { it > 0 },
        RootTab.MORE to blockedQueue.coerceAtLeast(0).takeIf { it > 0 },
    )

    const val BADGE_CAP = 99

    /** Western digits here; the screen formats with the locale. `overflow` is the localized "99+". */
    fun badgeText(count: Int?, overflow: String): String? = when {
        count == null || count <= 0 -> null
        count > BADGE_CAP -> overflow
        else -> count.toString()
    }
}
