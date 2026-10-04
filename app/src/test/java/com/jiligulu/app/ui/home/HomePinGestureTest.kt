package com.jiligulu.app.ui.home

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h640dp-port-mdpi")
class HomePinGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun firstPinnedPullKeepsHeadingFixedAndReturnsTheBillListThenSecondPullExpands() {
        compose.setContent {
            MaterialTheme { HomeContent(HomeUiState(monthLabel = "2026年10月"), {}, {}, onBillClick = {}) }
        }
        compose.onNodeWithTag("home-outer").performScrollToIndex(HOME_LEDGER_ITEM_INDEX)
        val heading = compose.onNodeWithTag("home-ledger-heading")
        val bills = compose.onNodeWithTag("home-day-bills")
        val headingTop = heading.fetchSemanticsNode().boundsInRoot.top
        val billsTop = bills.fetchSemanticsNode().boundsInRoot.top
        bills.performTouchInput {
            down(Offset(width * .5f, height * .15f))
            moveTo(Offset(width * .5f, height * .45f), delayMillis = 100)
        }
        compose.runOnIdle {
            assertEquals(headingTop, heading.fetchSemanticsNode().boundsInRoot.top, 1f)
            assertTrue(bills.fetchSemanticsNode().boundsInRoot.top > billsTop + 2f)
        }
        bills.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            assertEquals(headingTop, heading.fetchSemanticsNode().boundsInRoot.top, 1f)
            assertEquals(billsTop, bills.fetchSemanticsNode().boundsInRoot.top, 1f)
        }
        bills.performTouchInput {
            down(Offset(width * .5f, height * .15f))
            moveTo(Offset(width * .5f, height * .45f), delayMillis = 100)
            up()
        }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { assertTrue(heading.fetchSemanticsNode().boundsInRoot.top > headingTop + 10f) }
    }

    @Test fun overviewSwipeUsesMainPageButDailyHistoryKeepsItsOwnGesture() {
        var dx = 0f
        var ended = 0
        compose.setContent {
            MaterialTheme { HomeContent(HomeUiState(monthLabel = "2026年10月"), {}, {},
                onOpenStats = {}, onPageDrag = { dx += it }, onPageDragEnd = { ended++ }, onBillClick = {}) }
        }
        val outer = compose.onNodeWithTag("home-outer")
        outer.performTouchInput {
            down(Offset(width * .8f, 40f))
            moveTo(Offset(width * .4f, 40f), delayMillis = 100)
        }
        compose.runOnIdle { assertTrue(dx < -30f); assertEquals(0, ended) }
        outer.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, ended); dx = 0f }
        outer.performScrollToIndex(HOME_LEDGER_ITEM_INDEX)
        compose.onNodeWithTag("home-day-pager").performTouchInput {
            down(Offset(width * .2f, height * .5f))
            moveTo(Offset(width * .65f, height * .5f), delayMillis = 100)
            up()
        }
        compose.runOnIdle { assertEquals(0f, dx); assertEquals(1, ended) }
    }

    @Test fun headingPullHasTheSameTwoGesturePinBoundaryAsTheBillList() {
        compose.setContent {
            MaterialTheme { HomeContent(HomeUiState(monthLabel = "2026年10月"), {}, {}, onBillClick = {}) }
        }
        val outer = compose.onNodeWithTag("home-outer")
        outer.performScrollToIndex(HOME_LEDGER_ITEM_INDEX)
        val heading = compose.onNodeWithTag("home-ledger-heading")
        val initial = heading.fetchSemanticsNode().boundsInRoot.top
        // Start above the inner list: this gesture is owned by the outer LazyColumn.
        outer.performTouchInput {
            down(Offset(width * .3f, 60f))
            moveTo(Offset(width * .3f, 160f), delayMillis = 100)
        }
        compose.runOnIdle { assertEquals(initial, heading.fetchSemanticsNode().boundsInRoot.top, 1f) }
        outer.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals(initial, heading.fetchSemanticsNode().boundsInRoot.top, 1f) }
        outer.performTouchInput {
            down(Offset(width * .3f, 60f))
            moveTo(Offset(width * .3f, 160f), delayMillis = 100)
            up()
        }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { assertTrue(heading.fetchSemanticsNode().boundsInRoot.top > initial + 10f) }
    }


    @Test fun bottomSpringFollowedImmediatelyByTopPullNeverLeavesAGap() {
        val today = com.jiligulu.app.core.util.Formatters.dayStart(System.currentTimeMillis())
        compose.setContent {
            MaterialTheme { HomeContent(HomeUiState(monthLabel = "2026年10月"), {}, {},
                selectedDay = today, daySource = { kotlinx.coroutines.flow.flowOf(snapshot(it, "账单")) }, onBillClick = {}) }
        }
        compose.waitUntil(4000) { compose.onAllNodesWithText("账单-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home-outer").performScrollToIndex(HOME_LEDGER_ITEM_INDEX)
        val bills = compose.onNodeWithTag("home-day-bills")
        val heading = compose.onNodeWithTag("home-ledger-heading")
        val before = bills.getUnclippedBoundsInRoot().top.value
        val headingBefore = heading.getUnclippedBoundsInRoot().top.value
        bills.performTouchInput {
            down(Offset(width * .5f, height * .75f))
            moveTo(Offset(width * .5f, height * .3f), delayMillis = 100)
            up()
        }
        // Grab again while the bottom spring is still travelling back.
        compose.mainClock.advanceTimeBy(16)
        bills.performTouchInput {
            down(Offset(width * .5f, height * .2f))
            moveTo(Offset(width * .5f, height * .65f), delayMillis = 100)
            up()
        }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle {
            assertEquals(before, bills.getUnclippedBoundsInRoot().top.value, 1f)
            assertEquals(headingBefore, heading.getUnclippedBoundsInRoot().top.value, 1f)
        }
    }

    @Test fun yesterdayPageThenTopPullReturnsExactlyToItsRestingPosition() {
        val today = com.jiligulu.app.core.util.Formatters.dayStart(System.currentTimeMillis())
        val yesterday = com.jiligulu.app.ui.components.shiftLocalDay(today, -1)
        var selected by mutableStateOf(today)
        compose.setContent {
            MaterialTheme { HomeContent(HomeUiState(monthLabel = "2026年10月"), {}, {},
                selectedDay = selected,
                daySource = { kotlinx.coroutines.flow.flowOf(snapshot(it, if (it == today) "今天" else "昨天")) },
                onSelectDay = { selected = it }, onBillClick = {}) }
        }
        compose.waitUntil(4000) { compose.onAllNodesWithText("今天-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home-outer").performScrollToIndex(HOME_LEDGER_ITEM_INDEX)
        compose.onNodeWithTag("home-day-pager").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(yesterday, selected) }
        val bills = compose.onNodeWithTag("home-day-bills")
        val before = bills.getUnclippedBoundsInRoot().top.value
        bills.performTouchInput {
            down(Offset(width * .5f, height * .2f))
            moveTo(Offset(width * .5f, height * .65f), delayMillis = 100)
            up()
        }
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle { assertEquals(before, bills.getUnclippedBoundsInRoot().top.value, 1f) }
    }

    private fun snapshot(day: Long, prefix: String): DailyLedgerSnapshot = DailyLedgerSnapshot(day,
        bills = List(3) { index ->
            val entity = com.jiligulu.app.data.local.entity.BillEntity(id = day + index + 1,
                amountFen = 900, type = com.jiligulu.app.data.local.entity.BillType.EXPENSE,
                categoryId = 1, detail = "$prefix-$index", timestamp = day + (index + 1) * 60000)
            BillUi(entity.id, "🍚", 20f, 0, entity.detail, "12:00", "-9", true, entity, "吃饭")
        }, loaded = true)

}
