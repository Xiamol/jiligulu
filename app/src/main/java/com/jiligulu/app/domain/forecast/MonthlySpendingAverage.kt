package com.jiligulu.app.domain.forecast

import java.time.LocalDate

data class DailySpending(val date: LocalDate, val amountFen: Long)

data class MonthlyAverageDay(val date: LocalDate, val averageFen: Long?)

/** Month-to-date daily mean: zero-spend days count, future days remain empty. No prediction. */
object MonthlySpendingAverage {
    fun forDates(records: List<DailySpending>, dates: List<LocalDate>, today: LocalDate): List<MonthlyAverageDay> {
        val daily = records.asSequence().filter { it.amountFen > 0 && !it.date.isAfter(today) }
            .groupBy { it.date }.mapValues { (_, values) ->
                values.fold(0L) { total, item -> add(total, item.amountFen) }
            }
        val monthly = dates.map { it.withDayOfMonth(1) }.distinct().associateWith { start ->
            var total = 0L
            (1..start.lengthOfMonth()).associate { number ->
                val date = start.withDayOfMonth(number)
                total = add(total, daily[date] ?: 0L)
                // Integer cents keep imported large amounts exact and never overflow in rounding.
                val mean = total / number + if ((total % number) * 2 >= number) 1L else 0L
                date to mean.takeUnless { date.isAfter(today) }
            }
        }
        return dates.map { MonthlyAverageDay(it, monthly[it.withDayOfMonth(1)]?.get(it)) }
    }

    private fun add(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
}
