package com.jiligulu.app

import android.app.Application
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.data.reminder.WaterReminderScheduler
import com.jiligulu.app.data.reminder.WaterReminderWorker
import com.jiligulu.app.data.repository.AiRepository
import com.jiligulu.app.data.repository.BillRepository
import com.jiligulu.app.data.repository.BudgetRepository
import com.jiligulu.app.data.repository.CategoryAdminRepository
import com.jiligulu.app.data.repository.CategoryRepository
import com.jiligulu.app.data.repository.ChatHistoryRepository
import com.jiligulu.app.data.update.ReleaseUpdateRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/**
 * 手动 DI 容器：M1 规模用不上 Hilt，一个 AppContainer 就够；
 * 后续模块（人格引擎、悬浮窗服务）都往这里挂，保证可升级。
 */
class AppContainer(private val app: Application) {
    private val database: AppDatabase by lazy { AppDatabase.build(app) }

    val userPrefs: UserPrefs by lazy { UserPrefs(app) }
    val updates: ReleaseUpdateRepository by lazy { ReleaseUpdateRepository(userPrefs) }

    val billRepository: BillRepository by lazy { BillRepository(database.billDao()) }
    val categoryRepository: CategoryRepository by lazy { CategoryRepository(database.categoryDao()) }
    val categoryAdminRepository: CategoryAdminRepository by lazy { CategoryAdminRepository(database) }
    val chatHistoryRepository: ChatHistoryRepository by lazy { ChatHistoryRepository(database) }
    val aiRepository: AiRepository by lazy {
        AiRepository(app, categoryRepository, billRepository, userPrefs, chatHistoryRepository)
    }

    val budgetRepository: BudgetRepository by lazy {
        BudgetRepository(database.budgetDao(), database.billDao())
    }

    /**
     * 本进程是否已完成过一次完整启动（数据预载 + 入场动画）。
     * Activity 重建（旋转/切窗口/内存回收）不重置，进程死亡才重置。
     * 避免「切窗口再返回」时重复播放启动动画。
     */
    @Volatile
    var startupCompleted: Boolean = false

    suspend fun preloadLedger() = coroutineScope {
        awaitAll(
            async { billRepository.observeCurrentMonth().first() },
            async { categoryRepository.categories.first() },
            async { budgetRepository.observeStatus().first() }
        )
    }

    /**
     * 一次性迁移：把喝水提醒从「周期任务」换成「OneTimeWork 自链」。
     *
     * 必须只在升级后的第一次启动跑一次，不能每次冷启动都排：
     * 重排会重置 `setInitialDelay` 的倒计时，用户只要开 App 比提醒间隔勤快，
     * 提醒就永远等不到。所以用 [UserPrefs.waterScheduleVersion] 做闸门，
     * 迁移完写上新版本号，之后每次启动读完这个值就直接返回。
     *
     * 关着开关时也要清一次——旧的周期任务不会因为开关是关的就自己消失，
     * 它还在 WorkManager 队列里躺着被触发。
     *
     * 全程吞异常：这是启动关键路径上的一次「顺手收拾」，提醒排不上是小事，
     * 进不去 App 是大事。
     *
     * @return 是否真的做了迁移（未迁移 = 闸门已过或失败，供测试断言用）
     */
    suspend fun migrateWaterScheduleIfNeeded(): Boolean = runCatching {
        if (userPrefs.waterScheduleVersion.first() >= WaterReminderScheduler.SCHEDULE_VERSION) {
            return@runCatching false
        }
        val enabled = userPrefs.waterEnabled.first()
        val minutes = userPrefs.waterIntervalMinutes.first()
        if (enabled) WaterReminderScheduler.schedule(app, minutes) else WaterReminderScheduler.cancel(app)
        // 版本号只在排成之后写。写早了会把一次「排失败」永久记成「已迁移」，
        // 用户之后每次打开 App 都不会再重试，提醒就静默地再也不来了。
        userPrefs.setWaterScheduleVersion(WaterReminderScheduler.SCHEDULE_VERSION)
        true
    }.getOrDefault(false)
}

class JiliguluApp : Application() {
    lateinit var container: AppContainer
        private set

    /** App 是否在前台（喝水提醒：前台飘气泡，后台发通知） */
    @Volatile
    var isForeground: Boolean = false
        private set

    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        WaterReminderWorker.createChannel(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                startedActivities++
                isForeground = true
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) isForeground = false
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }
}
