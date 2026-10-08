package com.jiligulu.app.ui

import android.graphics.Bitmap
import android.content.Intent
import androidx.activity.compose.setContent
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.MotionEvent
import android.os.SystemClock
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTouchInput
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.After
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.jiligulu.app.data.reminder.WaterReminderNotifications
import com.jiligulu.app.ui.startup.StartupScreen
import com.jiligulu.app.ui.theme.GuluTheme
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.MainActivity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.ui.chat.DraftHistoryCodec
import com.jiligulu.app.ui.chat.DraftUi
import com.jiligulu.app.ui.chat.ChatScreen
import com.jiligulu.app.ui.chat.ChatViewModel
import com.jiligulu.app.ui.chat.ChatItem
import com.jiligulu.app.data.repository.AiRepository
import com.jiligulu.app.core.ai.DeepSeekClient
import com.jiligulu.app.domain.persona.PersonaEngine
import com.jiligulu.app.domain.persona.QuipLibrary
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import com.jiligulu.app.core.ai.AiAppAction
import com.jiligulu.app.ui.chat.AppActionCodec
import com.jiligulu.app.ui.chat.AppActionPayload
import com.jiligulu.app.data.prefs.UserPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** Actual Compose screens under Robolectric; screenshots are layout checks, not device captures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = JiliguluApp::class, qualifiers = "w411dp-h891dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class UiSmokeScreenshotTest {
    // Effects and frames share an owned scheduler. The default unconfined dispatcher may resume
    // a DataStore completion on its I/O thread while the initial composition is still applying.
    private val effectScheduler = TestCoroutineScheduler()
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher(effectScheduler))
    private lateinit var activity: MainActivity
    private val boundActivities = mutableSetOf<MainActivity>()
    private val fixtureViewModelJobs = mutableSetOf<Job>()

    private fun rememberFixtureViewModel(model: ViewModel) {
        model.viewModelScope.coroutineContext[Job]?.let { fixtureViewModelJobs += it }
    }

    private fun rememberFixtureStore(store: ViewModelStore, seen: MutableSet<ViewModelStore> = mutableSetOf()) {
        if (!seen.add(store)) return
        store.keys().forEach { key ->
            store[key]?.let { model ->
                rememberFixtureViewModel(model)
                // Navigation 2.8.5 keeps destination stores inside this internal owner. Those
                // query jobs must finish too; inspecting ownership does not alter their data.
                if (model.javaClass.name == "androidx.navigation.NavControllerViewModel") {
                    val stores = model.javaClass.getDeclaredField("viewModelStores").apply { isAccessible = true }
                        .get(model) as Map<*, *>
                    stores.values.filterIsInstance<ViewModelStore>().forEach { rememberFixtureStore(it, seen) }
                }
            }
        }
    }

    private fun rememberLiveActivityModels() {
        if (::activity.isInitialized && !activity.isDestroyed) rememberFixtureStore(activity.viewModelStore)
    }

    private fun bindActivity(bound: MainActivity) {
        activity = bound
        if (boundActivities.add(bound)) {
            // Destroy is dispatched in reverse observer order; remember the owners before
            // ComponentActivity clears its store. Stop also covers recreation/early teardown.
            bound.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                    rememberFixtureStore(bound.viewModelStore)
                }
            })
        }
    }

    private fun pumpFixtureCancellation() {
        effectScheduler.runCurrent()
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
    }

    @After
    fun releaseFixtureAndFlushSnapshotNotifications() {
        compose.mainClock.autoAdvance = true
        if (::activity.isInitialized && !activity.isDestroyed) {
            // Cleanup must not wait for a hierarchy whose failed held gesture prevented idleness.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity.setContent {}
                rememberFixtureStore(activity.viewModelStore)
                activity.viewModelStore.clear()
            }
        }
        Snapshot.sendApplyNotifications()
        effectScheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        // ViewModelStore.clear requests cancellation but Room's blocking cursor query may still
        // be unwinding on arch_disk_io. Closing/resetting SQLite before that completion races it.
        while (fixtureViewModelJobs.any { !it.isCompleted } && System.nanoTime() < deadline) {
            pumpFixtureCancellation()
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
        assertTrue("Fixture ViewModel cancellation exceeded 10 seconds", fixtureViewModelJobs.all { it.isCompleted })
        val container = (RuntimeEnvironment.getApplication() as JiliguluApp).container
        // Room/SQLite opening and closing take locks; neither belongs on SDK Main. Keep Main
        // available to finish disposal/cancellation, and fail boundedly if fixture I/O stalls.
        val close = FutureTask<Unit> { container.closeLedgerForTests() }
        val worker = Thread(close, "UiSmoke-ledger-close").apply { isDaemon = true }
        worker.start()
        while (!close.isDone && System.nanoTime() < deadline) {
            pumpFixtureCancellation()
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
        if (!close.isDone) {
            val stack = worker.stackTrace.joinToString("\n") { it.toString() }
            close.cancel(true)
            throw AssertionError("Ledger fixture close exceeded 10 seconds\n$stack")
        }
        close.get()
        Snapshot.sendApplyNotifications()
    }

    private fun whileFingerHeld(tag: String, gesture: TouchInjectionScope.() -> Unit, inspect: () -> Unit) {
        var began = false
        var failure: Throwable? = null
        try {
            compose.onNodeWithTag(tag).performTouchInput { began = true; gesture() }
            inspect()
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            if (began) try { compose.onNodeWithTag(tag).performTouchInput { up() } }
            catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private fun visibleText(text: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        val nodes = compose.onAllNodesWithText(text)
        val visible = nodes.fetchSemanticsNodes().indices.filter { nodes[it].isDisplayed() }
        assertEquals("Exactly one visible '$text' control is expected", 1, visible.size)
        return nodes[visible.single()]
    }

    private fun visibleClickableText(text: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        val nodes = compose.onAllNodes(hasText(text) and hasClickAction())
        // HorizontalPager may still keep the previous page's plain category text composed.
        // Wait for the actual target control, rather than treating that text as loaded stats.
        try {
            compose.waitUntil(8_000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                nodes.fetchSemanticsNodes().indices.count { nodes[it].isDisplayed() } == 1
            }
        } catch (failure: Throwable) {
            val tree = runCatching { allRootsSemantics() }.getOrElse { "Semantics unavailable: $it" }
            throw AssertionError("Waiting for one visible clickable '$text'\n$tree", failure)
        }
        val visible = nodes.fetchSemanticsNodes().indices.filter { nodes[it].isDisplayed() }
        assertEquals("Exactly one visible clickable '$text' is expected", 1, visible.size)
        return nodes[visible.single()]
    }

    private fun visibleDescription(description: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        val nodes = compose.onAllNodesWithContentDescription(description)
        // A scene title can appear before its decoded artwork exposes the real hit target.
        try {
            compose.waitUntil(8_000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                nodes.fetchSemanticsNodes().indices.count { nodes[it].isDisplayed() } == 1
            }
        } catch (failure: Throwable) {
            val tree = runCatching { allRootsSemantics() }.getOrElse { "Semantics unavailable: $it" }
            throw AssertionError("Waiting for one visible '$description' control\n$tree", failure)
        }
        val visible = nodes.fetchSemanticsNodes().indices.filter { nodes[it].isDisplayed() }
        assertEquals("Exactly one visible '$description' control is expected", 1, visible.size)
        return nodes[visible.single()]
    }

    private fun visibleClickLabel(label: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        val nodes = compose.onAllNodes(SemanticsMatcher("OnClick label '$label'") { node ->
            node.config.contains(SemanticsActions.OnClick) && node.config[SemanticsActions.OnClick].label == label
        })
        try {
            compose.waitUntil(8_000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                nodes.fetchSemanticsNodes().indices.count { nodes[it].isDisplayed() } == 1
            }
        } catch (failure: Throwable) {
            val tree = runCatching { allRootsSemantics() }.getOrElse { "Semantics unavailable: $it" }
            throw AssertionError("Waiting for one visible '$label' action\n$tree", failure)
        }
        val visible = nodes.fetchSemanticsNodes().indices.filter { nodes[it].isDisplayed() }
        assertEquals("Exactly one visible '$label' action is expected", 1, visible.size)
        return nodes[visible.single()]
    }

    private fun allRootsSemantics(): String {
        val roots = compose.onAllNodes(isRoot())
        return roots.fetchSemanticsNodes().indices.joinToString("\n\n") { index -> roots[index].printToString() }
    }

    private fun currentStatisticsList(): androidx.compose.ui.test.SemanticsNodeInteraction {
        val node = compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("每日收支"))).fetchSemanticsNode()
        return compose.onNode(SemanticsMatcher("statistics list with stable id ${node.id}") { it.id == node.id })
    }

    @Test(timeout = 120_000)
    fun homeDetailsStatisticsAndSettingsRenderInBothThemes() {
        val app = RuntimeEnvironment.getApplication() as JiliguluApp
        runBlocking {
            val container = app.container
            container.userPrefs.setNickname("路陌")
            container.userPrefs.setThemeMode(UserPrefs.THEME_LIGHT)
            container.userPrefs.setWaterEnabled(false)
            container.userPrefs.setUpdateRepository("")
            container.userPrefs.setAnnouncementSource("")
            container.announcements.initialize()
            val food = container.categoryRepository.getAll().first { it.name == "吃饭" }.id
            val drinks = container.categoryRepository.getAll().first { it.name == "饮品" }.id
            val travel = container.categoryRepository.createCategory("交通", iconValue = "🚇")
            val salary = container.categoryRepository.createCategory("工资", iconValue = "💌")
            val now = System.currentTimeMillis()
            container.billRepository.addManual(1200, BillType.EXPENSE, food, "牛肉面", "午餐 · 公司附近", now)
            container.billRepository.addManual(1800, BillType.EXPENSE, drinks, "奶茶", "下午的一点甜", now - 60_000)
            container.billRepository.addManual(600, BillType.EXPENSE, travel, "地铁", "下班回家", now - 120_000)
            container.billRepository.addManual(500000, BillType.INCOME, salary, "工资", "这个月也辛苦啦", now - 180_000)
            container.chatHistoryRepository.insert(ChatMessageEntity(kind = "USER",
                content = "历史记录验收", createdAt = now))
            container.chatHistoryRepository.insert(ChatMessageEntity(kind = "ASSISTANT",
                content = "小小的账本，也装得下大大的生活。阿噜 ♡", createdAt = now + 1))
            // The test starts ActivityScenario only after both seeded flows have emitted.
            assertEquals("路陌", withTimeout(5_000) { container.userPrefs.nickname.first() })
            assertEquals(UserPrefs.THEME_LIGHT, withTimeout(5_000) { container.userPrefs.themeMode.first() })
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { bindActivity(it) }
            awaitText("账本")
            awaitText("牛肉面")
            visibleText("叽里咕噜").assertIsDisplayed()
            compose.onNodeWithText("统计").assertIsDisplayed()
            visibleDescription("设置").assertIsDisplayed()
            val lightBackground = capture("home-light")
            captureLauncherIcon()

            compose.onNodeWithText("记一笔").performClick()
            awaitText("确认记账")
            capture("manual-entry-light")
            compose.onNodeWithTag("manual-date").assertIsDisplayed()
            compose.onNodeWithTag("manual-time").assertIsDisplayed()
            compose.onNodeWithTag("manual-photo-picker").assertIsDisplayed()
            compose.onNodeWithContentDescription("打开阿噜小算盘").assertIsDisplayed()
            capture("manual-time-light")
            compose.onNodeWithContentDescription("返回").performClick()
            awaitText("对话记账")

            compose.onNodeWithText("对话记账").performClick()
            awaitText("历史记录验收")
            compose.onNodeWithText("小小的账本，也装得下大大的生活。阿噜 ♡").assertIsDisplayed()
            capture("chat-light")
            compose.onNodeWithContentDescription("返回").performClick()
            awaitText("对话记账")
            compose.onNodeWithText("对话记账").performClick()
            awaitText("历史记录验收")
            compose.onNodeWithText("小小的账本，也装得下大大的生活。阿噜 ♡").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick()
            awaitText("账本")

            openSampleBill()
            awaitText("保存修改")
            compose.onNodeWithContentDescription("细则").assertIsDisplayed()
            capture("bill-detail-light", dialog = true)
            dismissTopDialogOutside("bill details from ledger")
            compose.onAllNodesWithText("保存修改").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }

            compose.onNodeWithText("统计").performClick()
            awaitText("每日收支")
            val statisticsList = currentStatisticsList()
            statisticsList.performScrollToIndex(2)
            awaitText("吃饭")
            visibleClickableText("吃饭").performClick()
            visibleClickableText("吃饭").performClick()
            awaitText("吃饭的小账单")
            visibleClickableText("牛肉面").performClick()
            awaitText("保存修改")
            dismissTopDialogOutside("bill details from category")
            compose.onAllNodesWithText("保存修改").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
            val remainingCategoryDialog = compose.onAllNodes(hasText("吃饭的小账单") and hasAnyAncestor(isDialog()))
            if (remainingCategoryDialog.fetchSemanticsNodes().isNotEmpty()) dismissTopDialogOutside("category bills")
            compose.onAllNodes(hasText("吃饭的小账单") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
            statisticsList.performScrollToIndex(0)
            awaitText("每日收支")
            compose.onAllNodesWithText("保存修改").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
            capture("statistics-light")

            visibleDescription("设置").performClick()
            awaitText("主题与皮肤")
            capture("settings-light")
            compose.onNodeWithTag("settings-tabs").performScrollToIndex(4)
            compose.onNodeWithTag("settings-tab-关于").performClick()
            awaitSettingsPage("关于", "制作人")
            scrollSettingsTo("阿噜使用手册")
            compose.onNodeWithText("阿噜使用手册").performClick()
            awaitText("阿噜使用手册 ♡")
            awaitText("见面啦，我是阿噜")
            capture("handbook-light", dialog = true)
            dismissTopDialogOutside("handbook")
            compose.onNodeWithText("阿噜使用手册 ♡").assertDoesNotExist()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("账本").performClick()
            compose.onNodeWithTag("home-outer").performScrollToIndex(0)

            runBlocking {
                app.container.userPrefs.setThemeMode(UserPrefs.THEME_DARK)
                app.container.userPrefs.themeMode.first { it == UserPrefs.THEME_DARK }
            }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(500)
            val darkBackground = capture("home-dark")
            assertNotEquals("Theme preference must change the rendered background", lightBackground, darkBackground)
            visibleDescription("设置").performClick()
            awaitText("主题与皮肤")
            capture("settings-dark")
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("账本").performClick()
            compose.onNodeWithTag("home-outer").performScrollToIndex(0)
            awaitText("对话记账")
            compose.onNodeWithText("对话记账").performClick()
            awaitText("历史记录验收")
            capture("chat-dark")
            compose.onNodeWithContentDescription("返回").performClick()
            awaitText("账本")
            visibleDescription("设置").performClick()
            awaitText("主题与皮肤")
            val cup = runBlocking {
                app.container.userPrefs.setWaterEnabled(true)
                app.container.userPrefs.markWaterDue(System.currentTimeMillis(), "水杯准备好啦，一起喝一口，阿噜！")
            }
            compose.runOnIdle {
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity,
                    // Preserve ActivityScenario's tracking marker while supplying the real notification extra.
                    Intent(activity.intent).putExtra(WaterReminderNotifications.EXTRA_WATER_REMINDER, cup.id))
            }
            awaitWaterCup()
            capture("water-waiting")
            compose.mainClock.advanceTimeBy(30_000)
            awaitWaterCup()
            assertEquals(cup.id, runBlocking { app.container.userPrefs.pendingWater.first().id })
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription(WAITING_CUP).performClick()
            compose.mainClock.advanceTimeBy(800)
            capture("water-drinking")
            compose.onNodeWithText("先等等").performClick()
            compose.mainClock.advanceTimeBy(200)
            compose.mainClock.autoAdvance = true
            awaitWaterCup()
            assertEquals("Cancelling the animation must retain the cup", cup.id,
                runBlocking { app.container.userPrefs.pendingWater.first().id })
            compose.onNodeWithContentDescription(WAITING_CUP).performClick()
            compose.mainClock.advanceTimeBy(4_000)
            compose.waitUntil(15_000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                compose.onAllNodesWithTag("drinking-animation").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithContentDescription(WAITING_CUP).fetchSemanticsNodes().isEmpty()
            }
            assertTrue(runBlocking { !app.container.userPrefs.pendingWater.first().isPending })
            capture("water-completed")
            visibleDescription("设置").performClick()
            awaitText("主题与皮肤")
            compose.onNodeWithTag("settings-tabs").performScrollToIndex(4)
            compose.onNodeWithTag("settings-tab-关于").performClick()
            awaitSettingsPage("关于", "制作人")
            scrollSettingsTo("检查更新")
            compose.waitForIdle()
            // 内置默认更新源后，检查更新按钮应始终可点击（不再依赖手动配置仓库）
            compose.onNodeWithText("检查更新").assertIsEnabled()
            capture("update-settings")

            compose.onNodeWithTag("settings-tabs").performScrollToIndex(3)
            compose.onNodeWithTag("settings-tab-数据").performClick()
            awaitSettingsPage("数据", "AI 服务")
            scrollSettingsTo("历史对话")
            val history = app.container.chatHistoryRepository
            val keptBills = runBlocking { app.container.billRepository.recent(30) }
            compose.onNodeWithTag("clear-history-entry").performClick()
            awaitText("给聊天腾个小空位？")
            capture("clear-history-dialog", dialog = true)
            compose.onNodeWithText("先留着").performClick()
            assertTrue(runBlocking { history.getAll().any { it.content == "历史记录验收" } })
            compose.onNodeWithTag("clear-history-entry").performClick()
            compose.onNodeWithText("清空对话").performClick()
            awaitText("聊天已清空，账单和未入账草稿都还在。")
            runBlocking {
                assertEquals(keptBills, app.container.billRepository.recent(30))
                assertTrue(history.getAll().none { it.content == "历史记录验收" })
            }
            compose.onNodeWithContentDescription("返回").performClick()
            awaitText("对话记账")
            compose.onNodeWithText("对话记账").performClick()
            awaitText("今天花了什么？补记也可以说", substring = true)
            compose.onNodeWithText("历史记录验收").assertDoesNotExist()
            compose.onNodeWithText("小小的账本，也装得下大大的生活。阿噜 ♡").assertDoesNotExist()

            // Render the real entry component separately; coordinator timing and lifecycle leases
            // are covered by StartupViewModelTest without ActivityScenario's paused-loop deadlock.
            compose.runOnIdle {
                activity.setContent { GuluTheme(darkTheme = false) { StartupScreen(null, {}) } }
            }
            compose.mainClock.advanceTimeBy(700)
            capture("startup-preview")
            compose.runOnIdle { activity.setContent {} }
            compose.waitForIdle()
        }
    }

    @Test(timeout = 120_000)
    fun oldDismissedDraftCanBeCollapsedEditedReopenedAndConfirmedOnce() {
        val app = RuntimeEnvironment.getApplication() as JiliguluApp
        val history = app.container.chatHistoryRepository
        val draftId = runBlocking {
            app.container.userPrefs.setNickname("路陌")
            app.container.userPrefs.setWaterEnabled(false)
            app.container.userPrefs.setUpdateRepository("")
            history.insert(ChatMessageEntity(kind = "DRAFT", status = "DISMISSED",
                rawInput = "12块补记午饭", createdAt = System.currentTimeMillis(),
                draftPayload = DraftHistoryCodec.encode(listOf(DraftUi(amountText = "12", categoryName = "吃饭",
                    detail = "补记午饭", timestamp = System.currentTimeMillis())))))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { bindActivity(it) }
            awaitText("对话记账")
            compose.onNodeWithText("对话记账").performClick()
            awaitText("展开 · 编辑草稿")
            compose.onNodeWithTag("draft-amount-$draftId-0").assertDoesNotExist()
            capture("draft-collapsed")
            compose.onNodeWithTag("draft-expand-$draftId").performClick()
            awaitTag("draft-amount-$draftId-0")
            compose.onNodeWithTag("draft-amount-$draftId-0").performTextReplacement("15.50")
            capture("draft-expanded")
            compose.onNodeWithTag("draft-collapse-$draftId").performClick()
            awaitTag("draft-amount-$draftId-0", present = false)
            compose.onNodeWithTag("draft-amount-$draftId-0").assertDoesNotExist()
            runBlocking {
                withTimeout(5_000) {
                    history.observeAll().first { messages ->
                        messages.firstOrNull { it.id == draftId }?.let {
                            DraftHistoryCodec.decode(it.draftPayload).single().amountText == "15.50"
                        } == true
                    }
                }
                assertEquals("DISMISSED", history.getById(draftId)!!.status)
            }
            compose.onNodeWithContentDescription("返回").performClick()
            awaitText("对话记账")
            compose.onNodeWithText("对话记账").performClick()
            awaitText("展开 · 编辑草稿")
            compose.onNodeWithText("−¥15.50").assertIsDisplayed()
            compose.onNodeWithTag("draft-amount-$draftId-0").assertDoesNotExist()
            compose.onNodeWithTag("draft-expand-$draftId").performClick()
            awaitTag("draft-confirm-$draftId")
            compose.onNodeWithTag("draft-confirm-$draftId").performClick()
            awaitText("记好了，1 笔账已放进账本 ♡", substring = true)
            runBlocking {
                assertEquals("CONFIRMED", history.getById(draftId)!!.status)
                val bills = app.container.billRepository.recent(30).filter { it.detail == "补记午饭" }
                assertEquals(1, bills.size)
                assertEquals(1550L, bills.single().amountFen)
            }
            compose.onNodeWithTag("draft-confirm-$draftId").assertDoesNotExist()
            compose.onNodeWithTag("draft-expand-$draftId").assertDoesNotExist()
            compose.onNodeWithContentDescription("返回").performClick()
            awaitText("对话记账")
            val deletedDraftId = runBlocking {
                history.insert(ChatMessageEntity(kind = "DRAFT", status = "EDITING", rawInput = "不要的草稿",
                    createdAt = System.currentTimeMillis(), draftPayload = DraftHistoryCodec.encode(
                        listOf(DraftUi(amountText = "8", categoryName = "吃饭", detail = "这笔不入账")))))
            }
            compose.onNodeWithText("对话记账").performClick()
            awaitText("展开 · 编辑草稿")
            compose.onNodeWithTag("draft-expand-$deletedDraftId").performClick()
            awaitTag("draft-delete-$deletedDraftId")
            compose.onNodeWithTag("draft-delete-$deletedDraftId").performClick()
            awaitText("这张草稿不要了吗？")
            compose.onNodeWithText("再留一会儿").performClick()
            assertEquals("EDITING", runBlocking { history.getById(deletedDraftId)!!.status })
            compose.onNodeWithTag("draft-delete-$deletedDraftId").performClick()
            compose.onNodeWithTag("draft-delete-confirm-$deletedDraftId").performClick()
            awaitText("草稿已删除")
            compose.onNodeWithTag("draft-expand-$deletedDraftId").assertDoesNotExist()
            compose.onNodeWithTag("draft-confirm-$deletedDraftId").assertDoesNotExist()
            assertEquals("DELETED", runBlocking { history.getById(deletedDraftId)!!.status })
            assertTrue(runBlocking { app.container.billRepository.recent(30).none { it.detail == "这笔不入账" } })
            capture("draft-deleted")
        }
    }

    @Test(timeout = 120_000)
    fun appSettingsCardShowsTheProposalAndCancellationPreservesSettings() {
        val app = RuntimeEnvironment.getApplication() as JiliguluApp
        val prefs = app.container.userPrefs
        val history = app.container.chatHistoryRepository
        val cardId = runBlocking {
            prefs.setNickname("路陌")
            prefs.setWaterSettings(enabled = false, intervalMinutes = 60)
            prefs.setUpdateRepository("")
            history.insert(ChatMessageEntity(kind = "APP_ACTION", status = "EDITING",
                rawInput = "打开提醒，每15分钟喝水", createdAt = System.currentTimeMillis(),
                draftPayload = AppActionCodec.encode(AppActionPayload(
                    action = AiAppAction(kind = AiAppAction.WATER_SETTINGS, enabled = true, intervalMinutes = 15),
                    summary = "喝水提醒：开启\n提醒间隔：60 → 15 分钟"))))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { bindActivity(it) }
            awaitText("对话记账")
            compose.onNodeWithText("对话记账").performClick()
            awaitText("确认调整")
            compose.onNodeWithText("喝水提醒：开启\n提醒间隔：60 → 15 分钟").assertIsDisplayed()
            runBlocking {
                assertEquals(false, prefs.waterEnabled.first())
                assertEquals(60, prefs.waterIntervalMinutes.first())
            }
            capture("app-action-confirmation")
            compose.onNodeWithTag("app-action-cancel").performClick()
            awaitText("已取消，没有执行这次操作")
            runBlocking {
                assertEquals("DISMISSED", history.getById(cardId)!!.status)
                assertEquals(false, prefs.waterEnabled.first())
                assertEquals(60, prefs.waterIntervalMinutes.first())
            }
            compose.onNodeWithTag("app-action-confirm").assertDoesNotExist()
        }
    }

    @Test(timeout = 120_000)
    fun newDraftExpandsWithoutPrematureReplyAndNewMessagesFollowTheBottom() {
        val app = RuntimeEnvironment.getApplication() as JiliguluApp
        val container = app.container
        val history = container.chatHistoryRepository
        val gate = java.util.concurrent.CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            check(gate.await(20, java.util.concurrent.TimeUnit.SECONDS))
            val content = """{"bills":[{"amount_yuan":3,"type":"EXPENSE","category":"饮品","detail":"验收水"}],"reply":"3块的水记上啦"}"""
            val body = org.json.JSONObject().put("choices", org.json.JSONArray().put(org.json.JSONObject()
                .put("finish_reason", "stop").put("message", org.json.JSONObject().put("content", content)))).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val before = runBlocking {
            container.userPrefs.setNickname("验收")
            container.userPrefs.setApiKeyOverride("test-only")
            container.userPrefs.setWaterEnabled(false)
            container.userPrefs.setUpdateRepository("")
            repeat(20) { i ->
                history.insert(ChatMessageEntity(kind = "USER", content = "旧消息$i"))
                history.insert(ChatMessageEntity(kind = "ASSISTANT", content = "以前的回复$i"))
            }
            history.getAll().count { it.kind == "ASSISTANT" }
        }
        val ai = AiRepository(app, container.categoryRepository, container.billRepository, container.userPrefs,
            history, container.categoryAdminRepository, clientFactory = { DeepSeekClient("test-only", http) })
        val store = ViewModelStore()
        val model = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(ai, container.categoryRepository, PersonaEngine(QuipLibrary.get(app)), history) as T
        })[ChatViewModel::class.java]
        rememberFixtureViewModel(model)
        val visible = androidx.compose.runtime.mutableStateOf(true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    bindActivity(it)
                    it.setContent {
                        GuluTheme {
                            if (visible.value) ChatScreen(vm = model, onBack = { visible.value = false })
                            else androidx.compose.material3.TextButton(onClick = { visible.value = true }) {
                                androidx.compose.material3.Text("重新进入聊天")
                            }
                        }
                    }
                }
                awaitText("以前的回复19")
                compose.onNodeWithTag("chat-input").performTextReplacement("水，3")
                compose.onNodeWithContentDescription("发送").performClick()
                compose.waitUntil(10_000) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                    (model.items.value.lastOrNull() as? ChatItem.GuluMsg)?.loading == true
                }
                compose.onNodeWithTag("chat-messages").performScrollToIndex(15)
                gate.countDown()
                awaitText("确认记账（1）")
                val card = model.items.value.filterIsInstance<ChatItem.DraftCard>().last()
                compose.onNodeWithTag("draft-confirm-${card.id}").assertIsDisplayed()
                compose.onNodeWithTag("draft-amount-${card.id}-0").assertIsDisplayed()
                compose.onNodeWithText("3块的水记上啦").assertDoesNotExist()
                assertEquals(before, runBlocking { history.getAll().count { it.kind == "ASSISTANT" } })
                assertTrue(runBlocking { container.billRepository.recent(50).none { it.detail == "验收水" } })
                capture("fresh-draft-expanded")
                compose.onNodeWithContentDescription("返回").performClick()
                awaitText("重新进入聊天")
                compose.onNodeWithText("重新进入聊天").performClick()
                awaitText("展开 · 编辑草稿")
                compose.onNodeWithTag("draft-amount-${card.id}-0").assertDoesNotExist()
                compose.onNodeWithTag("draft-expand-${card.id}").performClick()
                awaitTag("draft-confirm-${card.id}")
                compose.onNodeWithTag("draft-confirm-${card.id}").performClick()
                awaitText("笔账已放进账本", substring = true)
                assertEquals(before + 1, runBlocking { history.getAll().count { it.kind == "ASSISTANT" } })
                assertEquals(1, runBlocking { container.billRepository.recent(50).count { it.detail == "验收水" } })
                compose.onNodeWithText("3块的水记上啦").assertDoesNotExist()
                capture("draft-confirmed-single-reply")
                compose.runOnIdle { activity.setContent {} }
            }
        } finally {
            gate.countDown()
            store.clear()
        }
    }

    @Test(timeout = 120_000)
    fun reopeningActivityInAnAlreadyStartedProcessStillLoadsItsScreen() {
        val app = RuntimeEnvironment.getApplication() as JiliguluApp
        runBlocking {
            app.container.userPrefs.setNickname("验收")
            app.container.userPrefs.setWaterEnabled(false)
            app.container.userPrefs.setUpdateRepository("")
        }
        repeat(2) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { bindActivity(it) }
                awaitText("对话记账")
                assertTrue(app.container.startupCompleted)
                visibleDescription("设置").assertIsDisplayed()
                compose.runOnIdle { activity.setContent {} }
            }
        }
    }

    @Test(timeout = 90_000)
    fun announcementSupportsOpenAndMute() {
        val app = RuntimeEnvironment.getApplication() as JiliguluApp
        val prefs = app.container.userPrefs
        val fixture = """{"announcements":[{"id":"holiday-demo","title":"中秋快乐，记得好好吃饭","summary":"阿噜寄来一封小小的节日来信","emoji":"🌕","body":"愿你的日子像月亮一样圆满。\n忙碌之余，也记得给自己留一点甜。\n\n这是一条仅用于界面验收的公告。"}]}"""
        val notices = com.jiligulu.app.data.announcement.AnnouncementRepository(prefs, { fixture })
        runBlocking {
            prefs.setNickname("验收")
            prefs.setWaterEnabled(false)
            prefs.setUpdateRepository("")
            prefs.setAnnouncementSource("https://example.test/feed.json")
            notices.initialize()
            prefs.setAnnouncementSource("") // The actual app container must not make network requests in this UI test.
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                bindActivity(it)
                it.setContent {
                    GuluTheme {
                        val state = notices.state.collectAsStateWithLifecycle().value
                        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
                            com.jiligulu.app.ui.announcement.AnnouncementBoard(state, notices::open)
                            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.weight(1f))
                        }
                        com.jiligulu.app.ui.announcement.AnnouncementDialogHost(notices, enabled = true)
                    }
                }
            }
            awaitTag("announcement-body")
            capture("announcement-popup", dialog = true)
            dismissTopDialogOutside("announcement")
            awaitTag("announcement-body", present = false)
            assertTrue("Closing an announcement does not mute it", "holiday-demo" !in runBlocking { prefs.readAnnouncements().mutedIds })
            compose.onNodeWithTag("announcement-board").performClick()
            awaitTag("announcement-body")
            compose.onNodeWithText("这条不再弹出").performClick()
            awaitTag("announcement-body", present = false)
            assertTrue(runBlocking { "holiday-demo" in prefs.readAnnouncements().mutedIds })
            compose.onNodeWithTag("announcement-board").assertIsDisplayed()
            capture("announcement-home-card")
            compose.runOnIdle { activity.setContent {} }
        }
    }

    @Test(timeout = 60_000)
    fun emptyMailboxAndKeyboardInputRemainAvailable() {
        val app = RuntimeEnvironment.getApplication() as JiliguluApp
        val prefs = app.container.userPrefs
        runBlocking {
            prefs.setNickname("验收")
            prefs.setWaterEnabled(false)
            prefs.setUpdateRepository("")
            prefs.setAnnouncementSource("")
            app.container.announcements.initialize()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { bindActivity(it) }
            awaitText("对话记账")
            compose.onNodeWithTag("main-mailbox").performClick()
            awaitTag("announcement-empty")
            compose.onNodeWithText("收好信笺").performClick()
            awaitTag("announcement-empty", present = false)
            compose.onNodeWithText("对话记账").performClick()
            awaitTag("chat-input")
            compose.onNodeWithTag("voice-toggle").assertDoesNotExist()
            compose.runOnIdle { activity.setContent {} }
        }
    }

    @Test(timeout = 60_000)
    fun refreshedStatisticsAndCompactSettings() {
        val fixtureMonth = java.time.YearMonth.of(2026, 9)
        fun fixtureDay(day: Int) = fixtureMonth.atDay(day).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val app = RuntimeEnvironment.getApplication() as JiliguluApp
        runBlocking {
            app.container.userPrefs.setNickname("路陌")
            app.container.userPrefs.setThemeMode(UserPrefs.THEME_LIGHT)
            app.container.userPrefs.setWaterEnabled(false)
            app.container.userPrefs.setUpdateRepository("")
            app.container.userPrefs.setAnnouncementSource("")
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                bindActivity(it)
                it.setContent { GuluTheme {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
                        com.jiligulu.app.ui.stats.charts.CashFlowBarChart(
                            (1..30).map { day -> com.jiligulu.app.ui.stats.charts.DayBar(day, fixtureDay(day), if (day % 3 == 0) day * 120L else day * 60L, day == 24) },
                            fixtureDay(24), {}, com.jiligulu.app.ui.theme.ExpenseCoral, com.jiligulu.app.ui.theme.OutlineLight,
                            androidx.compose.ui.Modifier.fillMaxSize())
                    }
                } }
            }
            awaitText("今天")
            compose.onNodeWithContentDescription("9月24日，28.8元，已选中").assertIsDisplayed()
            capture("statistics-soft-bars")
            compose.runOnIdle { activity.setContent { GuluTheme { com.jiligulu.app.ui.settings.SettingsScreen(onBack = {}) } } }
            awaitSettingsPage("外观", "主题与皮肤")
            capture("settings-single-card")
            compose.onNodeWithTag("settings-tab-互动").performClick()
            awaitSettingsPage("互动", "你的称呼")
            scrollSettingsTo("阿噜悬浮球")
            capture("settings-interaction-card")
            compose.onNodeWithText("试听").assertDoesNotExist()
            scrollSettingsTo("采样速度")
            listOf(15, 30, 60, 120).forEach { fps ->
                compose.onNodeWithTag("app-glass-rate-$fps").assertIsDisplayed()
            }
            compose.onNodeWithContentDescription("采样速度说明").performClick()
            awaitText("仅对 App 内的背景折射起效", substring = true)
            dismissTopDialogOutside("sampling speed explanation")
            compose.onNodeWithContentDescription("采样速度说明").assertIsDisplayed()
            compose.onNodeWithTag("settings-tab-提醒").performClick()
            awaitSettingsPage("提醒", "喝水提醒")
            // Pager may keep the interaction page composed beside the visible reminder page.
            compose.onNodeWithText("阿噜悬浮球").assertIsNotDisplayed()
            capture("settings-reminders-card")
            compose.runOnIdle { activity.setContent {} }
        }
    }

    @Test(timeout = 75_000)
    @Config(qualifiers = "w411dp-h640dp-port-xhdpi")
    fun settingsPagerSwipesSynchronizeTabsAndRestoreInteractionScroll() {
        val app = RuntimeEnvironment.getApplication() as JiliguluApp
        runBlocking {
            app.container.userPrefs.setNickname("路陌")
            app.container.userPrefs.setThemeMode(UserPrefs.THEME_LIGHT)
            app.container.userPrefs.setWaterEnabled(false)
            app.container.userPrefs.setUpdateRepository("")
            app.container.userPrefs.setAnnouncementSource("")
            app.container.announcements.initialize()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                bindActivity(it)
                it.setContent { GuluTheme { com.jiligulu.app.ui.settings.SettingsScreen(onBack = {}) } }
            }
            awaitSettingsPage("外观", "主题与皮肤")
            compose.onNodeWithTag("settings-pages").performTouchInput {
                swipe(Offset(width * .8f, height * .08f), Offset(width * .2f, height * .08f), durationMillis = 300)
            }
            awaitSettingsPage("互动", "你的称呼")
            compose.onNodeWithText("主题与皮肤").assertIsNotDisplayed()

            val initialScroll = currentSettingsScroll().fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
            assertEquals("Interaction starts at the top", 0f, initialScroll.value(), .5f)
            assertTrue("The compact viewport must make interaction settings scrollable", initialScroll.maxValue() > 0f)
            currentSettingsScroll().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 200f) }
            compose.mainClock.advanceTimeBy(350)
            compose.waitForIdle()
            val beforeScroll = currentSettingsScroll().fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("The interaction page must actually move before leaving it", beforeScroll > 0f)
            val anchor = "悬浮与玻璃"
            val beforeAnchor = visibleText(anchor).getUnclippedBoundsInRoot().top.value

            // Drag across a section title, away from the icon-size slider and its gestures.
            val pagerTop = compose.onNodeWithTag("settings-pages").fetchSemanticsNode().boundsInRoot.top
            val titleY = visibleText(anchor).fetchSemanticsNode().boundsInRoot.center.y - pagerTop
            compose.onNodeWithTag("settings-pages").performTouchInput {
                swipe(Offset(width * .8f, titleY), Offset(width * .2f, titleY), durationMillis = 300)
            }
            awaitSettingsPage("提醒", "喝水提醒")
            compose.onNodeWithText(anchor).assertIsNotDisplayed()
            val reminderScroll = currentSettingsScroll().fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertEquals("A new page must have its own scroll position", 0f, reminderScroll, .5f)
            compose.onNodeWithTag("settings-pages").performTouchInput {
                swipe(Offset(width * .2f, height * .08f), Offset(width * .8f, height * .08f), durationMillis = 300)
            }
            awaitSettingsPage("互动", anchor)
            assertEquals("Swiping back must restore interaction scroll", beforeScroll,
                currentSettingsScroll().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
            assertEquals("The same title must return to its viewport anchor", beforeAnchor,
                visibleText(anchor).getUnclippedBoundsInRoot().top.value, 1f)

            compose.onNodeWithTag("settings-tabs").performScrollToIndex(0)
            compose.onNodeWithTag("settings-tab-外观").performClick()
            awaitSettingsPage("外观", "主题与皮肤")
            compose.onNodeWithTag("settings-tab-互动").performClick()
            awaitSettingsPage("互动", anchor)
            assertEquals("Selecting a tab must also restore interaction scroll", beforeScroll,
                currentSettingsScroll().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
            assertEquals(beforeAnchor, visibleText(anchor).getUnclippedBoundsInRoot().top.value, 1f)
            compose.runOnIdle { activity.setContent {} }
        }
    }

    @Test(timeout = 75_000)
    fun pinnedLedgerKeepsPositionAcrossStatisticsTab() {
        val c = (RuntimeEnvironment.getApplication() as JiliguluApp).container
        runBlocking {
            c.userPrefs.setWaterEnabled(false)
            c.userPrefs.setUpdateRepository("")
            c.userPrefs.setAnnouncementSource(""); c.announcements.initialize()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { bindActivity(it); it.setContent { GuluTheme {
                com.jiligulu.app.ui.main.MainScreen({}, {}, {})
            } } }
            awaitTag("home-outer")
            compose.onNodeWithTag("home-outer").performScrollToIndex(com.jiligulu.app.ui.home.HOME_LEDGER_ITEM_INDEX)
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(350)
            compose.waitForIdle()
            val before = compose.onNodeWithTag("home-ledger-heading").getUnclippedBoundsInRoot().top
            compose.onNodeWithText("统计").performClick()
            awaitText("每日收支")
            compose.onNodeWithText("账本").performClick()
            awaitTag("home-ledger-heading")
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(350)
            compose.waitForIdle()
            val after = compose.onNodeWithTag("home-ledger-heading").getUnclippedBoundsInRoot().top
            assertTrue("Pinned heading must keep its viewport anchor: $before -> $after", kotlin.math.abs(before.value - after.value) < 2f)
            compose.runOnIdle { activity.setContent {} }
        }
    }

    @Test(timeout = 75_000)
    fun compactStatisticsSelectsThenOpensCategoryDialog() {
        val c = (RuntimeEnvironment.getApplication() as JiliguluApp).container
        val today = com.jiligulu.app.core.util.Formatters.dayStart(System.currentTimeMillis())
        runBlocking {
            c.userPrefs.setWaterEnabled(false)
            c.userPrefs.setThemeMode(UserPrefs.THEME_LIGHT)
            c.userPrefs.setUpdateRepository("")
            c.userPrefs.setAnnouncementSource(""); c.announcements.initialize()
            listOf("交通", "零食", "购物", "住房", "宠物", "数码", "学习", "生活服务").forEach {
                c.categoryRepository.createCategory(it)
            }
            c.budgetRepository.setBudget(100000, com.jiligulu.app.data.local.entity.BudgetPeriod.MONTHLY, 1)
            val categories = c.categoryRepository.getAll()
            val food = categories.first { it.name == "吃饭" }
            c.billRepository.addManual(9000, BillType.EXPENSE, food.id, "午餐验收", "", today + 12 * 3600000L)
            categories.filter { it.id != food.id }.take(8).forEachIndexed { i, category ->
                c.billRepository.addManual(100L + i * 100L, BillType.EXPENSE, category.id, "其他验收$i", "", today + 13 * 3600000L)
            }
        }
        val store = ViewModelStore()
        val stats = com.jiligulu.app.ui.stats.StatsViewModel(c.billRepository, c.categoryRepository, c.budgetRepository)
        store.put("stats-preview", stats)
        rememberFixtureViewModel(stats)
        val active = androidx.compose.runtime.mutableStateOf(true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { bindActivity(it); it.setContent { GuluTheme { com.jiligulu.app.ui.stats.StatsScreen(stats, active.value) } } }
                awaitText("每日收支")
                awaitText("吃饭")
                compose.waitUntil(8000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)); stats.dayDonut.value.slices.size == 7 }
                compose.waitUntil(8000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)); stats.budgetUi.value.visible }
                capture("statistics-compact")
                compose.onNodeWithText("吃饭").performClick()
                awaitText("再点一次选中分类，看看小账单 ♡")
                compose.onNodeWithText("午餐验收").assertDoesNotExist()
                compose.onAllNodesWithText("吃饭").onLast().performClick()
                awaitText("午餐验收")
                capture("statistics-category-dialog", dialog = true)
                dismissTopDialogOutside("selected category details")
                compose.onAllNodes(hasText("午餐验收") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
                compose.runOnIdle { active.value = false }
                awaitText("轻点分类看占比 · 左右滑动换一天")
                compose.runOnIdle { activity.setContent {} }
            }
        } finally { store.clear() }
    }

    @Test(timeout = 90_000)
    fun pagerFollowsFingerWithoutRedirectAndStickyHeaderNeedsSecondPull() {
        val c = (RuntimeEnvironment.getApplication() as JiliguluApp).container
        val today = com.jiligulu.app.core.util.Formatters.dayStart(System.currentTimeMillis())
        val yesterday = com.jiligulu.app.ui.components.shiftLocalDay(today, -1)
        runBlocking {
            c.userPrefs.setNickname("路陌"); c.userPrefs.setWaterEnabled(false)
            c.userPrefs.setThemeMode(UserPrefs.THEME_LIGHT); c.userPrefs.setUpdateRepository("")
            c.userPrefs.setAnnouncementSource(""); c.announcements.initialize()
            val food = c.categoryRepository.getAll().first { it.name == "吃饭" }.id
            repeat(12) { i -> c.billRepository.addManual(900, BillType.EXPENSE, food, "今天-$i", "", today + (i + 1) * 60000L) }
            c.billRepository.addManual(1200, BillType.EXPENSE, food, "昨天唯一账单", "", yesterday + 3600000L)
        }
        val store = ViewModelStore()
        val home = com.jiligulu.app.ui.home.HomeViewModel(c.billRepository, c.categoryRepository)
        val stats = com.jiligulu.app.ui.stats.StatsViewModel(c.billRepository, c.categoryRepository, c.budgetRepository)
        store.put("home-preview", home); store.put("stats-preview", stats)
        rememberFixtureViewModel(home); rememberFixtureViewModel(stats)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { bindActivity(it); it.setContent { GuluTheme { com.jiligulu.app.ui.home.HomeScreen({}, {}, home) } } }
                awaitText("今天-11")
                // Leave the ledger partially below its pin position: horizontal navigation must not collapse the overview.
                compose.onNodeWithTag("home-outer").performScrollToIndex(com.jiligulu.app.ui.home.HOME_LEDGER_ITEM_INDEX - 1)
                compose.mainClock.advanceTimeBy(300)
                compose.waitForIdle()
                val before = compose.onNodeWithTag("home-ledger-heading").fetchSemanticsNode().boundsInRoot.top
                whileFingerHeld("home-day-pager", gesture = {
                    down(Offset(width * .15f, height * .6f))
                    moveTo(Offset(width * .25f, height * .6f), delayMillis = 70)
                    moveTo(Offset(width * .48f, height * .6f), delayMillis = 100)
                    moveTo(Offset(width * .78f, height * .6f), delayMillis = 160)
                }) {
                    assertEquals(today, home.selectedDay.value)
                    capture("home-finger-held-pages")
                    File("build/reports/ui/pager-held-semantics.txt").writeText(compose.onNodeWithTag("home-day-pager").printToString(5))
                    compose.onNodeWithText("昨天唯一账单").assertIsDisplayed()
                }
                compose.waitUntil(8000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)); home.selectedDay.value == yesterday }
                assertEquals(before, compose.onNodeWithTag("home-ledger-heading").fetchSemanticsNode().boundsInRoot.top, 2f)
                compose.runOnIdle { home.showToday() }
                compose.mainClock.advanceTimeBy(800)
                awaitText("今天-11")
                assertEquals(today, home.selectedDay.value)
                compose.waitUntil(8000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)); compose.onAllNodesWithTag("home-day-bills").fetchSemanticsNodes().size == 1 }
                compose.onNodeWithTag("home-outer").performScrollToIndex(com.jiligulu.app.ui.home.HOME_LEDGER_ITEM_INDEX)
                compose.onNodeWithTag("home-day-bills").performScrollToIndex(0)
                compose.mainClock.advanceTimeBy(300)
                compose.waitForIdle()
                val pinned = compose.onNodeWithTag("home-ledger-heading").fetchSemanticsNode().boundsInRoot.top
                val billsTop = compose.onNodeWithTag("home-day-bills").fetchSemanticsNode().boundsInRoot.top
                whileFingerHeld("home-day-bills", gesture = {
                    down(Offset(width * .5f, height * .2f))
                    moveTo(Offset(width * .5f, height * .4f), delayMillis = 70)
                    moveTo(Offset(width * .5f, height * .75f), delayMillis = 140)
                }) {
                    compose.mainClock.advanceTimeBy(32)
                    // The spring belongs to the list; moving its heading leaves an empty strip.
                    assertEquals(pinned, compose.onNodeWithTag("home-ledger-heading").fetchSemanticsNode().boundsInRoot.top, 2f)
                    assertTrue(compose.onNodeWithTag("home-day-bills").fetchSemanticsNode().boundsInRoot.top > billsTop + 4)
                    capture("home-first-pull-stretch")
                }
                compose.mainClock.advanceTimeBy(1200)
                assertEquals(pinned, compose.onNodeWithTag("home-ledger-heading").fetchSemanticsNode().boundsInRoot.top, 2f)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                compose.mainClock.advanceTimeBy(800)
                assertEquals(pinned, compose.onNodeWithTag("home-ledger-heading").fetchSemanticsNode().boundsInRoot.top, 2f)
                compose.onNodeWithTag("home-day-bills").performTouchInput { swipeDown() }
                compose.mainClock.advanceTimeBy(1200)
                assertEquals(pinned, compose.onNodeWithTag("home-ledger-heading").fetchSemanticsNode().boundsInRoot.top, 2f)
                compose.onNodeWithTag("home-day-bills").performTouchInput { swipeDown() }
                compose.mainClock.advanceTimeBy(800)
                assertTrue(compose.onNodeWithTag("home-ledger-heading").fetchSemanticsNode().boundsInRoot.top > pinned + 10)
                compose.runOnIdle { activity.setContent { GuluTheme { com.jiligulu.app.ui.stats.StatsScreen(stats) } } }
                awaitText("每日收支")
                currentStatisticsList().performScrollToIndex(2)
                awaitTag("statistics-day-swipe")
                whileFingerHeld("statistics-day-swipe", gesture = {
                    down(Offset(width * .15f, height * .35f))
                    moveTo(Offset(width * .25f, height * .35f), delayMillis = 70)
                    moveTo(Offset(width * .48f, height * .35f), delayMillis = 100)
                    moveTo(Offset(width * .78f, height * .35f), delayMillis = 160)
                }) {
                    assertEquals(today, stats.selectedDay.value)
                    capture("statistics-release-swipe")
                }
                compose.waitUntil(8000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)); stats.selectedDay.value == yesterday }
                compose.runOnIdle { activity.setContent {} }
            }
        } finally {
            if (::activity.isInitialized && !activity.isDestroyed) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.setContent {} }
            }
            store.clear()
            Snapshot.sendApplyNotifications()
        }
    }

    @Test(timeout = 45_000)
    fun crookedStickerDrawerRender() {
        val c=(RuntimeEnvironment.getApplication() as JiliguluApp).container
        runBlocking {
            c.userPrefs.setWaterEnabled(false);c.userPrefs.setUpdateRepository("")
            c.userPrefs.setAnnouncementSource("");c.announcements.initialize()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { bindActivity(it);it.setContent { GuluTheme { com.jiligulu.app.ui.stickers.StickerDrawer({}, {}) } } }
            awaitText("阿噜的贴纸墙");awaitText("早餐")
            compose.mainClock.advanceTimeBy(500);compose.waitForIdle()
            capture("sticker-drawer",dialog=true)
            compose.runOnIdle { activity.setContent {} }
        }
    }

    @Test(timeout = 75_000)
    fun littleWorldScreensAndStickerDrawerRender() {
        val app = RuntimeEnvironment.getApplication() as JiliguluApp
        val c = app.container
        val activeId = "ui-travel-wish"
        runBlocking {
            c.userPrefs.setThemeMode(UserPrefs.THEME_LIGHT)
            c.userPrefs.setWaterEnabled(false)
            c.userPrefs.setUpdateRepository("")
            c.userPrefs.setAnnouncementSource(""); c.announcements.initialize()
            c.littleWorld.saveWish(com.jiligulu.app.data.littleworld.Wish(id = activeId, title = "去海边的小旅行", targetFen = 100_000, emoji = "🌊", caption = "把想看的海，一颗颗攒起来。"))
            c.littleWorld.deposit(activeId, 30_000, "这个月先留一点")
            c.littleWorld.saveWish(com.jiligulu.app.data.littleworld.Wish(id = "ui-complete-wish", title = "终于买到小相机", targetFen = 12_000, emoji = "📷", caption = "以后的小日子，都想拍下来。"))
            c.littleWorld.deposit("ui-complete-wish", 12_000, "给自己的小礼物")
            c.littleWorld.saveWaiting(com.jiligulu.app.data.littleworld.WaitingWish(id = "ui-waiting-wish", title = "一盏暖暖的小台灯", amountFen = 9900, emoji = "💡"))
            c.littleWorld.saveFutureNote(com.jiligulu.app.data.littleworld.FutureNote(id = "ui-future-note", title = "旅行前给自己的一句话", body = "到海边的时候，记得慢慢走，吹一会儿风。", dueAt = System.currentTimeMillis() + 2 * 86_400_000L))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { bindActivity(it) }
            fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
                compose.runOnIdle { activity.setContent { GuluTheme(darkTheme = false) { content() } } }
            }

            render { com.jiligulu.app.ui.littleworld.LittleWorldScreen({}, {}, {}, {}) }
            awaitText("今日小签")
            capture("little-world-home")
            compose.onNodeWithText("今日小签").performClick()
            awaitText("翻翻收藏")
            val collectionPosition = compose.onNodeWithText("翻翻收藏").getUnclippedBoundsInRoot()
            val todayFortune = com.jiligulu.app.ui.littleworld.DailyFortunes.forDate(java.time.LocalDate.now())
            val wasSaved = runBlocking { todayFortune.id in c.littleWorld.snapshot().favoriteFortunes }
            compose.onNodeWithText(if (wasSaved) "已收藏" else "收藏").performClick()
            try {
                compose.waitUntil(8_000) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                    runBlocking { (todayFortune.id in c.littleWorld.snapshot().favoriteFortunes) != wasSaved }
                }
            } catch (failure: Throwable) {
                val actual = runBlocking { c.littleWorld.snapshot().favoriteFortunes }
                val tree = runCatching { allRootsSemantics() }.getOrElse { "Semantics unavailable: $it" }
                throw AssertionError("Bookmark did not toggle id=${todayFortune.id}, before=$wasSaved, after=$actual\n$tree", failure)
            }
            awaitText(if (wasSaved) "收藏" else "已收藏")
            assertEquals(collectionPosition, compose.onNodeWithText("翻翻收藏").getUnclippedBoundsInRoot())
            capture("little-world-fortune", dialog = true)
            dismissTopDialogOutside("daily fortune")
            compose.onNodeWithText("翻翻收藏").assertDoesNotExist()
            assertEquals(!wasSaved, runBlocking { todayFortune.id in c.littleWorld.snapshot().favoriteFortunes })

            render { com.jiligulu.app.ui.littleworld.WishBookScreen({}, {}) }
            awaitText("去海边的小旅行", substring = true)
            visibleClickLabel("查看去海边的小旅行").performClick()
            awaitText("30% · 正在攒")
            capture("wishbook-active", dialog = true)
            compose.onNodeWithText("放颗星星").performClick()
            awaitText("给「去海边的小旅行」放颗星星")
            compose.onNodeWithContentDescription("这次攒").performTextReplacement("10")
            compose.onNodeWithText("装进瓶子").performClick()
            compose.waitUntil(8000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                compose.onAllNodesWithText("给「去海边的小旅行」放颗星星").fetchSemanticsNodes().isEmpty()
            }
            // Saving a deposit returns to its parent bottle window, rather than the shelf.
            awaitText("31% · 正在攒")
            compose.onNodeWithText("放颗星星").assertIsDisplayed()
            assertEquals(31_000L, runBlocking { c.littleWorld.snapshot().wishes.first { it.id == activeId }.savedFen })
            dismissTopDialogOutside("wish bottle after saving a deposit")
            compose.onNodeWithText("放颗星星").assertDoesNotExist()
            assertEquals(31_000L, runBlocking { c.littleWorld.snapshot().wishes.first { it.id == activeId }.savedFen })
            compose.onNodeWithText("纪念").performClick()
            awaitText("终于买到小相机", substring = true)
            capture("wishbook-completed")

            render { com.jiligulu.app.ui.stickers.StickerDrawer({}, {}) }
            awaitText("阿噜的贴纸墙")
            awaitText("早餐")
            compose.mainClock.advanceTimeBy(400)
            capture("sticker-drawer", dialog = true)

            render { com.jiligulu.app.ui.futurenotes.FutureNotesScreen({}) }
            awaitText("在路上")
            compose.onNodeWithText("在路上").performClick()
            awaitText("旅行前给自己的一句话")
            capture("future-notes-list", dialog = true)
            compose.onNodeWithText("旅行前给自己的一句话").performClick()
            awaitText("到海边的时候，记得慢慢走，吹一会儿风。")
            capture("future-note-letter", dialog = true)
            dismissTopDialogOutside("future letter reader")
            compose.onNodeWithTag("future-note-body").assertDoesNotExist()
            assertNull(runBlocking { c.littleWorld.snapshot().futureNotes.first { it.id == "ui-future-note" }.readAt })
            dismissTopDialogOutside("future letter drawer")
            compose.onNodeWithText("旅行前给自己的一句话").assertDoesNotExist()
            compose.onNodeWithText("写一封信").performClick()
            awaitText("写给未来的你")
            compose.onNodeWithContentDescription("标题").performTextReplacement("UI 保存的小信")
            compose.onNodeWithContentDescription("正文").performTextReplacement("今天也记得给自己留一点甜。")
            compose.onNodeWithText("寄出去").performClick()
            compose.waitUntil(8000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                compose.onAllNodesWithText("写给未来的你").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("在路上").performClick()
            awaitText("UI 保存的小信")
            assertTrue(runBlocking { c.littleWorld.snapshot().futureNotes.any { it.title == "UI 保存的小信" && !it.notificationEnabled } })
            compose.runOnIdle { activity.setContent {} }
            compose.waitForIdle()
        }
    }

    private fun awaitTag(tag: String, present: Boolean = true) {
        try {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            rememberLiveActivityModels()
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() == present
        }
        compose.waitForIdle()
        } catch (failure: Throwable) {
            File("build/reports/ui/failed-tag.txt").apply { parentFile?.mkdirs() }.writeText("$tag present=$present\n" + runCatching { allRootsSemantics() }.getOrElse { "Semantics unavailable: $it" })
            throw failure
        }
    }

    private fun awaitText(text: String, substring: Boolean = false) {
        try {
            compose.waitUntil(15_000) {
                // DataStore resumes on Android's Handler/Choreographer, independently of the
                // Compose test clock. Advance that paused Looper too, so a late I/O completion
                // can publish its next frame instead of leaving the first loading composition.
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                rememberLiveActivityModels()
                compose.onAllNodesWithTag("startup-animation").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            runCatching { capture("failure-screen", dialog = ShadowDialog.getLatestDialog()?.isShowing == true) }
            val tree = runCatching { allRootsSemantics() }.getOrElse { "Unable to read semantics: $it" }
            val flowState = runCatching { runBlocking {
                withTimeout(2_000) {
                    val app = activity.application as JiliguluApp
                    "nickname=${app.container.userPrefs.nickname.first()}, theme=${app.container.userPrefs.themeMode.first()}, " +
                        "sameApplication=${app === RuntimeEnvironment.getApplication()}, main=${Dispatchers.Main}, clock=${compose.mainClock.currentTime}"
                }
            } }.getOrElse { "Preference read failed: $it" }
            File("build/reports/ui/failure-semantics.txt").apply { parentFile?.mkdirs() }
                .writeText("Waiting for '$text'\n$flowState\n$tree\n$failure\n" +
                    org.robolectric.shadows.ShadowLog.getLogsForTag("ConversationHistory").joinToString("\n") { it.throwable?.stackTraceToString().orEmpty() } +
                    "\nWorldState sanitized debug:\n" + org.robolectric.shadows.ShadowLog.getLogsForTag("WorldState").joinToString("\n") { it.msg })
            throw AssertionError("Waiting for '$text': $flowState\n$tree", failure)
        }
        compose.waitForIdle()
    }

    private fun openSampleBill() {
        compose.onNodeWithTag("home-outer").performScrollToIndex(com.jiligulu.app.ui.home.HOME_LEDGER_ITEM_INDEX)
        compose.onNodeWithTag("home-day-bills").performScrollToIndex(0)
        awaitText("牛肉面")
        compose.onAllNodesWithText("牛肉面").onFirst().performClick()
    }

    /** Deliver the same platform outside-window event used by a user tapping beyond a Dialog. */
    private fun dismissTopDialogOutside(flow: String) {
        compose.waitForIdle()
        val dialog = compose.runOnIdle {
            // A child may already have closed, so getLatestDialog can still refer to its old
            // instance while a parent drawer is visible underneath it.
            val current = checkNotNull(ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }) {
                "No showing dialog to dismiss in $flow"
            }
            val now = SystemClock.uptimeMillis()
            val outside = MotionEvent.obtain(now, now, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
            try { assertTrue("Outside event was not handled in $flow", current.onTouchEvent(outside)) }
            finally { outside.recycle() }
            current
        }
        compose.waitUntil(8_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            !dialog.isShowing
        }
        compose.waitForIdle()
        assertTrue("Dialog remained showing after outside dismissal in $flow", !dialog.isShowing)
    }

    private fun awaitWaterCup() {
        compose.waitUntil(15_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            compose.onAllNodesWithContentDescription(WAITING_CUP).fetchSemanticsNodes().size == 1 &&
                compose.onAllNodesWithTag("drinking-animation").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }

    private fun awaitSettingsPage(tab: String, anchor: String) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            rememberLiveActivityModels()
            val chip = compose.onAllNodesWithTag("settings-tab-$tab").fetchSemanticsNodes().singleOrNull()
            val targets = compose.onAllNodesWithText(anchor)
            chip != null && chip.config.contains(SemanticsProperties.Selected) &&
                chip.config[SemanticsProperties.Selected] &&
                targets.fetchSemanticsNodes().indices.any { targets[it].isDisplayed() }
        }
        // Current-page selection can change during the drag; wait until the page settles too.
        compose.mainClock.advanceTimeBy(800)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        compose.waitForIdle()
        compose.onNodeWithTag("settings-tab-$tab").assertIsSelected()
        val selectedTabs = compose.onAllNodes(SemanticsMatcher("selected settings tab") { node ->
            node.config.contains(SemanticsProperties.TestTag) &&
                node.config[SemanticsProperties.TestTag].startsWith("settings-tab-") &&
                node.config.contains(SemanticsProperties.Selected) && node.config[SemanticsProperties.Selected]
        }).fetchSemanticsNodes()
        assertEquals("Exactly one settings tab must be selected", 1, selectedTabs.size)
        visibleText(anchor).assertIsDisplayed()
    }

    private fun currentSettingsScroll(): androidx.compose.ui.test.SemanticsNodeInteraction = compose.onNode(
        SemanticsMatcher("scrollable content in the active settings page") { node ->
            node.config.contains(SemanticsProperties.VerticalScrollAxisRange) &&
                node.config.contains(SemanticsActions.ScrollBy)
        } and hasAnyAncestor(hasTestTag("settings-list")),
        useUnmergedTree = true
    )

    private fun scrollSettingsTo(text: String, towardTop: Boolean = false) {
        // A bounded single action + a frame avoids Compose 1.7's synchronous search-scroll loop.
        repeat(12) {
            if (runCatching { compose.onNodeWithText(text).assertIsDisplayed() }.isSuccess) return
            currentSettingsScroll().performSemanticsAction(SemanticsActions.ScrollBy) {
                it(0f, if (towardTop) -400f else 400f)
            }
            compose.mainClock.advanceTimeBy(250)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            compose.waitForIdle()
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun captureLauncherIcon() {
        compose.runOnIdle {
            // Inflate the packaged resource; Robolectric's package-icon lookup is a mock table.
            val icon = checkNotNull(activity.getDrawable(com.jiligulu.app.R.mipmap.ic_launcher))
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, 256, 256)
            icon.draw(android.graphics.Canvas(bitmap))
            File("build/reports/ui/launcher-icon.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    companion object { private const val WAITING_CUP = "咕噜拿着水杯等你，点击一起喝水" }

    /** Return a background pixel, while refusing to silently accept a blank renderer. */
    private fun capture(name: String, dialog: Boolean = false): Int {
        compose.waitForIdle()
        // Compose 1.7 captureToImage waits for a real VSYNC redraw that Robolectric cannot emit.
        // Use the same Window PixelCopy backend after Compose is idle; Robolectric's native
        // PixelCopy shadow renders synchronously. Newer AndroidX uses this same bypass.
        lateinit var bitmap: Bitmap
        var result = -1
        compose.runOnIdle {
            val window = if (dialog) checkNotNull(ShadowDialog.getLatestDialog()?.window) else activity.window
            val view = window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(window, bitmap, { result = it }, Handler(Looper.getMainLooper()))
        }
        assertEquals("Native PixelCopy must succeed", PixelCopy.SUCCESS, result)
        val output = File("build/reports/ui/$name.png")
        checkNotNull(output.parentFile).mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Dialogs intentionally wrap a short confirmation; only full screens need phone height.
        assertTrue("Screenshot must have visible bounds", bitmap.width >= 411 && bitmap.height >= if (dialog) 160 else 800)
        val colors = mutableSetOf<Int>()
        for (x in 0 until bitmap.width step 31) {
            for (y in 0 until bitmap.height step 31) colors += bitmap.getPixel(x, y)
        }
        assertTrue("Native renderer returned a blank screenshot", colors.size > 12)
        return bitmap.getPixel(2, bitmap.height / 2)
    }
}
