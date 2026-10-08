package com.jiligulu.app.ui.components

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jiligulu.app.ui.stats.StatsCalendarDialog
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h640dp-port-mdpi")
class PickerDialogDismissalTest {
    @get:Rule val compose = createComposeRule()

    @Test fun timePickerOutsideAndBackDiscardTheDraftWhileConfirmationStillApplies() {
        var shown by mutableStateOf(true)
        val confirmed = mutableListOf<Pair<Int, Int>>()
        compose.setContent {
            MaterialTheme {
                if (shown) TimePickerDialog("提醒时间", 9, 30,
                    onConfirm = { hour, minute -> confirmed += hour to minute; shown = false },
                    onDismiss = { shown = false })
            }
        }
        compose.onNodeWithTag("time-picker-cancel").assertDoesNotExist()
        dismissOutside()
        compose.runOnIdle { assertFalse(shown); assertTrue(confirmed.isEmpty()); shown = true }
        dismissBack()
        compose.runOnIdle { assertFalse(shown); assertTrue(confirmed.isEmpty()); shown = true }
        compose.onNodeWithTag("time-picker-confirm").performClick()
        compose.runOnIdle { assertFalse(shown); assertEquals(listOf(9 to 30), confirmed) }
    }

    @Test fun calendarOutsideAndBackNeverApplyAPendingDayAndItsRealConfirmRemains() {
        var shown by mutableStateOf(true)
        val selected = LocalDate.of(2028, 2, 1).millis()
        val applied = mutableListOf<Pair<Long, Boolean>>()
        compose.setContent {
            MaterialTheme {
                if (shown) StatsCalendarDialog(selected, YearMonth.of(2028, 2), { shown = false },
                    onSelect = { date, monthOnly -> applied += date to monthOnly; shown = false })
            }
        }
        compose.onNodeWithText("取消").assertDoesNotExist()
        compose.onNodeWithTag("stats-calendar-day-2028-02-29").performClick()
        dismissOutside()
        compose.runOnIdle { assertFalse(shown); assertTrue(applied.isEmpty()); shown = true }
        compose.onNodeWithTag("stats-calendar-day-2028-02-29").performClick()
        dismissBack()
        compose.runOnIdle { assertFalse(shown); assertTrue(applied.isEmpty()); shown = true }
        compose.onNodeWithTag("stats-calendar-day-2028-02-29").performClick()
        compose.onNodeWithText("确认").performClick()
        compose.runOnIdle { assertFalse(shown); assertEquals(listOf(LocalDate.of(2028, 2, 29).millis() to false), applied) }
    }

    @Test fun timeWheelStartsAtExactEdgeAndSteppedValuesWithoutAdvancingTwoRows() {
        data class Case(val hour: Int, val minute: Int, val step: Int)
        val cases = listOf(Case(0, 0, 1), Case(23, 59, 1), Case(0, 0, 15), Case(23, 45, 15))
        var current by mutableStateOf(cases.first())
        var shown by mutableStateOf(true)
        val confirmed = mutableListOf<Pair<Int, Int>>()
        compose.setContent { MaterialTheme {
            if (shown) TimePickerDialog("边界时间${current.step}", current.hour, current.minute,
                minuteStep = current.step, onConfirm = { hour, minute -> confirmed += hour to minute; shown = false },
                onDismiss = { shown = false })
        } }
        for (case in cases) {
            compose.runOnIdle { current = case; shown = true }
            compose.waitForIdle()
            val dialog = compose.runOnIdle { checkNotNull(ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }) }
            compose.onNodeWithTag("time-picker-confirm").performClick()
            compose.waitUntil(8_000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                !dialog.isShowing
            }
            compose.runOnIdle { assertFalse(shown); assertEquals(case.hour to case.minute, confirmed.last()) }
        }
        assertEquals(cases.map { it.hour to it.minute }, confirmed)
    }

    private fun dismissOutside() = dismissPlatformDialog { dialog ->
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
        try { assertTrue(dialog.onTouchEvent(event)) } finally { event.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun dismissBack() = dismissPlatformDialog { it.onBackPressed() }

    private fun dismissPlatformDialog(action: (android.app.Dialog) -> Unit) {
        compose.waitForIdle()
        val dialog = compose.runOnIdle {
            checkNotNull(ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }).also(action)
        }
        compose.waitUntil(8_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            !dialog.isShowing
        }
        compose.waitForIdle()
        assertFalse(dialog.isShowing)
    }

    private fun LocalDate.millis() = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
