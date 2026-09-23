package com.jiligulu.app.data.reminder

import android.app.AlarmManager
import android.util.Log
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
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
import org.robolectric.shadows.ShadowAlarmManager
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = JiliguluApp::class)
class WaterReminderSchedulingTest {
    private val app get() = RuntimeEnvironment.getApplication() as JiliguluApp
    private val prefs get() = app.container.userPrefs
    private val alarmManager get() = app.getSystemService(AlarmManager::class.java)
    private val alarms get() = shadowOf(alarmManager).scheduledAlarms.filter {
        it.operation != null && shadowOf(it.operation).savedIntent.action == WaterReminderScheduler.ACTION_FIRE
    }
    private lateinit var workManager: WorkManager
    private val now = 1_790_000_000_000L

    @Before fun setUp() = runBlocking {
        ShadowAlarmManager.setAutoSchedule(false)
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setMinimumLoggingLevel(Log.ERROR).build())
        workManager = WorkManager.getInstance(app)
        prefs.setWaterEnabled(false)
        prefs.setWaterEnabled(true)
        prefs.setWaterIntervalMinutes(30)
        prefs.setWaterScheduleVersion(0)
        prefs.setQuietHours(0, 0)
    }

    private suspend fun schedule(minutes: Int, at: Long = System.currentTimeMillis()) {
        prefs.setWaterIntervalMinutes(minutes)
        WaterReminderScheduler.schedule(app, minutes, at)
    }
    private fun hasLiveWork(name: String) =
        workManager.getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished }

    private fun seedLegacyWork() {
        workManager.enqueueUniquePeriodicWork(WaterReminderScheduler.LEGACY_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WaterReminderWorker>(30, TimeUnit.MINUTES).build())
        for (name in listOf(WaterReminderScheduler.SLOT_A, WaterReminderScheduler.SLOT_B)) {
            workManager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WaterReminderWorker>().setInitialDelay(30, TimeUnit.MINUTES).build())
        }
    }

    @Test fun `one minute remains one minute and there is only one alarm`() = runBlocking {
        schedule(1, now)
        assertEquals(now + 60_000L, prefs.waterNextDueAt.first())
        assertEquals(now + 60_000L, alarms.single().triggerAtTime)
        assertEquals(0L, alarms.single().interval)
        assertTrue(alarms.single().allowWhileIdle)
        assertEquals(AlarmManager.RTC_WAKEUP, alarms.single().type)
        schedule(120, now)
        assertEquals(now + 120 * 60_000L, alarms.single().triggerAtTime)
        assertFalse(hasLiveWork(WaterReminderScheduler.SLOT_A))
        assertFalse(hasLiveWork(WaterReminderScheduler.SLOT_B))
    }

    @Test fun `restore recreates a lost system alarm without postponing its persisted deadline`() = runBlocking {
        schedule(30, now)
        val due = prefs.waterNextDueAt.first()
        alarmManager.cancel(requireNotNull(alarms.single().operation))
        assertTrue(alarms.isEmpty())
        assertFalse(WaterReminderScheduler.restore(app, now + 10_000L))
        assertEquals(due, alarms.single().triggerAtTime)
        assertEquals(due, prefs.waterNextDueAt.first())
        assertFalse(WaterReminderScheduler.restore(app, now + 20_000L))
        assertEquals(due, alarms.single().triggerAtTime)
    }

    @Test fun `migration cancels all legacy chains once without resetting a current deadline`() = runBlocking {
        seedLegacyWork()
        assertTrue(hasLiveWork(WaterReminderScheduler.LEGACY_PERIODIC_WORK))
        schedule(30)
        val due = prefs.waterNextDueAt.first()
        assertTrue(app.container.migrateWaterScheduleIfNeeded())
        for (name in listOf(WaterReminderScheduler.LEGACY_PERIODIC_WORK,
            WaterReminderScheduler.SLOT_A, WaterReminderScheduler.SLOT_B)) assertFalse(hasLiveWork(name))
        assertEquals(WaterReminderScheduler.SCHEDULE_VERSION, prefs.waterScheduleVersion.first())
        assertEquals(due, prefs.waterNextDueAt.first())
        assertFalse(app.container.migrateWaterScheduleIfNeeded())
        assertEquals(due, alarms.single().triggerAtTime)
    }

    @Test fun `migration while disabled cleans old jobs and creates no alarm`() = runBlocking {
        seedLegacyWork()
        prefs.setWaterEnabled(false)
        assertTrue(app.container.migrateWaterScheduleIfNeeded())
        assertTrue(alarms.isEmpty())
        assertFalse(hasLiveWork(WaterReminderScheduler.LEGACY_PERIODIC_WORK))
        assertFalse(hasLiveWork(WaterReminderScheduler.SLOT_A))
        assertFalse(hasLiveWork(WaterReminderScheduler.SLOT_B))
    }

    @Test fun `old workers have no effects even if one runs during migration`() = runBlocking {
        schedule(1, now)
        val due = prefs.waterNextDueAt.first()
        TestListenableWorkerBuilder<WaterReminderWorker>(app).build().doWork()
        assertEquals(due, prefs.waterNextDueAt.first())
        assertFalse(prefs.pendingWater.first().isPending)
        assertFalse(hasLiveWork(WaterReminderScheduler.SLOT_A))
        assertFalse(hasLiveWork(WaterReminderScheduler.SLOT_B))
    }

    @Test fun `cancelling removes deadline and disabled settings block any late alarm`() = runBlocking {
        schedule(1, now)
        val due = prefs.waterNextDueAt.first()
        prefs.setWaterEnabled(false)
        WaterReminderScheduler.cancel(app)
        assertEquals(0L, prefs.waterNextDueAt.first())
        assertTrue(alarms.isEmpty())
        assertFalse(WaterReminderEngine.fire(app, due + 1L, expectedDueAt = due))
        assertFalse(prefs.pendingWater.first().isPending)
        schedule(1, now)
        assertTrue(alarms.isEmpty())
    }

    @Test fun `an old broadcast cannot fire after user resets the interval`() = runBlocking {
        schedule(1, now)
        val staleDue = prefs.waterNextDueAt.first()
        schedule(30, now)
        assertFalse(WaterReminderEngine.fire(app, now + 60_001L, expectedDueAt = staleDue))
        assertFalse(prefs.pendingWater.first().isPending)
        assertEquals(now + 30 * 60_000L, prefs.waterNextDueAt.first())
    }

    @Test fun `a delayed off handler cannot cancel a newly enabled alarm`() = runBlocking {
        prefs.setWaterEnabled(false)
        prefs.setWaterEnabled(true)
        schedule(30)
        val due = prefs.waterNextDueAt.first()
        WaterReminderScheduler.cancel(app)
        assertEquals(due, prefs.waterNextDueAt.first())
        assertEquals(due, alarms.single().triggerAtTime)
    }

    @Test fun `a stale schedule request uses the newest saved interval`() = runBlocking {
        prefs.setWaterIntervalMinutes(120)
        WaterReminderScheduler.schedule(app, 1, now)
        assertEquals(now + 120 * 60_000L, prefs.waterNextDueAt.first())
        assertEquals(prefs.waterNextDueAt.first(), alarms.single().triggerAtTime)
    }

    @Test fun `duplicate broadcasts advance a deadline only once`() = runBlocking {
        schedule(1, now)
        val due = prefs.waterNextDueAt.first()
        assertTrue(WaterReminderEngine.fire(app, due, expectedDueAt = due))
        val pending = prefs.pendingWater.first()
        assertFalse(WaterReminderEngine.fire(app, due, expectedDueAt = due))
        assertEquals(pending, prefs.pendingWater.first())
        assertEquals(due + 60_000L, prefs.waterNextDueAt.first())
        assertEquals(prefs.waterNextDueAt.first(), alarms.single().triggerAtTime)
    }

    @Test fun `saved interval change restores its new deadline if process exits before rescheduling`() = runBlocking {
        schedule(30)
        val previousDue = prefs.waterNextDueAt.first()
        val beforeSave = System.currentTimeMillis()
        prefs.setWaterSettings(intervalMinutes = 5)
        val updatedDue = prefs.waterNextDueAt.first()
        val afterSave = System.currentTimeMillis()
        assertTrue(updatedDue in (beforeSave + 5 * 60_000L)..(afterSave + 5 * 60_000L))
        assertTrue(updatedDue < previousDue)
        // Android still has the old alarm: the process has not reached the scheduler yet.
        assertEquals(previousDue, alarms.single().triggerAtTime)
        assertFalse(WaterReminderEngine.fire(app, previousDue, expectedDueAt = previousDue))
        assertFalse(WaterReminderScheduler.restore(app, afterSave))
        assertEquals(updatedDue, alarms.single().triggerAtTime)
        assertEquals(updatedDue, prefs.waterNextDueAt.first())
        assertFalse(prefs.pendingWater.first().isPending)
    }

    @Test fun `replaying saved settings or changing only quiet hours preserves existing deadline`() = runBlocking {
        schedule(30, now)
        val due = prefs.waterNextDueAt.first()
        prefs.setWaterSettings(enabled = true, intervalMinutes = 30)
        assertEquals(due, prefs.waterNextDueAt.first())
        prefs.setWaterSettings(quietEnabled = true, quietStartMinutes = 22 * 60, quietEndMinutes = 8 * 60)
        assertEquals(due, prefs.waterNextDueAt.first())
    }

    @Test fun `old system alarm repairs an interrupted reschedule using only the new persisted deadline`() = runBlocking {
        schedule(30)
        val oldDue = prefs.waterNextDueAt.first()
        prefs.setWaterSettings(intervalMinutes = 5)
        val newDue = prefs.waterNextDueAt.first()
        // The OS still delivers the old 30-minute alarm after the new 5-minute deadline was missed.
        assertTrue(WaterReminderEngine.onAlarm(app, expectedDueAt = oldDue, now = oldDue))
        assertTrue(prefs.pendingWater.first().isPending)
        assertEquals(oldDue + 5 * 60_000L, prefs.waterNextDueAt.first())
        assertNotEquals(newDue, prefs.waterNextDueAt.first())
        assertEquals(prefs.waterNextDueAt.first(), alarms.single().triggerAtTime)
        assertFalse(WaterReminderEngine.onAlarm(app, expectedDueAt = oldDue, now = oldDue))
    }

    @Test fun `enabling persists a recoverable deadline before any scheduler call`() = runBlocking {
        prefs.setWaterEnabled(false)
        prefs.setWaterSettings(intervalMinutes = 10)
        assertEquals(0L, prefs.waterNextDueAt.first())
        val beforeSave = System.currentTimeMillis()
        prefs.setWaterEnabled(true)
        val due = prefs.waterNextDueAt.first()
        val afterSave = System.currentTimeMillis()
        assertTrue(due in (beforeSave + 10 * 60_000L)..(afterSave + 10 * 60_000L))
        assertFalse(WaterReminderScheduler.restore(app, afterSave))
        assertEquals(due, alarms.single().triggerAtTime)
    }

    @Test @Config(sdk = [35])
    fun `exact permission denial falls back to the same inexact alarm and grant upgrades it`() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(WaterReminderScheduler.exactAllowed(app))
        schedule(1, now)
        assertEquals(ShadowAlarmManager.WINDOW_HEURISTIC, alarms.single().windowLengthMs)
        assertTrue(alarms.single().allowWhileIdle)
        val operation = alarms.single().operation
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(WaterReminderScheduler.exactAllowed(app))
        WaterReminderScheduler.restore(app, now)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarms.single().windowLengthMs)
        assertEquals(operation, alarms.single().operation)
        assertEquals(now + 60_000L, alarms.single().triggerAtTime)
    }
}
