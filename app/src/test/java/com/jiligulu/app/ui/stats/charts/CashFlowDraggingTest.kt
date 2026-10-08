package com.jiligulu.app.ui.stats.charts

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.jiligulu.app.ui.components.forwardMainPageSwipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h640dp-port-mdpi")
@OptIn(ExperimentalTestApi::class)
class CashFlowDraggingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun directDateApiInitiallyShowsTheSelectionAndUsesTheSuppliedTodayDate() {
        val month = YearMonth.of(2026, 9)
        val selected = month.atDay(24).millis()
        val supplied = (1..30).map { day ->
            DayBar(day, month.atDay(day).millis(), if (day % 3 == 0) day * 120L else day * 60L, day == 24)
        }
        var explicitToday by mutableStateOf<Long?>(null)
        compose.setContent {
            MaterialTheme {
                CashFlowBarChart(supplied, selected, {}, Color.Red, Color.LightGray,
                    Modifier.fillMaxWidth().height(202.dp), todayMillis = explicitToday)
            }
        }
        compose.onNode(hasContentDescription("9月24日，28.8元，已选中") and hasText("今天")).assertIsDisplayed()
        compose.runOnIdle { explicitToday = month.atDay(25).millis() }
        compose.onNode(hasContentDescription("9月25日，15元") and hasText("今天")).assertIsDisplayed()
        compose.onNodeWithContentDescription("9月24日，28.8元，已选中").assertIsDisplayed()
    }

    @Test fun externallyRetainedViewportKeepsItsFractionalOffsetAfterTheChartIsUnmounted() {
        val first = LocalDate.of(2026, 12, 27)
        val today = LocalDate.of(2026, 12, 31)
        var anchor by mutableStateOf(CashFlowChartAnchor(first, YearMonth.from(today)))
        var showBars by mutableStateOf(true)
        var retained: CashFlowViewport? = null
        compose.setContent {
            val viewport = rememberCashFlowViewport(anchor)
            SideEffect { retained = viewport }
            MaterialTheme {
                if (showBars) CashFlowBarChart(bars(), today.millis(), {}, Color.Red, Color.LightGray,
                    Modifier.fillMaxWidth().height(202.dp), anchor = anchor, viewport = viewport,
                    todayMillis = today.millis(), onCompactViewport = { start, _, _ ->
                        anchor = anchor.copy(firstDay = start.date())
                    })
                else Box(Modifier.fillMaxWidth().height(202.dp)) { Text("折线视图") }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("cash-flow-day-strip").performTouchInput {
            down(Offset(width * .8f, height * .5f))
            moveTo(Offset(width * .6f, height * .5f), delayMillis = 250)
            // Stop the finger before release so this checks a partial slot, not an ongoing fling.
            moveTo(Offset(width * .6f, height * .5f), delayMillis = 150)
            up()
        }
        compose.waitForIdle()
        var before: Pair<Int, Int>? = null
        compose.runOnIdle {
            val state = requireNotNull(retained).days
            before = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
            assertTrue("The fixture must retain a real partially visible slot", state.firstVisibleItemScrollOffset > 0)
            showBars = false
        }
        compose.waitForIdle()
        compose.onNodeWithText("折线视图").assertIsDisplayed()
        compose.runOnIdle { showBars = true }
        compose.waitForIdle()
        compose.runOnIdle {
            val state = requireNotNull(retained).days
            assertEquals(before, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
        }
    }

    @Test fun compactDatesMoveBeforeReleaseAndDataOrSelectionUpdatesNeverReanchor() {
        val first = LocalDate.of(2026, 12, 27)
        val today = LocalDate.of(2026, 12, 31)
        var anchor by mutableStateOf(CashFlowChartAnchor(first, YearMonth.from(today)))
        var selected by mutableStateOf(today.millis())
        var compressed by mutableStateOf(false)
        var loadedBars by mutableStateOf(bars())
        var visible: Pair<Long, Long>? = null
        var bounds: Rect? = null
        var parentDrag = 0f
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().forwardMainPageSwipe(enabled = { true },
                    onDrag = { parentDrag += it }, onDragEnd = {}, allowRight = true,
                    startAllowed = { bounds?.contains(it) != true })) {
                    CashFlowBarChart(loadedBars, selected, {
                        selected = it
                        anchor = anchor.copy(month = YearMonth.from(it.date()))
                    }, Color.Red, Color.LightGray,
                        Modifier.fillMaxWidth().height(202.dp).onGloballyPositioned { bounds = it.boundsInRoot() },
                        compressedMonth = compressed, anchor = anchor, todayMillis = today.millis(),
                        onCompactViewport = { start, end, moving ->
                            visible = start to end
                            anchor = anchor.copy(firstDay = start.date(),
                                month = if (moving) YearMonth.from(start.date()) else anchor.month)
                        })
                }
            }
        }
        compose.waitForIdle()
        val strip = compose.onNodeWithTag("cash-flow-day-strip")
        val axis = compose.onNodeWithTag("cash-flow-y-axis").getUnclippedBoundsInRoot()
        strip.performTouchInput {
            down(Offset(width * .9f, height * .5f))
            moveTo(Offset(width * .18f, height * .5f), delayMillis = 250)
        }
        var beforeData: Pair<Long, Long>? = null
        compose.runOnIdle {
            beforeData = requireNotNull(visible)
            assertTrue(requireNotNull(visible).first.date().isAfter(first))
            assertTrue(requireNotNull(visible).first.date().year == 2027)
            assertEquals(0f, parentDrag)
            // A repository emission and a viewport metadata update must preserve the drag.
            loadedBars = loadedBars.mapIndexed { index, bar -> if (index == 0) bar.copy(amountFen = 200) else bar }
        }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { assertEquals(beforeData, visible) }
        assertEquals(axis, compose.onNodeWithTag("cash-flow-y-axis").getUnclippedBoundsInRoot())
        strip.performTouchInput { up() }
        compose.waitForIdle()
        val range = requireNotNull(visible)
        val target = range.first.date().plusDays(1)
        compose.onNodeWithContentDescription("${target.monthValue}月${target.dayOfMonth}日，1元").performClick()
        compose.runOnIdle {
            assertEquals(target.millis(), selected)
            assertEquals(range, visible)
            assertEquals(0f, parentDrag)
            compressed = true
        }
        compose.waitForIdle()
        compose.onNodeWithTag("cash-flow-month-pager").assertIsDisplayed()
        compose.runOnIdle { compressed = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(range, visible); assertEquals(target.millis(), selected) }
        strip.performTouchInput {
            down(Offset(width * .15f, height * .5f))
            moveTo(Offset(width * .85f, height * .5f), delayMillis = 250)
        }
        compose.runOnIdle {
            assertTrue(requireNotNull(visible).first < range.first)
            assertEquals(0f, parentDrag)
        }
        strip.performTouchInput { up() }
    }

    @Test fun monthPagerRevealsAdjacentRealMonthBeforeReleaseAndCrossesTheNewYear() {
        val today = LocalDate.of(2026, 12, 31)
        var anchor by mutableStateOf(CashFlowChartAnchor(today.minusDays(9), YearMonth.from(today)))
        var selected by mutableStateOf(today.millis())
        var reportedMonth: YearMonth? = null
        compose.setContent {
            MaterialTheme {
                CashFlowBarChart(bars(), selected, { selected = it }, Color.Red, Color.LightGray,
                    Modifier.fillMaxWidth().height(202.dp), compressedMonth = true, anchor = anchor,
                    todayMillis = today.millis(), onMonthViewport = { start, moving ->
                        val month = YearMonth.from(start.date())
                        reportedMonth = month
                        if (moving) {
                            anchor = anchor.copy(month = month)
                            selected = month.atDay(31.coerceAtMost(month.lengthOfMonth())).millis()
                        }
                    })
            }
        }
        compose.waitForIdle()
        val pager = compose.onNodeWithTag("cash-flow-month-pager")
        pager.performTouchInput {
            down(Offset(width * .9f, height * .5f))
            moveTo(Offset(width * .15f, height * .5f), delayMillis = 250)
        }
        compose.runOnIdle { assertEquals(YearMonth.of(2027, 1), reportedMonth) }
        // Both are actual page contents while the finger is still down.
        compose.onNodeWithContentDescription("12月31日，1元").assertIsDisplayed()
        compose.onNodeWithContentDescription("1月1日，1元").assertIsDisplayed()
        pager.performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(YearMonth.of(2027, 1), reportedMonth)
            assertEquals(LocalDate.of(2027, 1, 31).millis(), selected)
        }
    }

    private fun bars(): List<DayBar> = (0 until 151).map { offset ->
        val day = LocalDate.of(2026, 11, 1).plusDays(offset.toLong())
        DayBar(day.dayOfMonth, day.millis(), 100, day == LocalDate.of(2026, 12, 31))
    }
    private fun LocalDate.millis(): Long = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun Long.date(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
}
