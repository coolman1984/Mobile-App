package com.pixelplus.owner.sync

import android.content.Context
import com.pixelplus.owner.ui.bothLines
import com.pixelplus.owner.ui.text

/**
 * Both languages are shown. The phone language does not hide one of them.
 * Remote success is a different sentence from a queued intent.
 */
data class BothLines(val arabic: String, val english: String) {
    val display: String get() = arabic + "\n" + english
}

object SyncCopy {
    fun cachedSnapshot(context: Context, lastSuccessMs: Long): BothLines {
        val stamp = com.pixelplus.owner.domain.CairoClock.stamp(lastSuccessMs).cairoLocalIso
        return context.bothLines("sync_cached", stamp)
    }

    fun settingsChanged(context: Context) = context.bothLines("sync_settings_changed")

    fun queued(context: Context, action: String): BothLines = when (action) {
        OutboxRules.ACTION_APPROVE -> context.bothLines("sync_queued_approve")
        else -> context.bothLines("sync_queued_refuse")
    }

    fun notRemote(context: Context) = context.bothLines("sync_not_remote")

    fun existing(context: Context, status: String): BothLines = when (status) {
        OutboxStatus.CONFIRMED_REMOTE.name -> context.bothLines("sync_confirmed")
        else -> context.bothLines("sync_duplicate")
    }

    fun queuedDisplay(context: Context, action: String): String =
        queued(context, action).display + "\n" + notRemote(context).display

    fun queueLine(context: Context, waiting: Int, oldestAgeMs: Long?): String {
        if (waiting <= 0) return ""
        val age = oldestAgeMs?.let { ageWords(context, it) }
        return if (age == null) {
            context.bothLines("sync_queue_waiting", waiting).display
        } else {
            context.bothLines("sync_queue_oldest", waiting, age.display).display
        }
    }

    private fun ageWords(context: Context, ageMs: Long): BothLines {
        val minutes = (ageMs.coerceAtLeast(0L) / 60_000L).toInt()
        return when {
            minutes < 1 -> context.bothLines("sync_age_lt_min")
            minutes < 60 -> context.bothLines("sync_age_minutes", minutes)
            else -> context.bothLines("sync_age_hours", minutes / 60)
        }
    }
}
