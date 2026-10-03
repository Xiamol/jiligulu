package com.jiligulu.app.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h640dp-port-mdpi")
class DayPagerGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyEnabledTodayBoundaryOpensStatsAndHistoryStillPagesNormally() {
        val today = LocalDate.of(2026, 10, 2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        var selected by mutableStateOf(today)
        var enabled by mutableStateOf(false)
        var opened = 0
        compose.setContent {
            MaterialTheme {
                DayPager(selected, today, { selected = it }, Modifier.fillMaxSize(),
                    onSwipePastToday = if (enabled) ({ opened++ }) else null) { date -> Text("$date") }
            }
        }
        val pager = compose.onNodeWithTag("day-pager")
        pager.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(0, opened); assertEquals(today, selected); enabled = true }
        pager.performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(0, opened); assertEquals(today, selected) }
        pager.performTouchInput {
            down(Offset(width * .7f, height * .5f))
            moveTo(Offset(width * .58f, height * .5f), delayMillis = 100)
            up()
        }
        compose.runOnIdle { assertEquals(0, opened) }
        pager.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(1, opened); assertEquals(today, selected) }
        pager.performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(shiftLocalDay(today, -1), selected) }
        pager.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(today, selected) }
        pager.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(2, opened); assertEquals(today, selected) }
    }

    @Test fun todayEdgeStreamsRealDragAndHistoryOrVerticalGesturesDoNot() {
        val today = LocalDate.of(2026, 10, 4).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        var selected by mutableStateOf(today)
        var dragX = 0f
        var releases = 0
        compose.setContent {
            MaterialTheme {
                DayPager(selected, today, { selected = it }, Modifier.fillMaxSize(),
                    onSwipePastToday = {}, onPageDrag = { dragX += it },
                    onPageDragEnd = { releases++ }) { date -> Text("$date") }
            }
        }
        val pager = compose.onNodeWithTag("day-pager")
        pager.performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(0f, dragX); assertEquals(0, releases) }
        pager.performTouchInput {
            down(Offset(width * .75f, height * .5f))
            moveTo(Offset(width * .5f, height * .5f), delayMillis = 100)
        }
        // A page offset must already exist before up, rather than switching only at release.
        compose.runOnIdle { org.junit.Assert.assertTrue(dragX < -30f); assertEquals(0, releases) }
        pager.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, releases); assertEquals(today, selected); dragX = 0f }
        pager.performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(shiftLocalDay(today, -1), selected)
            assertEquals(0f, dragX); assertEquals(1, releases)
        }
        pager.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(today, selected); assertEquals(0f, dragX); assertEquals(1, releases) }
    }


    @Test fun forwardedDragRemainsPhysicalWhenThePageMovesUnderTheFinger() {
        var translated by mutableStateOf(0f)
        var ended = 0
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("fixed-root")) {
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = translated }
                    .forwardMainPageSwipe(enabled = { true }, onDrag = { translated += it },
                        onDragEnd = { ended++ }))
            }
        }
        val root = compose.onNodeWithTag("fixed-root")
        root.performTouchInput {
            down(Offset(width * .8f, height * .5f))
            moveTo(Offset(width * .6f, height * .5f), delayMillis = 100)
        }
        var afterFirst = 0f
        compose.runOnIdle { afterFirst = translated; org.junit.Assert.assertTrue(afterFirst < -30f) }
        // Advance a frame so the receiving page is now in a different local coordinate space.
        compose.mainClock.advanceTimeBy(32)
        root.performTouchInput { moveTo(Offset(width * .4f, height * .5f), delayMillis = 100) }
        compose.runOnIdle { assertEquals(afterFirst * 2f, translated, 2f) }
        root.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, ended) }
    }

}
