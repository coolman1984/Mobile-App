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
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Local notifications only. This worker never calls /ack and never includes a token in the text. */
class HubCheckWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val store = SecureHubStore(applicationContext)
        val config = store.read()
        if (!config.isLive) return Result.success()
        val client = HubClient(AndroidHubTransport())
        val pending = client.pending(config)
        val events = client.events(config)
        val fresh = noticeNew(
            store.readSeen(),
            if (pending is HubResult.Ok) pending.value.map { it.id } else null,
            if (events is HubResult.Ok) events.value.map { it.id } else null
        )
        store.writeSeen(fresh.next)
        val text = notificationText(fresh)
        if (text != null) HubNotifications.post(applicationContext, text)
        val retry = pending is HubResult.Network || events is HubResult.Network
        return if (retry) Result.retry() else Result.success()
    }
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
}

object HubNotifications {
    private const val CHANNEL_ID = "owner_hub"
    private val ids = AtomicInteger(1000)

    fun post(context: Context, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
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
            manager.notify(ids.incrementAndGet(), notification)
        } catch (e: SecurityException) {
            // The permission was taken away between the check and the post.
        }
    }
}
