package com.jiligulu.app.ui

import android.graphics.Bitmap
import android.content.Intent
import androidx.activity.compose.setContent
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTouchInput
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
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

    @Test(timeout = 120_000)
    fun homeDetailsStatisticsAndSettingsRenderInBothThemes() {
        val app = RuntimeEnvironment.getApplication() as JiliguluApp
        runBlocking {
            val container = app.container
            container.userPrefs.setNickname("路陌")
            container.userPrefs.setThemeMode(UserPrefs.THEME_LIGHT)
            container.userPrefs.setWaterEnabled(false)
            container.userPrefs.setUpdateRepository("")
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
            scenario.onActivity { activity = it }
            awaitText("账本")
            awaitText("牛肉面")
            compose.onNodeWithText("叽里咕噜").assertIsDisplayed()
            compose.onNodeWithText("统计").assertIsDisplayed()
            compose.onNodeWithContentDescription("设置").assertIsDisplayed()
            val lightBackground = capture("home-light")
            captureLauncherIcon()

            compose.onNodeWithText("记一笔").performClick()
            awaitText("保存这一笔")
            capture("manual-entry-light")
            compose.onNodeWithText("账单时间").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("选择日期").assertIsDisplayed()
            compose.onNodeWithText("选择时间").assertIsDisplayed()
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
            compose.onNodeWithText("账单名称").assertIsDisplayed()
            capture("bill-detail-light", dialog = true)
            compose.onNodeWithText("关闭").performClick()

            compose.onNodeWithText("统计").performClick()
            awaitText("收支统计")
            openSampleBill()
            awaitText("保存修改")
            compose.onNodeWithText("关闭").performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            capture("statistics-light")

            compose.onNodeWithContentDescription("设置").performClick()
            awaitText("保存设置")
            capture("settings-light")
            scrollSettingsTo("阿噜使用手册")
            compose.onNodeWithText("阿噜使用手册").performClick()
            awaitText("阿噜使用手册 ♡")
            awaitText("见面啦，我是阿噜")
            capture("handbook-light", dialog = true)
            compose.onNodeWithText("知道啦").performClick()
            compose.onNodeWithText("阿噜使用手册 ♡").assertDoesNotExist()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("账本").performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)

            runBlocking {
                app.container.userPrefs.setThemeMode(UserPrefs.THEME_DARK)
                app.container.userPrefs.themeMode.first { it == UserPrefs.THEME_DARK }
            }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(500)
            val darkBackground = capture("home-dark")
            assertNotEquals("Theme preference must change the rendered background", lightBackground, darkBackground)
            compose.onNodeWithContentDescription("设置").performClick()
            awaitText("保存设置")
            capture("settings-dark")
            compose.onNodeWithContentDescription("返回").performClick()
            awaitText("对话记账")
            compose.onNodeWithText("对话记账").performClick()
            awaitText("历史记录验收")
            capture("chat-dark")
            compose.onNodeWithContentDescription("返回").performClick()
            awaitText("账本")
            compose.onNodeWithContentDescription("设置").performClick()
            awaitText("保存设置")
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
            compose.onNodeWithContentDescription("设置").performClick()
            awaitText("保存设置")
            scrollSettingsTo("重看初次见面")
            compose.waitForIdle()
            compose.onNodeWithText("重看初次见面").performClick()
            awaitText("你的名字")
            capture("welcome-preview")
            compose.onNodeWithText("继续看动画").performClick()
            compose.mainClock.advanceTimeBy(4_000)
            awaitText("保存设置")
            assertEquals("Welcome preview must not overwrite the user's profile", "路陌",
                runBlocking { app.container.userPrefs.nickname.first() })
            scrollSettingsTo("检查更新")
            compose.waitForIdle()
            // 内置默认更新源后，检查更新按钮应始终可点击（不再依赖手动配置仓库）
            compose.onNodeWithText("检查更新").assertIsEnabled()
            capture("update-settings")

            scrollSettingsTo("历史对话", towardTop = true)
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
            scenario.onActivity { activity = it }
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
            scenario.onActivity { activity = it }
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
        val visible = androidx.compose.runtime.mutableStateOf(true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    activity = it
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
                scenario.onActivity { activity = it }
                awaitText("对话记账")
                assertTrue(app.container.startupCompleted)
                compose.onNodeWithContentDescription("设置").assertIsDisplayed()
                compose.runOnIdle { activity.setContent {} }
            }
        }
    }

    @Test(timeout = 90_000)
    fun announcementAndVoiceComposerSupportReviewBeforeSending() {
        val app = RuntimeEnvironment.getApplication() as JiliguluApp
        val prefs = app.container.userPrefs
        val fixture = """{"announcements":[{"id":"holiday-demo","title":"中秋快乐，记得好好吃饭","summary":"阿噜寄来一封小小的节日来信","emoji":"🌕","body":"愿你的日子像月亮一样圆满。\n忙碌之余，也记得给自己留一点甜。\n\n这是一条仅用于界面验收的公告。"}]}"""
        val notices = com.jiligulu.app.data.announcement.AnnouncementRepository(prefs, { fixture })
        runBlocking {
            prefs.setPreferVoiceInput(false)
            prefs.setNickname("验收")
            prefs.setWaterEnabled(false)
            prefs.setUpdateRepository("")
            prefs.setAnnouncementSource("https://example.test/feed.json")
            notices.initialize()
            prefs.setAnnouncementSource("") // The actual app container must not make network requests in this UI test.
        }
        lateinit var callback: com.jiligulu.app.ui.voice.SpeechInputEngine.Listener
        val voice = com.jiligulu.app.ui.voice.SpeechInputController {
            object : com.jiligulu.app.ui.voice.SpeechInputEngine {
                override fun start(listener: com.jiligulu.app.ui.voice.SpeechInputEngine.Listener) { callback = listener; listener.ready() }
                override fun stop() { callback.result("水，3") }
                override fun cancel() = Unit
                override fun destroy() = Unit
            }
        }
        val input = androidx.compose.runtime.mutableStateOf("原有文字")
        val sent = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                activity = it
                it.setContent {
                    GuluTheme {
                        val state = notices.state.collectAsStateWithLifecycle().value
                        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
                            com.jiligulu.app.ui.announcement.AnnouncementBoard(state, notices::open)
                            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.weight(1f))
                            com.jiligulu.app.ui.voice.VoiceComposer(input.value, { input.value = it },
                                { sent += input.value }, ready = true, sending = false, suppliedController = voice)
                        }
                        com.jiligulu.app.ui.announcement.AnnouncementDialogHost(notices, enabled = true)
                    }
                }
            }
            awaitTag("announcement-body")
            capture("announcement-popup", dialog = true)
            compose.onNodeWithText("关闭").performClick()
            awaitTag("announcement-body", present = false)
            compose.onNodeWithTag("announcement-board").performClick()
            awaitTag("announcement-body")
            compose.onNodeWithText("这条不再弹出").performClick()
            awaitTag("announcement-body", present = false)
            assertTrue(runBlocking { "holiday-demo" in prefs.readAnnouncements().mutedIds })
            compose.onNodeWithTag("announcement-board").assertIsDisplayed()
            capture("announcement-home-card")
            compose.onNodeWithTag("voice-toggle").performClick()
            awaitText("按住说话")
            compose.onNodeWithTag("voice-hold").performTouchInput { down(center) }
            awaitText("正在听，松开结束")
            capture("voice-listening")
            compose.onNodeWithTag("voice-hold").performTouchInput { up() }
            awaitTag("chat-input")
            compose.onNodeWithTag("chat-input").assertTextEquals("原有文字 水，3")
            assertTrue(sent.isEmpty())
            compose.onNodeWithTag("chat-input").performTextReplacement("水，3.50")
            capture("voice-editable-text")
            compose.onNodeWithContentDescription("发送").performClick()
            assertEquals(listOf("水，3.50"), sent)
            awaitText("按住说话")
            assertTrue(runBlocking { prefs.preferVoiceInput.first() })
            compose.runOnIdle { activity.setContent {} }
        }
    }

    @Test(timeout = 60_000)
    fun voicePreferenceAndEmptyMailboxSurviveComposerReopening() {
        val app = RuntimeEnvironment.getApplication() as JiliguluApp
        val prefs = app.container.userPrefs
        runBlocking {
            prefs.setNickname("验收")
            prefs.setWaterEnabled(false)
            prefs.setUpdateRepository("")
            prefs.setAnnouncementSource("")
            prefs.setPreferVoiceInput(true)
            app.container.announcements.initialize()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity = it }
            awaitText("对话记账")
            compose.onNodeWithTag("announcement-board").performClick()
            awaitTag("announcement-empty")
            compose.onNodeWithText("收好信笺").performClick()
            awaitTag("announcement-empty", present = false)
            compose.onNodeWithText("对话记账").performClick()
            awaitText("按住说话")
            compose.onNodeWithContentDescription("返回").performClick()
            awaitText("对话记账")
            compose.onNodeWithText("对话记账").performClick()
            awaitText("按住说话")
            compose.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                val toggle = compose.onAllNodesWithTag("voice-toggle").fetchSemanticsNodes().singleOrNull()
                toggle != null && !toggle.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
            compose.onNodeWithTag("voice-toggle").performClick()
            awaitTag("chat-input")
            assertEquals(false, runBlocking { prefs.preferVoiceInput.first() })
            compose.onNodeWithContentDescription("返回").performClick()
            awaitText("对话记账")
            compose.onNodeWithText("对话记账").performClick()
            awaitTag("chat-input")
            compose.onNodeWithTag("voice-hold").assertDoesNotExist()
            compose.runOnIdle { activity.setContent {} }
        }
    }

    private fun awaitTag(tag: String, present: Boolean = true) {
        try {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() == present
        }
        compose.waitForIdle()
        } catch (failure: Throwable) {
            File("build/reports/ui/failed-tag.txt").writeText("$tag present=$present\n" + compose.onRoot().printToString())
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
                compose.onAllNodesWithTag("startup-animation").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            runCatching { capture("failure-screen") }
            val tree = runCatching { compose.onRoot().printToString() }.getOrElse { "Unable to read semantics: $it" }
            val flowState = runCatching { runBlocking {
                withTimeout(2_000) {
                    val app = activity.application as JiliguluApp
                    "nickname=${app.container.userPrefs.nickname.first()}, theme=${app.container.userPrefs.themeMode.first()}, " +
                        "sameApplication=${app === RuntimeEnvironment.getApplication()}, main=${Dispatchers.Main}, clock=${compose.mainClock.currentTime}"
                }
            } }.getOrElse { "Preference read failed: $it" }
            File("build/reports/ui/failure-semantics.txt").apply { parentFile?.mkdirs() }
                .writeText("Waiting for '$text'\n$flowState\n$tree\n$failure\n" +
                    org.robolectric.shadows.ShadowLog.getLogsForTag("ConversationHistory").joinToString("\n") { it.throwable?.stackTraceToString().orEmpty() })
            throw AssertionError("Waiting for '$text': $flowState\n$tree", failure)
        }
        compose.waitForIdle()
    }

    private fun openSampleBill() {
        // Both screens place this seeded ledger in section 4. A single bounded scroll followed
        // by an idle wait lets StandardTestDispatcher finish it; Compose 1.7's search-and-scroll
        // loop assumes an unconfined continuation and can spin without advancing queued work.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(4)
        awaitText("牛肉面")
        compose.onAllNodesWithText("牛肉面").onFirst().performClick()
    }

    private fun awaitWaterCup() {
        compose.waitUntil(15_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            compose.onAllNodesWithContentDescription(WAITING_CUP).fetchSemanticsNodes().size == 1 &&
                compose.onAllNodesWithTag("drinking-animation").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }

    private fun scrollSettingsTo(text: String, towardTop: Boolean = false) {
        // A bounded single action + a frame avoids Compose 1.7's synchronous search-scroll loop.
        repeat(12) {
            if (runCatching { compose.onNodeWithText(text).assertIsDisplayed() }.isSuccess) return
            compose.onNodeWithTag("settings-list").performSemanticsAction(SemanticsActions.ScrollBy) {
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
