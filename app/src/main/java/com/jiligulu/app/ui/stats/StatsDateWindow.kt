package com.jiligulu.app.ui.stats

import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.ui.stats.charts.DayBar
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Inclusive dates, exclusive database end. Calendar days also work across DST and leap years. */
internal data class StatsDateWindow(val first: LocalDate, val last: LocalDate) {
    init { require(!last.isBefore(first)) }
    val dates: List<LocalDate> get() = (0..ChronoUnit.DAYS.between(first, last).toInt()).map { first.plusDays(it.toLong()) }
    fun millis(zone: ZoneId) = first.atStartOfDay(zone).toInstant().toEpochMilli() to
        last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    fun shifted(days: Long) = StatsDateWindow(first.plusDays(days), last.plusDays(days))
}

/** Today uses the last ten days; other dates stay centered. This is an initial placement, never a navigation limit. */
internal fun compactStatsWindow(anchor: LocalDate, today: LocalDate) =
    if (anchor == today) StatsDateWindow(today.minusDays(9), today)
    else StatsDateWindow(anchor.minusDays(4), anchor.plusDays(5))
internal fun monthStatsWindow(anchor: LocalDate): StatsDateWindow = YearMonth.from(anchor).let {
    StatsDateWindow(it.atDay(1), it.atEndOfMonth())
}
internal fun adjacentStatsMonth(anchor: LocalDate, delta: Long): LocalDate =
    YearMonth.from(anchor).plusMonths(delta).let { it.atDay(anchor.dayOfMonth.coerceAtMost(it.lengthOfMonth())) }

internal fun statsDayBars(bills: List<BillEntity>, type: BillType, window: StatsDateWindow,
    today: LocalDate, zone: ZoneId): List<DayBar> {
    val totals = HashMap<LocalDate, Long>()
    bills.forEach { bill ->
        if (bill.type == type) {
            val date = Instant.ofEpochMilli(bill.timestamp).atZone(zone).toLocalDate()
            if (!date.isBefore(window.first) && !date.isAfter(window.last))
                totals[date] = (totals[date] ?: 0L) + bill.amountFen
        }
    }
    return window.dates.map { date -> DayBar(date.dayOfMonth,
        date.atStartOfDay(zone).toInstant().toEpochMilli(), totals[date] ?: 0L, date == today) }
}
