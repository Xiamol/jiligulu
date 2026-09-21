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
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.MainActivity
import com.jiligulu.app.R
import com.jiligulu.app.domain.persona.PersonaEngine
import com.jiligulu.app.domain.persona.PersonaEventBus
import com.jiligulu.app.domain.persona.QuipLibrary
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * 喝水提醒 Worker（PRD §3.5）：
 * - 免打扰时段内直接跳过；
 * - 常驻桌宠可见 → 发事件更新文案；
 * - 后台或停留二级页面 → 发系统通知（文案从台词库 water 类型抽）。
 *
 * 注意：WorkManager 周期任务硬下限 15 分钟（系统限制）。
 */
class WaterReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as JiliguluApp
        val prefs = app.container.userPrefs
        if (!prefs.waterEnabled.first()) return Result.success()

        val engine = PersonaEngine(QuipLibrary.get(applicationContext))
        val now = System.currentTimeMillis()
        val quietStart = prefs.quietStartMinutes.first()
        val quietEnd = prefs.quietEndMinutes.first()
        if (engine.inQuietHours(now, quietStart, quietEnd)) return Result.success()

        val name = buildDisplayName(prefs.nickname.first(), prefs.nameSuffix.first())
        val pending = prefs.markWaterDue(now, engine.nextWaterQuipForNotification(name))
        if (pending.isPending && !(app.isForeground && PersonaEventBus.isHostVisible)) {
            postNotification(pending.text, pending.id)
        }
        return Result.success()
    }

    private fun buildDisplayName(nickname: String, suffix: String): String =
        if (nickname.isBlank()) "" else "$nickname$suffix"

    private fun postNotification(text: String, reminderId: Long) {
        // Android 13+ 需要运行时通知权限；未授权时静默放弃（设置页开启时会引导授权）
        if (Build.VERSION.SDK_INT >= 33 &&
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_WATER_REMINDER, reminderId)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_water_notification)
            .setContentTitle("叽里咕噜 · 喝水提醒")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // 权限在运行中被回收的兜底，不 crash
        }
    }

    companion object {
        const val CHANNEL_ID = "water_reminder"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_WATER_REMINDER = "com.jiligulu.app.WATER_REMINDER"

        fun cancelNotification(context: Context) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }

        /** App 启动时建通知渠道（Android 8+ 必须） */
        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID, "喝水提醒", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "叽里咕噜提醒你喝水" }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}

/** 喝水提醒调度封装：设置页开关/改频率时用 */
object WaterReminderScheduler {
    private const val UNIQUE_WORK = "water_reminder"

    fun schedule(context: Context, intervalMinutes: Int) {
        val request = PeriodicWorkRequestBuilder<WaterReminderWorker>(
            intervalMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        WaterReminderWorker.cancelNotification(context)
    }
}
