package com.pixelplus.owner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result
import com.pixelplus.owner.data.db.OwnerDatabase
import com.pixelplus.owner.data.db.RecordRules
import com.pixelplus.owner.sync.HubSync
import com.pixelplus.owner.sync.SyncStore
import com.pixelplus.owner.sync.stableEventKey
import com.pixelplus.owner.sync.stableRequestKey
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Local notifications only. This worker never calls /ack and never includes a token in the text. */
class HubCheckWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result = HubJobs.check(applicationContext)
}

/** Drains the outbox. Work input is empty: the token is read from the encrypted store. */
class OutboxDrainWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result = HubJobs.drain(applicationContext, tags.firstOrNull { it.startsWith("profile:") }?.removePrefix("profile:"))
}

internal object HubJobs {
    fun check(context: Context): Result = synchronized(com.pixelplus.owner.sync.HubSession.lock) {
        val prefs = SecureHubStore(context)
        val config = prefs.read()
        if (!config.isLive) return Result.success()
        val client = HubClient(AndroidHubTransport())
        val pending = client.pendingAll(config)
        val events = client.events(config)
        val database = runCatching { OwnerDatabase.open(context) }.getOrNull()
        var retry = pending is HubResult.Network || events is HubResult.Network
        try {
            if (database != null) {
                val store = SyncStore(database)
                val sync = HubSync(store, client)
                val now = System.currentTimeMillis()
                val profile = store.ensureProfile(config.baseUrl, now)
                if (profile != null) {
                    runCatching { sync.refresh(config, profile, now) }
                    if (pending is HubResult.Ok) sync.noteReachable(config, now)
                    val summary = sync.drain(config, now, jitterMs = (now % 5000L))
                    if (summary.retry) retry = true
                    val requestKeys = if (pending is HubResult.Ok) pending.value.map(::requestKey) else emptyList()
                    val eventKeys = if (events is HubResult.Ok) events.value.map { stableEventKey(it.id) } else emptyList()
                    val requestPlan = if (pending is HubResult.Ok) store.noticeSnapshot(profile.profileId, "notice_requests", requestKeys, now) else com.pixelplus.owner.sync.NoticePlan(emptyList(), emptyList())
                    val eventPlan = if (events is HubResult.Ok) store.noticeSnapshot(profile.profileId, "notice_events", eventKeys, now) else com.pixelplus.owner.sync.NoticePlan(emptyList(), emptyList())
                    val fresh = FreshNotice(requestPlan.notify, eventPlan.notify, SeenMarks.EMPTY)
                    notificationText(fresh)?.let {
                        if (HubNotifications.post(context, it, HubNotifications.HUB_ID)) {
                            store.markNoticesDelivered(profile.profileId, requestPlan.notify + eventPlan.notify, now)
                        }
                    }
                }
            } else {
                retry = true
            }
        } finally {
            database?.close()
        }
        return if (retry) Result.retry() else Result.success()
    }

    fun drain(context: Context, expectedProfileId: String? = null): Result = synchronized(com.pixelplus.owner.sync.HubSession.lock) {
        val config = SecureHubStore(context).read()
        if (!config.isLive) return Result.success()
        val database = runCatching { OwnerDatabase.open(context) }.getOrNull() ?: return Result.retry()
        return try {
            val store = SyncStore(database)
            if (expectedProfileId != null && store.profileForUrl(config.baseUrl)?.profileId != expectedProfileId) return Result.success()
            val summary = HubSync(store, HubClient(AndroidHubTransport()))
                .drain(config, System.currentTimeMillis(), jitterMs = 0L)
            if (summary.retry) Result.retry() else Result.success()
        } finally {
            database.close()
        }
    }

    private fun requestKey(row: LicenceRequest): String = stableRequestKey(row.id)

}

object HubScheduler {
    private const val NAME = "pixel-plus-owner-hub-check"

    fun ensure(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<HubCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }

    /** No input data. The profile id is only the work name, not a credential. */
    fun outboxRequest(profileId: String): OneTimeWorkRequest = OneTimeWorkRequestBuilder<OutboxDrainWorker>()
        .addTag("profile:$profileId")
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        )
        .build()

    fun kick(context: Context, profileId: String) {
        if (!RecordRules.validId(profileId)) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "pixel-plus-owner-outbox-$profileId",
            ExistingWorkPolicy.KEEP,
            outboxRequest(profileId),
        )
    }
}

object HubNotifications {
    private const val CHANNEL_ID = "owner_hub"
    private val ids = AtomicInteger(1000)

    const val HUB_ID = 4104

    fun post(context: Context, text: String, notificationId: Int = ids.incrementAndGet()): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "تنبيهات المركز", NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = "طلبات الترخيص والأحداث الجديدة"
            manager.createNotificationChannel(channel)
        }
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("بيكسل بلس")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            manager.notify(notificationId, notification)
            return true
        } catch (e: SecurityException) {
            // The permission was taken away between the check and the post.
            return false
        }
    }
}
