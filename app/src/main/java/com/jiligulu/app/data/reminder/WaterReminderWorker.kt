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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            // 每次提醒都用新 ID：复用同一个 ID + OnlyAlertOnce 会让系统把后续提醒
            // 当成「同一条通知的更新」而不再响铃/弹横幅，这是后台收不到提示的真凶。
            NotificationManagerCompat.from(applicationContext)
                .notify(notificationIdFor(reminderId), notification)
        } catch (_: SecurityException) {
            // 权限在运行中被回收的兜底，不 crash
        }
    }

    companion object {
        /**
         * 渠道 ID 带版本后缀：Android 建渠道后 IMPORTANCE 不可改，
         * 老版本建的是 DEFAULT，这里换新 ID 才能拿到 HIGH（弹横幅 + 响铃）。
         */
        const val CHANNEL_ID = "water_reminder_v2"
        private const val LEGACY_CHANNEL_ID = "water_reminder"
        private const val NOTIFICATION_ID_BASE = 1000

        /** 提醒 id 是单调递增的，直接拿来当通知 id 就能保证每次都是新提醒。 */
        fun notificationIdFor(reminderId: Long): Int =
            NOTIFICATION_ID_BASE + (reminderId % 10_000).toInt()

        const val EXTRA_WATER_REMINDER = "com.jiligulu.app.WATER_REMINDER"

        fun cancelNotification(context: Context, reminderId: Long) {
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(notificationIdFor(reminderId))
            // 早期版本用过固定 ID，顺手清掉，避免升级后残留一条划不掉的通知。
            manager.cancel(NOTIFICATION_ID_BASE + 1)
        }

        /** 关掉提醒开关时，把所有已发出的喝水通知一并撤回。 */
        fun cancelAllNotifications(context: Context) {
            val manager = NotificationManagerCompat.from(context)
            // 直接枚举当前活跃通知，只撤自己这一条渠道的，不碰系统里别人的。
            val mine = runCatching { manager.activeNotifications }.getOrNull().orEmpty()
                .filter { it.id in NOTIFICATION_ID_BASE..(NOTIFICATION_ID_BASE + 10_000) }
            mine.forEach { manager.cancel(it.id) }
        }

        /** App 启动时建通知渠道（Android 8+ 必须） */
        fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID, "喝水提醒", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "叽里咕噜提醒你喝水"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
            // 清掉旧渠道，免得通知设置里出现两条同名项
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
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
        WaterReminderWorker.cancelAllNotifications(context)
    }
}
