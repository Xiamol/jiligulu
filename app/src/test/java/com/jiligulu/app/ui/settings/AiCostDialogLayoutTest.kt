package com.jiligulu.app.ui.settings

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.unit.Density
import com.jiligulu.app.core.ai.AiUsagePurpose
import com.jiligulu.app.ui.theme.GuluTheme
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiCostDialogLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val day = LocalDate.of(2026, 10, 9)

    @Test fun overviewShowsTotalAndSelectedDailyMoneyWithCacheOnItsOwnLineAndFitsWithoutScrolling() {
        show()
        compose.onNodeWithText("累计花费").assertExists()
        compose.onNodeWithTag("ai-cost-total").assertTextEquals("¥0.001")
        compose.onNodeWithTag("ai-cost-daily").assertTextEquals("¥0.001")
        compose.onNodeWithText("90%").assertExists()
        compose.onNodeWithTag("ai-cost-day-details").assertDoesNotExist()
        compose.onNodeWithText("关闭").assertDoesNotExist()
        compose.onNodeWithTag("ai-cost-category-LEDGER_CHAT").assertDoesNotExist()
        val panel = compose.onNodeWithTag("ai-cost-panel").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("ai-cost-actions").fetchSemanticsNode().boundsInRoot
        val chart = compose.onNodeWithTag("ai-cost-chart").fetchSemanticsNode().boundsInRoot
        val money = compose.onNodeWithTag("ai-cost-total").fetchSemanticsNode().boundsInRoot
        val daily = compose.onNodeWithTag("ai-cost-daily").fetchSemanticsNode().boundsInRoot
        val rate = compose.onNodeWithTag("ai-cost-cache-rate").fetchSemanticsNode().boundsInRoot
        assertTrue(chart.height in 75f..100f)
        assertTrue(chart.top >= panel.top && chart.bottom < footer.top)
        assertTrue(money.height >= 28f && daily.height >= 28f)
        assertEquals(money.top, daily.top, 1f)
        assertTrue("cache must remain visible on its own line", rate.top >= maxOf(money.bottom, daily.bottom))
        assertTrue(rate.height >= 20f && rate.bottom < chart.top)
        val scrollRange = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().single().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        assertNotNull(scrollRange)
        assertEquals("panel=$panel footer=$footer chart=$chart money=$money daily=$daily rate=$rate", 0f, scrollRange!!.maxValue(), 1f)
        assertFixedActionsInsidePanel()
    }

    @Test fun purposeAndDayDetailsReplaceTheOverviewAndPreserveTheFixedActions() {
        show()
        val initialFooter = compose.onNodeWithTag("ai-cost-actions").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("ai-cost-purpose-action").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("记账聊天").assertExists()
        compose.onNodeWithTag("ai-cost-category-money-LEDGER_CHAT").assertTextEquals("¥0.001")
        compose.onNodeWithTag("ai-cost-category-rate-LEDGER_CHAT").assertTextEquals("90%")
        compose.onNodeWithTag("ai-cost-chart").assertDoesNotExist()
        compose.onNodeWithTag("ai-cost-day-details").assertDoesNotExist()
        compose.onNodeWithTag("ai-cost-day-action").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("ai-cost-day-details").assertExists()
        compose.onNodeWithText("当日 ¥0.001 · 1 次").assertExists()
        compose.onNodeWithTag("ai-cost-category-money-LEDGER_CHAT").assertTextEquals("¥0.001")
        compose.onNodeWithTag("ai-cost-back").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("ai-cost-chart").assertExists()
        assertEquals(initialFooter, compose.onNodeWithTag("ai-cost-actions").fetchSemanticsNode().boundsInRoot)
        assertFixedActionsInsidePanel()
    }

    @Test fun emptyChartExplainsTheEmptyStateAndUnreportedCacheDoesNotShowZeroPercent() {
        show(CostView())
        compose.onNodeWithTag("ai-cost-empty-chart").assertExists()
        compose.onNodeWithText("暂无请求").assertExists()
        compose.onNodeWithText("0%").assertDoesNotExist()
        assertFixedActionsInsidePanel()
    }

    @Test fun undatedLegacyHistoryStaysVisibleWithoutInventingDailyCosts() {
        val legacy = costGroup(hit = 0, miss = 0, known = null, unknown = 8).copy(day = null, calls = 8, purpose = AiUsagePurpose.UNSPECIFIED,
            legacyCalls = 8, legacyEstimatePico = 2_000_000_000,
            cacheReportedCalls = 0, cacheHitReportedCalls = 0, cacheMissReportedCalls = 0)
        show(CostView(listOf(costGroup()), listOf(costGroup(), legacy), undatedCalls = 8))
        compose.onNodeWithTag("ai-cost-total").assertTextEquals("¥0.003")
        compose.onNodeWithTag("ai-cost-daily").assertTextEquals("¥0.001")
        compose.onNodeWithText("含旧版参考估算").assertExists()
        compose.onNodeWithText("本月 ¥0.001").assertExists()
        compose.onNodeWithText("10/9 已知 ¥0.001").assertDoesNotExist()
        compose.onNodeWithTag("ai-cost-purpose-action").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("ai-cost-undated-history").assertExists()
        compose.onNodeWithText("累计 ¥0.003 · 9 次").assertExists()
        compose.onNodeWithText("历史汇总").assertExists()
        compose.onNodeWithTag("ai-cost-category-money-UNSPECIFIED").assertTextEquals("¥0.002")
        assertFixedActionsInsidePanel()
    }

    @Test fun aDatedUnpricedRequestShowsItsCallCountInsteadOfDisappearingAsZeroCost() {
        val unpriced = costGroup(known = null, unknown = 1)
        show(CostView(listOf(unpriced), listOf(unpriced)))
        compose.onNodeWithTag("ai-cost-daily").assertTextEquals("未知")
        compose.onNodeWithText("10/9 花费").assertExists()
        compose.onNodeWithText("1 次 · 含未知").assertExists()
        compose.onNodeWithTag("ai-cost-chart").assertExists()
        compose.onNodeWithText("10/9 已知 ¥0").assertDoesNotExist()
        assertFixedActionsInsidePanel()
    }

    @Test fun wholeMonthWithAllSixPurposesStillFitsAndShowsTheSelectedDaysAmount() {
        val rows = AiUsagePurpose.entries.map { costGroup().copy(purpose = it) }
        show(CostView(rows, rows))
        compose.onNodeWithTag("ai-cost-daily").assertTextEquals("¥0.006")
        compose.onNodeWithText("本月 ¥0.006").assertExists()
        for (label in listOf("聊天", "识图", "分类", "回信", "小六壬", "其它")) compose.onNodeWithText(label).assertExists()
        val footer = compose.onNodeWithTag("ai-cost-actions").fetchSemanticsNode().boundsInRoot
        val selected = compose.onNodeWithTag("ai-cost-selected-day").fetchSemanticsNode().boundsInRoot
        assertTrue(selected.bottom < footer.top)
        val scrollRange = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().single().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        assertEquals(0f, scrollRange!!.maxValue(), 1f)
        assertFixedActionsInsidePanel()
    }

    @Test fun wholeMonthChartIncludesTheFirstAndLastDayAndUsesOnlyMonthNavigation() {
        val dates = mutableListOf<LocalDate>()
        val months = mutableListOf<YearMonth>()
        show(onSelectDay = { dates.add(it) }, onMonth = { months.add(it) })
        compose.onNodeWithText("10/1—10/31").assertExists()
        compose.onNodeWithTag("ai-cost-period-7").assertDoesNotExist()
        compose.onNodeWithTag("ai-cost-period-30").assertDoesNotExist()
        compose.onNodeWithText("7日").assertDoesNotExist()
        compose.onNodeWithText("30日").assertDoesNotExist()
        compose.onNodeWithTag("ai-cost-chart").performTouchInput {
            click(Offset(1f, height / 2f))
            click(Offset(width - 1f, height / 2f))
        }
        compose.onNodeWithTag("ai-cost-month-prev").performClick()
        compose.onNodeWithTag("ai-cost-month-next").performClick()
        compose.runOnIdle {
            assertEquals(listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)), dates)
            assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10)), months)
        }
        assertFixedActionsInsidePanel()
    }

    @Test fun datedLegacyEstimateIsVisibleInBothLargeMoneyFiguresWithoutClaimingKnownPricing() {
        val legacy = costGroup(known = null, unknown = 1).copy(legacyCalls = 1, legacyEstimatePico = 3_000_000_000)
        show(CostView(listOf(legacy), listOf(legacy)))
        compose.onNodeWithTag("ai-cost-total").assertTextEquals("¥0.003")
        compose.onNodeWithTag("ai-cost-daily").assertTextEquals("¥0.003")
        compose.onNodeWithText("含旧版参考估算").assertExists()
        compose.onNodeWithText("本月 ¥0.003").assertExists()
        assertFixedActionsInsidePanel()
    }

    @Test fun purposeTableShowsEachCostAndRateWithHistorySeparateAndFitsInOneScreen() {
        val current = AiUsagePurpose.entries.filter { it != AiUsagePurpose.UNSPECIFIED }.mapIndexed { index, purpose ->
            costGroup(hit = if (purpose == AiUsagePurpose.IMAGE_RECOGNITION) 100 else 900,
                miss = if (purpose == AiUsagePurpose.IMAGE_RECOGNITION) 900 else 100,
                known = (index + 1) * 1_000_000_000L).copy(purpose = purpose)
        }
        val legacy = costGroup(hit = 40, miss = 60, known = null, unknown = 8).copy(purpose = AiUsagePurpose.UNSPECIFIED,
            calls = 8, legacyCalls = 8, legacyEstimatePico = 7_000_000_000, cacheReportedCalls = 0,
            cacheHitReportedCalls = 0, cacheMissReportedCalls = 0)
        show(CostView(current, current + legacy, undatedCalls = 8))
        compose.onNodeWithTag("ai-cost-purpose-action").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("历史汇总").assertExists()
        compose.onNodeWithText("用途未知").assertDoesNotExist()
        compose.onNodeWithTag("ai-cost-category-money-LEDGER_CHAT").assertTextEquals("¥0.001")
        compose.onNodeWithTag("ai-cost-category-rate-LEDGER_CHAT").assertTextEquals("90%")
        compose.onNodeWithTag("ai-cost-category-money-IMAGE_RECOGNITION").assertTextEquals("¥0.002")
        compose.onNodeWithTag("ai-cost-category-rate-IMAGE_RECOGNITION").assertTextEquals("10%")
        compose.onNodeWithTag("ai-cost-category-money-UNSPECIFIED").assertTextEquals("¥0.007")
        compose.onNodeWithTag("ai-cost-category-rate-UNSPECIFIED").assertTextEquals("40%")
        compose.onNodeWithText("部分").assertExists()
        compose.onNodeWithText("完整报告").assertDoesNotExist()
        assertTrue(compose.onAllNodesWithText("tokens", substring = true).fetchSemanticsNodes().isEmpty())
        val footer = compose.onNodeWithTag("ai-cost-actions").fetchSemanticsNode().boundsInRoot
        for (purpose in AiUsagePurpose.entries) {
            val row = compose.onNodeWithTag("ai-cost-category-${purpose.name}").fetchSemanticsNode().boundsInRoot
            val money = compose.onNodeWithTag("ai-cost-category-money-${purpose.name}").fetchSemanticsNode().boundsInRoot
            val rate = compose.onNodeWithTag("ai-cost-category-rate-${purpose.name}").fetchSemanticsNode().boundsInRoot
            assertTrue("$purpose must remain above fixed actions", row.bottom < footer.top)
            assertEquals("primary values must align even when cache has a partial note", money.top, rate.top, 1f)
        }
        val range = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().single().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        assertEquals(0f, range!!.maxValue(), 1f)
        assertFixedActionsInsidePanel()
    }

    @Test fun dayDetailsCombineModelsAndShowUnknownCacheAsADashWithoutTokenBreakdowns() {
        val first = costGroup()
        val second = costGroup(hit = 0, miss = 100, known = 2_000_000_000).copy(providerKey = "second", model = "second-model")
        val image = costGroup(hit = 0, miss = 0, known = 4_000_000_000).copy(purpose = AiUsagePurpose.IMAGE_RECOGNITION,
            cacheReportedCalls = 0, cacheHitReportedCalls = 0, cacheMissReportedCalls = 0)
        show(CostView(listOf(first, second, image), listOf(first, second, image)))
        compose.onNodeWithTag("ai-cost-day-action").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("当日 ¥0.007 · 3 次").assertExists()
        compose.onNodeWithTag("ai-cost-category-money-LEDGER_CHAT").assertTextEquals("¥0.003")
        compose.onNodeWithTag("ai-cost-category-rate-LEDGER_CHAT").assertTextEquals("81.8%")
        compose.onNodeWithTag("ai-cost-category-money-IMAGE_RECOGNITION").assertTextEquals("¥0.004")
        compose.onNodeWithTag("ai-cost-category-rate-IMAGE_RECOGNITION").assertTextEquals("—")
        compose.onNodeWithText("未报告").assertExists()
        for (text in listOf("tokens", "未命中输入", "输出", "未知计价", "Fixture")) {
            assertTrue("$text must not clutter daily cost rows", compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty())
        }
        assertFixedActionsInsidePanel()
    }

    @Test fun missingModernPricesKeepNamedCategoriesAndRecoveredHistoryMoneyVisible() {
        val unpriced = AiUsagePurpose.entries.filter { it != AiUsagePurpose.UNSPECIFIED }.map {
            costGroup(hit = 0, miss = 0, known = null, unknown = 1).copy(purpose = it,
                cacheReportedCalls = 0, cacheHitReportedCalls = 0, cacheMissReportedCalls = 0)
        }
        val history = costGroup(known = null, unknown = 1).copy(purpose = AiUsagePurpose.UNSPECIFIED,
            legacyCalls = 1, legacyEstimatePico = 8_000_000_000)
        show(CostView(unpriced, unpriced + history))
        compose.onNodeWithTag("ai-cost-purpose-action").performSemanticsAction(SemanticsActions.OnClick) { it() }
        for (purpose in AiUsagePurpose.entries.filter { it != AiUsagePurpose.UNSPECIFIED }) {
            compose.onNodeWithText(purpose.label).assertExists()
            compose.onNodeWithTag("ai-cost-category-money-${purpose.name}").assertTextEquals("未知")
            compose.onNodeWithTag("ai-cost-category-rate-${purpose.name}").assertTextEquals("—")
        }
        compose.onNodeWithText("历史汇总").assertExists()
        compose.onNodeWithTag("ai-cost-category-money-UNSPECIFIED").assertTextEquals("¥0.008")
        compose.onNodeWithText("用途未知").assertDoesNotExist()
        val range = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().single().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        assertEquals(0f, range!!.maxValue(), 1f)
        assertFixedActionsInsidePanel()
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-port-mdpi")
    fun narrowScreenWithLargerTextKeepsNavigationAndActionsInsideThePanel() {
        show(fontScale = 1.3f)
        assertFixedActionsInsidePanel()
        val panel = compose.onNodeWithTag("ai-cost-panel").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("ai-cost-scope-0", "ai-cost-scope-1", "ai-cost-scope-2", "ai-cost-month-prev", "ai-cost-month-next")) {
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must not spill beyond paper", bounds.left >= panel.left && bounds.right <= panel.right + 1f)
            assertTrue("$tag must keep a usable height", bounds.height >= 28f)
        }
    }

    private fun show(view: CostView = CostView(listOf(costGroup()), listOf(costGroup())), fontScale: Float = 1f,
        onSelectDay: (LocalDate) -> Unit = {}, onMonth: (YearMonth) -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                GuluTheme(darkTheme = false) {
                    var month by remember { mutableStateOf(YearMonth.from(day)) }
                    AiCostOverviewDialog(view, month, 0, true, day,
                        onMonth = { month = it; onMonth(it) }, onScope = {}, onSelectDay = onSelectDay, onPrices = {}, onHelp = {}, onDismiss = {}) {
                        CostDayDetails(view.daily.filter { it.day == day.toString() })
                    }
                }
            }
        }
    }

    private fun assertFixedActionsInsidePanel() {
        val panel = compose.onNodeWithTag("ai-cost-panel").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("ai-cost-actions").fetchSemanticsNode().boundsInRoot
        assertTrue(footer.bottom <= panel.bottom && footer.height >= 40f)
        var previousRight = panel.left
        for (tag in listOf("ai-cost-purpose-action", "ai-cost-day-action", "ai-price-editor", "ai-cost-help")) {
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must stay inside the footer", bounds.top >= footer.top && bounds.bottom <= footer.bottom + 1f)
            assertTrue("$tag must not overlap its neighbor", bounds.left >= previousRight - 1f)
            assertTrue("$tag must stay inside paper", bounds.right <= panel.right + 1f)
            previousRight = bounds.right
        }
    }
}
