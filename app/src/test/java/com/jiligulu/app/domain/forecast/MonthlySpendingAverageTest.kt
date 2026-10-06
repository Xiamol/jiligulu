package com.jiligulu.app.domain.forecast

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class MonthlySpendingAverageTest {
    private val first = LocalDate.of(2026, 10, 1)

    @Test fun emptyDaysCountFromMonthStartAndFutureIsNotEstimated() {
        val records = listOf(DailySpending(first.plusDays(2), 900), DailySpending(first.plusDays(3), 1100))
        val values = MonthlySpendingAverage.forDates(records, (0..5).map { first.plusDays(it.toLong()) }, first.plusDays(3))
        assertEquals(listOf(0L, 0L, 300L, 500L, null, null), values.map { it.averageFen })
    }

    @Test fun eachMonthStartsFreshAndMultipleBillsAreIncluded() {
        val records = listOf(DailySpending(first.minusDays(1), 100000), DailySpending(first, 100), DailySpending(first, 101))
        val values = MonthlySpendingAverage.forDates(records, listOf(first, first.plusDays(1)), first.plusDays(1))
        assertEquals(listOf(201L, 101L), values.map { it.averageFen })
    }

    @Test fun malformedFutureNegativeAndOverflowAmountsCannotCorruptTheMean() {
        val records = listOf(DailySpending(first, Long.MAX_VALUE), DailySpending(first, 9),
            DailySpending(first, -2), DailySpending(first.plusDays(1), 900))
        assertEquals(Long.MAX_VALUE, MonthlySpendingAverage.forDates(records, listOf(first), first).single().averageFen)
    }
}
