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
        WaterReminderWorker.cancelNotification(app)
    }

    @Test fun `background worker saves waiting cup before publishing actionable notification`() = runBlocking {
        val worker = TestListenableWorkerBuilder<WaterReminderWorker>(app).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        val pending = prefs.pendingWater.first()
        assertTrue(pending.isPending)
        val restored = UserPrefs(app).pendingWater.first()
        assertEquals(pending, restored)
        val notification = shadowOf(app.getSystemService(NotificationManager::class.java))
            .getNotification(WaterReminderWorker.NOTIFICATION_ID)
        assertNotNull(notification)
        notification.contentIntent.send()
        val launch = shadowOf(app).nextStartedActivity
        assertEquals(pending.id, launch.getLongExtra(WaterReminderWorker.EXTRA_WATER_REMINDER, 0L))
        assertEquals("Opening the notification is not drinking", pending, prefs.pendingWater.first())
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
            .getNotification(WaterReminderWorker.NOTIFICATION_ID))
    }
}
