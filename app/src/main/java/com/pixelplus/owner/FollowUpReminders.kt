package com.pixelplus.owner

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.pixelplus.owner.data.db.OwnerDatabase
import java.util.concurrent.TimeUnit

/**
 * Reminders for the owner's follow-ups, shown as a local notification even when the app is closed. No network and no hub needed.
 * The text says only how many are due ("1 متابعة حان موعدها"), never a title or a name. A follow-up reminds once: its id is remembered
 * in the app's private preferences. Android may delay a 15-minute job; this is a reminder, not an alarm.
 */
class FollowUpReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = FollowUpReminders.run(applicationContext)
}

object FollowUpReminders {
    private const val NAME = "pixel-plus-owner-followup-reminders"
    private const val NOTIFICATION_ID = 4105
    private const val PREFS = "owner_prefs"
    private const val KEY = "fu_notified"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<FollowUpReminderWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Ids whose reminder time has come and that were not reminded yet. */
    internal fun due(rows: List<Triple<String, Long, Int>>, nowUtcMs: Long, notified: Set<String>): List<String> =
        rows.filter { (id, dueAt, offsetMin) -> id !in notified && dueAt - offsetMin * 60_000L <= nowUtcMs }.map { it.first }

    fun run(context: Context): Result {
        val database = runCatching { OwnerDatabase.open(context) }.getOrNull() ?: return Result.retry()
        try {
            val now = System.currentTimeMillis()
            val rows = mutableListOf<Triple<String, Long, Int>>()
            database.openHelper.readableDatabase.query(
                "SELECT id, dueAtUtcMs, reminderOffsetMin FROM follow_ups WHERE deletedAtUtcMs IS NULL AND completedAtUtcMs IS NULL",
            ).use { c -> while (c.moveToNext()) rows += Triple(c.getString(0), c.getLong(1), c.getInt(2)) }
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val notified = sp.getStringSet(KEY, emptySet()).orEmpty().toSet()
            val fresh = due(rows, now, notified)
            if (fresh.isNotEmpty()) {
                val text = if (fresh.size == 1) "1 متابعة حان موعدها" else "${fresh.size} متابعات حان موعدها"
                // remembered only when Android accepted the notification, so a denied permission does not swallow the reminder
                if (HubNotifications.post(context, text, NOTIFICATION_ID)) {
                    sp.edit().putStringSet(KEY, (notified + fresh).toSet()).apply()
                }
            }
            // forget ids that no longer exist so the set cannot grow without limit
            val alive = rows.map { it.first }.toSet()
            val kept = notified.filter { it in alive }.toSet()
            if (kept.size != notified.size) sp.edit().putStringSet(KEY, kept).apply()
            return Result.success()
        } finally {
            database.close()
        }
    }
}
