package com.jiligulu.app.ui.stats

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h640dp-port-mdpi")
class StatsCalendarDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun browsingAndTappingDoNotApplyUntilConfirmAndAMonthOnlyChoiceStartsAtOne() {
        val applied = ArrayList<Pair<Long, Boolean>>()
        compose.setContent {
            MaterialTheme {
                StatsCalendarDialog(LocalDate.of(2026, 12, 24).millis(), YearMonth.of(2026, 12), {},
                    onSelect = { date, monthOnly -> applied.add(date to monthOnly) })
            }
        }
        compose.onNodeWithContentDescription("下一个月").performClick()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
        compose.onNodeWithText("确认").performClick()
        compose.runOnIdle { assertEquals(listOf(LocalDate.of(2027, 1, 1).millis() to true), applied) }
    }

    @Test fun aSpecificDayRemainsPendingUntilConfirmationAndTheTodayShortcutIsAbsent() {
        val applied = ArrayList<Pair<Long, Boolean>>()
        compose.setContent {
            MaterialTheme {
                StatsCalendarDialog(LocalDate.of(2028, 2, 1).millis(), YearMonth.of(2028, 2), {},
                    onSelect = { date, monthOnly -> applied.add(date to monthOnly) })
            }
        }
        compose.onNodeWithTag("stats-calendar-day-2028-02-29").performClick()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
        compose.onNodeWithText("回到今天").assertDoesNotExist()
        compose.onNodeWithText("确认").performClick()
        compose.runOnIdle { assertEquals(listOf(LocalDate.of(2028, 2, 29).millis() to false), applied) }
    }

    private fun LocalDate.millis() = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
