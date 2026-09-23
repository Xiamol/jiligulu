package com.jiligulu.app.data.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jiligulu.app.MainActivity
import com.jiligulu.app.R
import com.jiligulu.app.data.prefs.PendingWater

object WaterReminderNotifications {
    const val CHANNEL_ID = "water_reminder_v2"
    private const val LEGACY_CHANNEL_ID = "water_reminder"
    private const val NOTIFICATION_ID_BASE = 1000
    const val EXTRA_WATER_REMINDER = "com.jiligulu.app.WATER_REMINDER"

    fun notificationIdFor(reminderId: Long): Int = NOTIFICATION_ID_BASE + (reminderId % 10_000).toInt()

    fun post(context: Context, pending: PendingWater): Boolean {
        if (!pending.isPending) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        createChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_WATER_REMINDER, pending.id)
        }
        val contentIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_water_notification)
            .setContentTitle("叽里咕噜 · 喝水提醒")
            .setContentText(pending.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(pending.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        return try {
            manager.notify(notificationIdFor(pending.id), notification)
            true
        } catch (_: SecurityException) {
            false // Permission can be revoked after the check. The persisted cup still survives.
        }
    }

    fun cancel(context: Context, reminderId: Long) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(notificationIdFor(reminderId))
    }

    fun cancelAll(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val notifications = runCatching { manager.activeNotifications }.getOrNull().orEmpty()
        notifications.filter {
            it.notification.channelId == CHANNEL_ID || it.notification.channelId == LEGACY_CHANNEL_ID
        }.forEach { manager.cancel(it.id) }
    }

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "喝水提醒", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "叽里咕噜提醒你喝水"
            enableVibration(true)
        })
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
    }
}
