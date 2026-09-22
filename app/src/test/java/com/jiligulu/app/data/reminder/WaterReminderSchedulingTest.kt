package com.jiligulu.app.data.reminder

import android.util.Log
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import com.jiligulu.app.JiliguluApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * 喝水提醒的调度机制。
 *
 * 这一层存在的唯一理由：WorkManager 对 `PeriodicWorkRequest` 有 15 分钟硬下限，
 * 用户设的间隔却可以短到 1 分钟。于是改成「一次性任务 + 跑完自己排下一次」，
 * 并用两个槽位交替，避免新任务把正在运行的自己取消掉。
 *
 * 这些测试锁的就是三件事：链不会断、不会变双份、升级残留会被清掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = JiliguluApp::class)
class WaterReminderSchedulingTest {
    private val app get() = RuntimeEnvironment.getApplication() as JiliguluApp
    private val prefs get() = app.container.userPrefs
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setMinimumLoggingLevel(Log.DEBUG)
                .build()
        )
        workManager = WorkManager.getInstance(app)
        // 迁移闸门会被复用，逐条用例都从「从未迁移过」开始。
        prefs.setWaterScheduleVersion(0)
    }

    private fun infos(slot: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(slot).get()

    private fun hasLiveWork(slot: String): Boolean =
        infos(slot).any { !it.state.isFinished }

    /** 模拟 0.5.4 留在设备上的周期任务。 */
    private fun seedLegacyPeriodicWork() {
        workManager.enqueueUniquePeriodicWork(
            WaterReminderScheduler.LEGACY_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WaterReminderWorker>(30, TimeUnit.MINUTES).build()
        )
    }

    @Test
    fun `the periodicity probe can actually tell periodic work apart`() {
        // 上面那条「必须排成一次性任务」依赖 periodicityInfo 判空。
        // 如果这个字段在该环境下恒为 null，那条断言就是假绿——这里用真的周期任务反证探针有效。
        seedLegacyPeriodicWork()
        val legacy = infos(WaterReminderScheduler.LEGACY_PERIODIC_WORK).single()
        assertNotNull(
            "周期任务的 periodicityInfo 必须非 null，否则上面那条断言测不出任何东西",
            legacy.periodicityInfo
        )
    }

    @Test
    fun `the scheduled reminder is a one-shot task so short intervals are not raised to 15 minutes`() {
        WaterReminderScheduler.schedule(app, 1)
        val info = infos(WaterReminderScheduler.SLOT_A).single()
        // periodicityInfo 非 null 就等于「这是周期任务」，间隔会被静默提升到 15 分钟。
        assertNull(
            "必须排成一次性任务，否则 WorkManager 会把 1 分钟静默改成 15 分钟",
            info.periodicityInfo
        )
    }

    @Test
    fun `finishing a reminder queues the next one in the other slot`() = runBlocking {
        prefs.setWaterEnabled(true)
        val worker = TestListenableWorkerBuilder<WaterReminderWorker>(app)
            .setInputData(workDataOf(WaterReminderScheduler.KEY_SLOT to WaterReminderScheduler.SLOT_A))
            .build()
        worker.doWork()

        // 链的核心：A 干完必须把 B 排上。少这一下提醒就永久停摆，而且 WorkManager 不会报错。
        assertTrue("跑完一次就该把下一次排出去", infos(WaterReminderScheduler.SLOT_B).isNotEmpty())
    }

    @Test
    fun `a worker that is switched off does not keep the chain alive`() = runBlocking {
        prefs.setWaterEnabled(false)
        val worker = TestListenableWorkerBuilder<WaterReminderWorker>(app)
            .setInputData(workDataOf(WaterReminderScheduler.KEY_SLOT to WaterReminderScheduler.SLOT_A))
            .build()
        worker.doWork()

        assertTrue("关掉提醒后不该再排下一次", infos(WaterReminderScheduler.SLOT_B).isEmpty())
    }

    @Test
    fun `scheduling clears the periodic task left behind by an older version`() {
        seedLegacyPeriodicWork()
        assertTrue(hasLiveWork(WaterReminderScheduler.LEGACY_PERIODIC_WORK))

        WaterReminderScheduler.schedule(app, 30)

        // 不清掉的话，旧周期任务（15 分钟）会和新链（用户设的间隔）并行，提醒直接翻倍。
        assertFalse(
            "0.5.4 遗留的周期任务必须被清掉",
            hasLiveWork(WaterReminderScheduler.LEGACY_PERIODIC_WORK)
        )
        assertTrue(hasLiveWork(WaterReminderScheduler.SLOT_A))
    }

    @Test
    fun `rescheduling also clears the other slot so two chains never run at once`() {
        WaterReminderScheduler.schedule(app, 30)
        WaterReminderScheduler.enqueueNext(app, WaterReminderScheduler.SLOT_A, 30)
        assertTrue(hasLiveWork(WaterReminderScheduler.SLOT_B))

        WaterReminderScheduler.schedule(app, 30)

        assertFalse("重排必须把另一条链收掉", hasLiveWork(WaterReminderScheduler.SLOT_B))
        assertTrue(hasLiveWork(WaterReminderScheduler.SLOT_A))
    }

    @Test
    fun `cancelling stops every slot and the legacy task`() {
        WaterReminderScheduler.schedule(app, 30)
        WaterReminderScheduler.enqueueNext(app, WaterReminderScheduler.SLOT_A, 30)
        seedLegacyPeriodicWork()

        WaterReminderScheduler.cancel(app)

        assertFalse(hasLiveWork(WaterReminderScheduler.SLOT_A))
        assertFalse(hasLiveWork(WaterReminderScheduler.SLOT_B))
        assertFalse(hasLiveWork(WaterReminderScheduler.LEGACY_PERIODIC_WORK))
    }

    @Test
    fun `migration schedules the chain once and then stays out of the way`() = runBlocking {
        prefs.setWaterEnabled(true)
        prefs.setWaterIntervalMinutes(30)

        assertTrue(app.container.migrateWaterScheduleIfNeeded())
        assertEquals(
            WaterReminderScheduler.SCHEDULE_VERSION,
            prefs.waterScheduleVersion.first()
        )
        val firstQueue = infos(WaterReminderScheduler.SLOT_A).map { it.id }
        assertTrue(firstQueue.isNotEmpty())

        // 关键：第二次不能重排。重排会重置 setInitialDelay 的倒计时，
        // 用户只要开 App 比提醒间隔勤快，提醒就永远等不到。
        assertFalse(app.container.migrateWaterScheduleIfNeeded())
        assertEquals("闸门生效时不该动队列", firstQueue, infos(WaterReminderScheduler.SLOT_A).map { it.id })
    }

    @Test
    fun `migration with the reminder off only cleans up and never schedules`() = runBlocking {
        prefs.setWaterEnabled(false)
        seedLegacyPeriodicWork()

        assertTrue(app.container.migrateWaterScheduleIfNeeded())

        // 开关关着也要清：旧周期任务不会因为开关是关的就自己消失。
        assertFalse(hasLiveWork(WaterReminderScheduler.LEGACY_PERIODIC_WORK))
        assertTrue("没开提醒就不该排链", infos(WaterReminderScheduler.SLOT_A).isEmpty())
    }

    @Test
    fun `interval changes made by the user are picked up by the next hop`() = runBlocking {
        prefs.setWaterEnabled(true)
        // 排 A，间隔 1 分钟；用户随后改成 120 分钟。
        WaterReminderScheduler.schedule(app, 1)
        prefs.setWaterIntervalMinutes(120)

        val worker = TestListenableWorkerBuilder<WaterReminderWorker>(app)
            .setInputData(workDataOf(WaterReminderScheduler.KEY_SLOT to WaterReminderScheduler.SLOT_A))
            .build()
        worker.doWork()

        // 间隔是下一跳现读的，所以改完设置不用等这一轮跑完就生效。
        assertTrue(infos(WaterReminderScheduler.SLOT_B).isNotEmpty())
        assertEquals(120, prefs.waterIntervalMinutes.first())
    }
}
