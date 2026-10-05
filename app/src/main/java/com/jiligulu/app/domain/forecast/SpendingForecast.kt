package com.jiligulu.app.domain.forecast

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

data class DailySpending(val date: LocalDate, val amountFen: Long)

data class SpendingForecastDay(
    val date: LocalDate,
    val expectedFen: Long?,
    val historyDays: Int,
    val usesWeekdayPattern: Boolean
)

/**
 * Small, local daily forecast. A historical point is a genuine one-step estimate: only earlier
 * complete days train it. Today is deliberately excluded from training, and empty days after
 * the first recorded expense count as zero. Future estimates end at this calendar month's end;
 * the caller supplies one whole displayed month, and a 31-day safety ceiling bounds projection.
 *
 * The level uses exponential smoothing (alpha .18); after three weeks, a conservative weekday
 * blend captures recurring weekend habits. A rolling positive median/MAD cap prevents one
 * exceptional purchase from becoming the expected daily spend. The actual ledger is untouched.
 * Method references: https://otexts.com/fpp3/ses.html and
 * https://otexts.com/fpp3/simple-methods.html . This robust/weekday blend is our own heuristic.
 */
object SpendingForecast {
    const val MIN_HISTORY_DAYS = 7
    const val HISTORY_DAYS = 28
    const val MAX_FUTURE_DAYS = 31

    fun forDates(
        records: List<DailySpending>,
        dates: List<LocalDate>,
        today: LocalDate
    ): List<SpendingForecastDay> {
        // The upper clamp and saturating addition also make malformed imported values harmless.
        val sums = records.filter { !it.date.isAfter(today) && it.amountFen > 0 }
            .groupBy { it.date }
            .mapValues { (_, values) -> values.fold(0L) { acc, v -> saturatedAdd(acc, v.amountFen) } }
        val first = sums.keys.minOrNull()
        val lastForecastDay = minOf(today.withDayOfMonth(today.lengthOfMonth()), today.plusDays(MAX_FUTURE_DAYS.toLong()))
        return dates.map { day ->
            if (first == null || day.isAfter(lastForecastDay)) {
                return@map SpendingForecastDay(day, null, 0, false)
            }
            val end = minOf(day.minusDays(1), today.minusDays(1))
            val start = maxOf(first, end.minusDays(HISTORY_DAYS - 1L))
            val count = if (start.isAfter(end)) 0 else ChronoUnit.DAYS.between(start, end).toInt() + 1
            if (count < MIN_HISTORY_DAYS) return@map SpendingForecastDay(day, null, count, false)
            val days = (0 until count).map { start.plusDays(it.toLong()) }
            val observations = days.map { (sums[it] ?: 0L).toDouble() }
            val positives = observations.filter { it > 0 }.sorted()
            val medianValue = median(positives)
            val deviation = median(positives.map { abs(it - medianValue) }.sorted())
            // A cap needs several positive observations; sparse legitimate expenses aren't erased.
            val cap = if (positives.size >= 4) max(medianValue * 4, medianValue + 4 * deviation) else Double.MAX_VALUE
            val clipped = observations.map { it.coerceAtMost(cap) }
            var level = clipped.take(MIN_HISTORY_DAYS).average()
            clipped.drop(MIN_HISTORY_DAYS).forEach { value -> level = .18 * value + .82 * level }
            val weekdayValues = days.indices.filter { days[it].dayOfWeek == day.dayOfWeek }.map { clipped[it] }
            val seasonal = count >= 21 && weekdayValues.size >= 3
            val estimate = if (seasonal) {
                // Three or four samples do not justify a fully seasonal model: shrink toward level.
                .65 * level + .35 * weekdayValues.average()
            } else level
            SpendingForecastDay(day, estimate.coerceIn(0.0, Long.MAX_VALUE.toDouble()).roundToLong(), count, seasonal)
        }
    }

    private fun median(sorted: List<Double>): Double = when {
        sorted.isEmpty() -> 0.0
        sorted.size % 2 == 1 -> sorted[sorted.size / 2]
        else -> sorted[sorted.size / 2 - 1] / 2 + sorted[sorted.size / 2] / 2
    }

    private fun saturatedAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
}
