package com.jiligulu.app.ui

import android.graphics.Bitmap
import android.content.Intent
import androidx.activity.compose.setContent
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.jiligulu.app.data.reminder.WaterReminderWorker
import com.jiligulu.app.ui.startup.StartupScreen
import com.jiligulu.app.ui.theme.GuluTheme
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.MainActivity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.ChatMessageEntity
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
            val food = container.categoryRepository.getAll().first { it.name == "eating" }.id
            val drinks = container.categoryRepository.getAll().first { it.name == "drinking" }.id
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
                    Intent(activity.intent).putExtra(WaterReminderWorker.EXTRA_WATER_REMINDER, cup.id))
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

    private fun awaitText(text: String) {
        try {
            compose.waitUntil(15_000) {
                // DataStore resumes on Android's Handler/Choreographer, independently of the
                // Compose test clock. Advance that paused Looper too, so a late I/O completion
                // can publish its next frame instead of leaving the first loading composition.
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                compose.onAllNodesWithTag("startup-animation").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
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
                .writeText("Waiting for '$text'\n$flowState\n$tree\n$failure")
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

    private fun scrollSettingsTo(text: String) {
        // A bounded single action + a frame avoids Compose 1.7's synchronous search-scroll loop.
        repeat(12) {
            if (runCatching { compose.onNodeWithText(text).assertIsDisplayed() }.isSuccess) return
            compose.onNodeWithTag("settings-list").performSemanticsAction(SemanticsActions.ScrollBy) {
                it(0f, 400f)
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
        assertTrue("Screenshot must have phone-sized bounds", bitmap.width >= 411 && bitmap.height >= 800)
        val colors = mutableSetOf<Int>()
        for (x in 0 until bitmap.width step 31) {
            for (y in 0 until bitmap.height step 31) colors += bitmap.getPixel(x, y)
        }
        assertTrue("Native renderer returned a blank screenshot", colors.size > 12)
        return bitmap.getPixel(2, bitmap.height / 2)
    }
}
