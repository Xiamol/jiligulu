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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.MainActivity
import com.jiligulu.app.R
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.domain.persona.PersonaEngine
import com.jiligulu.app.domain.persona.PersonaEventBus
import com.jiligulu.app.domain.persona.QuipLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * 喝水提醒 Worker（PRD §3.5）：
 * - 免打扰时段内直接跳过；
 * - 常驻桌宠可见 → 发事件更新文案；
 * - 后台或停留二级页面 → 发系统通知（文案从台词库 water 类型抽）。
 *
 * **它不是周期任务**：跑完一次后自己把下一次排进队列（见 [WaterReminderScheduler]）。
 * 这么做是因为 WorkManager 对 `PeriodicWorkRequest` 有 15 分钟的硬下限，
 * 而用户设的间隔可以短到 1 分钟。
 */
class WaterReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as JiliguluApp
        val prefs = app.container.userPrefs
        try {
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
        } finally {
            // 排下一次。放在 finally 是必须的：这是条「自己接自己」的链，
            // 少排一次提醒就永久停摆，而 WorkManager 不会报任何错。
            try {
                reschedule(app, prefs)
            } catch (cancelled: CancellationException) {
                // 任务被撤回（用户关了提醒）时协程已取消，本来就不该再排下一次。
                // 这里重新抛出而不是用 runCatching 吞掉——吞掉会破坏协程的取消语义。
                throw cancelled
            } catch (_: Exception) {
                // 排不上是小事，别让它盖掉 doWork 的返回值。
            }
        }
    }

    /**
     * 把下一次提醒排到另一个槽位。
     *
     * 间隔现读而不是从 inputData 取：用户在等待期间改了频率，下一次就该按新频率走。
     * 读失败时什么都不做——此时提醒已经处于异常状态，硬排一个猜出来的间隔更糟。
     */
    private suspend fun reschedule(app: JiliguluApp, prefs: UserPrefs) {
        val slot = inputData.getString(WaterReminderScheduler.KEY_SLOT) ?: return
        if (!prefs.waterEnabled.first()) return
        WaterReminderScheduler.enqueueNext(applicationContext, slot, prefs.waterIntervalMinutes.first())
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

/**
 * 喝水提醒调度（设置页开关 / 改频率时用）。
 *
 * **为什么不用 `PeriodicWorkRequest`**：
 * WorkManager 对它写死了 15 分钟下限（`WorkSpec.MIN_PERIODIC_INTERVAL_MILLIS`），
 * 传更小的值会被 `clampPeriodicIntervalDuration()` 静默提升到 15 分钟——
 * 结果就是设置页显示 1 分钟、实际 15 分钟，还查不出原因。
 * `OneTimeWorkRequest` 没有这个限制（`setInitialDelay` 收任意值），
 * 所以改成「一次性任务 + 跑完自己排下一次」来等效周期，间隔可以真做到 1 分钟。
 *
 * **为什么要两个槽位**：
 * 自链需要在 Worker 里重新入队。若始终用同一个 unique name 配 `REPLACE`，
 * 新任务会把**正在运行的自己**取消掉。于是 A/B 交替：这次跑 A，下次排 B，再下次排 A。
 *
 * **代价**：Doze 深度省电下仍可能被系统延后——这是所有后台任务共有的，换机制也绕不开。
 */
object WaterReminderScheduler {
    /** 两个槽位名对测试可见，好断言「跑完 A 就排 B」这条链真的接上了。 */
    internal const val SLOT_A = "water_reminder_a"
    internal const val SLOT_B = "water_reminder_b"

    /**
     * 0.5.4 及以前用的周期任务名。升级上来的设备里它还躺在 WorkManager 数据库中，
     * 不主动取消就会和新链并行跑，提醒直接翻倍。
     */
    internal const val LEGACY_PERIODIC_WORK = "water_reminder"

    /**
     * 调度实现的版本号。改了调度机制就递增，配合 [UserPrefs.waterScheduleVersion]
     * 做一次性的升级迁移。
     */
    const val SCHEDULE_VERSION = 2

    /** Worker 从 inputData 读自己被排在了哪个槽，好知道下一次该排到哪儿。 */
    const val KEY_SLOT = "water_reminder_slot"

    fun schedule(context: Context, intervalMinutes: Int) {
        val manager = WorkManager.getInstance(context)
        // 清掉两个历史包袱：旧的周期任务，以及另一个槽里可能残留的链。
        // 少了这一步，一条遗留的链会和新链各跑各的，提醒变成双份。
        manager.cancelUniqueWork(LEGACY_PERIODIC_WORK)
        manager.cancelUniqueWork(SLOT_B)
        enqueue(context, SLOT_A, intervalMinutes)
    }

    /** 仅由 [WaterReminderWorker] 在干完活之后调用，把下一次排到另一个槽。 */
    internal fun enqueueNext(context: Context, fromSlot: String, intervalMinutes: Int) {
        enqueue(context, if (fromSlot == SLOT_A) SLOT_B else SLOT_A, intervalMinutes)
    }

    private fun enqueue(context: Context, slot: String, intervalMinutes: Int) {
        val request = OneTimeWorkRequestBuilder<WaterReminderWorker>()
            // 下限 1 分钟是业务下限，不是系统下限——one-time 任务没有 15 分钟限制。
            .setInitialDelay(intervalMinutes.coerceAtLeast(1).toLong(), TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_SLOT to slot))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(slot, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(LEGACY_PERIODIC_WORK)
        manager.cancelUniqueWork(SLOT_A)
        manager.cancelUniqueWork(SLOT_B)
        WaterReminderWorker.cancelAllNotifications(context)
    }
}
