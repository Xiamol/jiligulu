package com.jiligulu.app.ui.components

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
        var pinned by mutableStateOf(false)
        var opened = 0
        compose.setContent {
            MaterialTheme {
                DayPager(selected, today, { selected = it }, Modifier.fillMaxSize(),
                    onSwipePastToday = if (pinned) ({ opened++ }) else null) { date -> Text("$date") }
            }
        }
        val pager = compose.onNodeWithTag("day-pager")
        pager.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(0, opened); assertEquals(today, selected); pinned = true }
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
}
