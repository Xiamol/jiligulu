package com.jiligulu.app.data.reminder

import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.domain.persona.PersonaEventBus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.app.NotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = JiliguluApp::class)
class WaterReminderPersistenceTest {
    private val app get() = RuntimeEnvironment.getApplication() as JiliguluApp
    private val prefs get() = app.container.userPrefs

    @Before fun reset() = runBlocking {
        prefs.setWaterEnabled(false)
        prefs.setWaterEnabled(true)
        prefs.setQuietHours(0, 0)
        PersonaEventBus.updateHostVisibility(false)
        WaterReminderWorker.cancelAllNotifications(app)
    }

    @Test fun `background worker saves waiting cup before publishing actionable notification`() = runBlocking {
        val worker = TestListenableWorkerBuilder<WaterReminderWorker>(app).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        val pending = prefs.pendingWater.first()
        assertTrue(pending.isPending)
        val restored = UserPrefs(app).pendingWater.first()
        assertEquals(pending, restored)
        val notification = shadowOf(app.getSystemService(NotificationManager::class.java))
            .getNotification(WaterReminderWorker.notificationIdFor(pending.id))
        assertNotNull(notification)
        notification.contentIntent.send()
        val launch = shadowOf(app).nextStartedActivity
        assertEquals(pending.id, launch.getLongExtra(WaterReminderWorker.EXTRA_WATER_REMINDER, 0L))
        assertEquals("Opening the notification is not drinking", pending, prefs.pendingWater.first())
    }

    @Test fun `each reminder family gets its own notification id`() {
        // 复用同一个通知 ID 会让系统把后续提醒当成「同一条通知的更新」而不再响铃弹横幅。
        val ids = (1L..5L).map { WaterReminderWorker.notificationIdFor(it) }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(WaterReminderWorker.notificationIdFor(1L) > 0)
    }

    @Test fun `acknowledging a reminder only cancels its own notification`() = runBlocking {
        val manager = app.getSystemService(NotificationManager::class.java)
        val shadow = shadowOf(manager)
        val compat = androidx.core.app.NotificationManagerCompat.from(app)
        compat.notify(
            WaterReminderWorker.notificationIdFor(1L),
            androidx.core.app.NotificationCompat.Builder(app, WaterReminderWorker.CHANNEL_ID).build()
        )
        compat.notify(
            WaterReminderWorker.notificationIdFor(2L),
            androidx.core.app.NotificationCompat.Builder(app, WaterReminderWorker.CHANNEL_ID).build()
        )
        WaterReminderWorker.cancelNotification(app, 1L)
        assertNull(shadow.getNotification(WaterReminderWorker.notificationIdFor(1L)))
        assertNotNull(shadow.getNotification(WaterReminderWorker.notificationIdFor(2L)))
    }

    @Test fun `repeated reminders retain pending cup and stale completion cannot clear a new one`() = runBlocking {
        val first = prefs.markWaterDue(100L, "喝口水")
        assertEquals(first, prefs.markWaterDue(200L, "第二次提醒"))
        assertFalse(prefs.completeWater(first.id + 1L))
        assertEquals(first, prefs.pendingWater.first())
        assertTrue(prefs.completeWater(first.id))
        assertFalse(prefs.pendingWater.first().isPending)
        val newer = prefs.markWaterDue(50L, "新的水杯")
        assertTrue(newer.id > first.id)
        assertFalse(prefs.completeWater(first.id))
        assertEquals(newer, prefs.pendingWater.first())
    }

    @Test fun `turning reminder off clears waiting state and prevents a racing worker from reviving it`() = runBlocking {
        prefs.markWaterDue(100L, "喝水")
        prefs.setWaterEnabled(false)
        assertFalse(prefs.pendingWater.first().isPending)
        assertFalse(prefs.markWaterDue(200L, "又一杯").isPending)
        assertEquals(ListenableWorker.Result.success(), TestListenableWorkerBuilder<WaterReminderWorker>(app).build().doWork())
        assertNull(shadowOf(app.getSystemService(NotificationManager::class.java))
            .getNotification(WaterReminderWorker.notificationIdFor(1L)))
    }

    /**
     * 这个类刻意不初始化 WorkManager，于是 `WaterReminderScheduler` 一定失败——
     * 正好用来验证「迁移失败绝不写版本号」。
     *
     * 一旦写早了，一次偶然的失败会被永久记成「已迁移」，之后每次打开 App 都不再重试，
     * 提醒就静默地再也不来了，而且从界面上完全看不出问题。
     */
    @Test fun `a migration that cannot reach WorkManager leaves the gate open for the next launch`() = runBlocking {
        prefs.setWaterEnabled(true)
        prefs.setWaterScheduleVersion(0)

        assertFalse(app.container.migrateWaterScheduleIfNeeded())
        assertEquals(0, prefs.waterScheduleVersion.first())
    }
}
