package com.jiligulu.app

import android.app.Application
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.data.reminder.WaterReminderScheduler
import com.jiligulu.app.data.reminder.WaterReminderNotifications
import com.jiligulu.app.data.repository.AiRepository
import com.jiligulu.app.data.repository.BillRepository
import com.jiligulu.app.data.repository.BudgetRepository
import com.jiligulu.app.data.repository.CategoryAdminRepository
import com.jiligulu.app.data.repository.CategoryRepository
import com.jiligulu.app.data.repository.ChatHistoryRepository
import com.jiligulu.app.data.update.ReleaseUpdateRepository
import com.jiligulu.app.data.announcement.AnnouncementRepository
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
    val announcements: AnnouncementRepository by lazy { AnnouncementRepository(userPrefs) }

    val billRepository: BillRepository by lazy { BillRepository(database.billDao()) }
    val categoryRepository: CategoryRepository by lazy { CategoryRepository(database.categoryDao()) }
    val categoryAdminRepository: CategoryAdminRepository by lazy { CategoryAdminRepository(database) }
    val chatHistoryRepository: ChatHistoryRepository by lazy { ChatHistoryRepository(database) }
    val aiRepository: AiRepository by lazy {
        AiRepository(
            app, categoryRepository, billRepository, userPrefs,
            chatHistoryRepository, categoryAdminRepository
        )
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

    /** Cancel legacy WorkManager chains once; normal cold starts preserve the existing deadline. */
    suspend fun migrateWaterScheduleIfNeeded(): Boolean = try {
        if (userPrefs.waterScheduleVersion.first() >= WaterReminderScheduler.SCHEDULE_VERSION) {
            false
        } else {
            WaterReminderScheduler.cancelLegacyWork(app)
            WaterReminderScheduler.restore(app)
            userPrefs.setWaterScheduleVersion(WaterReminderScheduler.SCHEDULE_VERSION)
            true
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false // Keep the gate open so a later launch can retry legacy cleanup.
    }

    /** Alarm permission revocation and force-stop can remove system alarms; recover the saved due time. */
    suspend fun catchUpWaterReminder(): Boolean = try {
        WaterReminderScheduler.restore(app)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false // Reminders are optional startup work, never a reason to block access to the ledger.
    }
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
        // Retired offline voice trial: remove only its private model directory.
        Thread({
            runCatching {
                val root = filesDir.canonicalFile
                val retired = java.io.File(root, "offline-voice").canonicalFile
                if (retired.parentFile == root && retired.name == "offline-voice") {
                    retired.deleteRecursively()
                }
            }
        }, "RetiredVoiceCleanup").start()
        WaterReminderNotifications.createChannel(this)
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
