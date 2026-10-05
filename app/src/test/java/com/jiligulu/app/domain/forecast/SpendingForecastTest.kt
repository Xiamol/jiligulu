package com.jiligulu.app.domain.forecast

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SpendingForecastTest {
    private val first = LocalDate.of(2026, 9, 1)
    private fun records(values: List<Long>) = values.mapIndexed { i, amount -> DailySpending(first.plusDays(i.toLong()), amount) }
    private fun estimate(values: List<Long>) = SpendingForecast.forDates(records(values), listOf(first.plusDays(values.size.toLong())), first.plusDays(values.size.toLong())).single()

    @Test fun noForecastUntilSevenCompletedDaysAndNoInventedHistory() {
        assertNull(estimate(listOf(1000, 1200, 800, 1100, 900, 1000)).expectedFen)
        assertEquals(1000L, estimate(List(7) { 1000L }).expectedFen)
        assertNull(SpendingForecast.forDates(emptyList(), listOf(first), first).single().expectedFen)
    }

    @Test fun historicalEstimateDoesNotLookAheadAndTodayDoesNotTrainIt() {
        val baseline = records(List(14) { 1000L })
        val target = first.plusDays(7)
        val changedAfter = baseline.map { if (it.date >= target) it.copy(amountFen = 9_999_999L) else it }
        assertEquals(
            SpendingForecast.forDates(baseline, listOf(target), first.plusDays(14)).single(),
            SpendingForecast.forDates(changedAfter, listOf(target), first.plusDays(14)).single()
        )
        val today = first.plusDays(14)
        val tomorrow = today.plusDays(1)
        assertEquals(
            SpendingForecast.forDates(baseline, listOf(tomorrow), today).single(),
            SpendingForecast.forDates(baseline + DailySpending(today, 999_999L), listOf(tomorrow), today).single()
        )
    }

    @Test fun occasionalLargePurchaseDoesNotBecomeDailyExpectation() {
        val normal = estimate(List(28) { 1000L }).expectedFen!!
        val withOutlier = estimate(List(27) { 1000L } + 1_000_000L).expectedFen!!
        assertEquals(1000L, normal)
        assertTrue(withOutlier in 1000L..1700L)
    }

    @Test fun zeroSpendDaysCountAndEstimatesStayNonnegative() {
        val result = estimate(listOf(700L) + List(13) { 0L })
        assertEquals(14, result.historyDays)
        assertTrue(result.expectedFen!! in 0L..100L)
        val negative = estimate(List(7) { -100L })
        assertNull(negative.expectedFen)
    }

    @Test fun weekdayPatternRequiresThreeWeeksAndFutureStopsAtMonthEnd() {
        val values = (0 until 28).map { if (first.plusDays(it.toLong()).dayOfWeek.value >= 6) 4000L else 1000L }
        val today = first.plusDays(28)
        val future = listOf(today, today.withDayOfMonth(today.lengthOfMonth()), today.withDayOfMonth(today.lengthOfMonth()).plusDays(1), today.plusDays(32))
        val result = SpendingForecast.forDates(records(values), future, today)
        assertTrue(result.first().usesWeekdayPattern)
        assertNotNull(result[1].expectedFen)
        assertNull(result[2].expectedFen)
        assertNull(result[3].expectedFen)
        val historical = SpendingForecast.forDates(records(values), listOf(first.plusDays(24), first.plusDays(25)), today)
        val weekday = historical[0].expectedFen!!
        val weekend = historical[1].expectedFen!!
        assertTrue(weekend > weekday)
    }

    @Test fun duplicateRecordsAggregateAndLargeImportsDoNotOverflowNegative() {
        val records = records(List(7) { 1000L })
        val duplicated = records + records
        assertEquals(2000L, SpendingForecast.forDates(duplicated, listOf(first.plusDays(7)), first.plusDays(7)).single().expectedFen)
        val huge = records(List(28) { Long.MAX_VALUE }) + DailySpending(first, Long.MAX_VALUE)
        assertTrue(SpendingForecast.forDates(huge, listOf(first.plusDays(28)), first.plusDays(28)).single().expectedFen!! >= 0)
    }
}
