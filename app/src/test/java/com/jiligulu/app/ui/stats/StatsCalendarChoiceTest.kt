package com.jiligulu.app.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class StatsCalendarChoiceTest {
    @Test fun browsingOnlyConfirmsTheFirstDayOfTheDisplayedMonthAcrossTheNewYear() {
        val choice = StatsCalendarChoice(YearMonth.of(2026, 12)).browse(YearMonth.of(2027, 1))
        assertEquals(LocalDate.of(2027, 1, 1), choice.confirmed)
    }
    @Test fun aClickedDayIsPendingUntilConfirmedAndBrowsingClearsThatPendingDay() {
        val initial = StatsCalendarChoice(YearMonth.of(2028, 2))
        val picked = initial.choose(29)
        assertEquals(LocalDate.of(2028, 2, 1), initial.confirmed)
        assertEquals(LocalDate.of(2028, 2, 29), picked.confirmed)
        assertEquals(LocalDate.of(2028, 3, 1), picked.browse(YearMonth.of(2028, 3)).confirmed)
    }
}
