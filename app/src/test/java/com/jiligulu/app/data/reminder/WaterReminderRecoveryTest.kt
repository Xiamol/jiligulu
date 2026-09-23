package com.jiligulu.app.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.NotificationManager
import com.jiligulu.app.JiliguluApp
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = JiliguluApp::class, shadows = [FailingWaterAlarmManager::class])
class WaterReminderRecoveryTest {
    private val app get() = RuntimeEnvironment.getApplication() as JiliguluApp
    private val prefs get() = app.container.userPrefs

    @Before fun reset() = runBlocking {
        FailingWaterAlarmManager.failScheduling = false
        ShadowAlarmManager.setAutoSchedule(false)
        prefs.setWaterEnabled(false)
        prefs.setWaterSettings(enabled = true, intervalMinutes = 1, quietEnabled = false)
    }

    @Test fun `platform scheduling failure still delivers current reminder and next launch repairs the alarm`() = runBlocking {
        val now = System.currentTimeMillis()
        WaterReminderScheduler.schedule(app, 1, now - 60_000L)
        val manager = app.getSystemService(AlarmManager::class.java)
        val old = shadowOf(manager).scheduledAlarms.single()
        manager.cancel(requireNotNull(old.operation)) // The one-shot alarm has been consumed.
        FailingWaterAlarmManager.failScheduling = true
        try {
            assertTrue(WaterReminderEngine.fire(app, now, expectedDueAt = now))
            val pending = prefs.pendingWater.first()
            assertTrue(pending.isPending)
            assertEquals(now + 60_000L, prefs.waterNextDueAt.first())
            assertNotNull(shadowOf(app.getSystemService(NotificationManager::class.java))
                .getNotification(WaterReminderNotifications.notificationIdFor(pending.id)))
            assertFalse(WaterReminderEngine.fire(app, now, expectedDueAt = now))
            assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
        } finally {
            FailingWaterAlarmManager.failScheduling = false
        }
        assertFalse(WaterReminderScheduler.restore(app, now + 1L))
        assertEquals(now + 60_000L, shadowOf(manager).scheduledAlarms.single().triggerAtTime)
    }

    @Test fun `deadline and waiting cup commit as one transition that cannot be replayed`() = runBlocking {
        val now = System.currentTimeMillis()
        WaterReminderScheduler.schedule(app, 1, now - 60_000L)
        val first = requireNotNull(prefs.advanceWaterDeadline(now, now, "喝口水", skipExistingCup = false))
        assertEquals(now + 60_000L, first.nextDueAt)
        assertEquals(first.pendingToDeliver, prefs.pendingWater.first())
        assertEquals(first.nextDueAt, prefs.waterNextDueAt.first())
        assertNull(prefs.advanceWaterDeadline(now, now, "重复广播", skipExistingCup = false))
        assertEquals(first.pendingToDeliver, prefs.pendingWater.first())
    }
}

@Implements(AlarmManager::class)
class FailingWaterAlarmManager : ShadowAlarmManager() {
    companion object {
        @Volatile var failScheduling = false
    }

    @Implementation
    override fun setExactAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) {
        if (failScheduling) throw IllegalStateException("Simulated platform alarm service failure")
        super.setExactAndAllowWhileIdle(type, triggerAtMillis, operation)
    }
}
