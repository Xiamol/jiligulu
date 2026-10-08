package com.jiligulu.app.ui.stats

import com.jiligulu.app.data.prefs.CalendarProgressMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal data class CalendarProgress(val completedDays: Int, val totalDays: Int) {
    val fraction: Float get() = completedDays.toFloat() / totalDays
    val percentage: String get() = java.math.BigDecimal(completedDays * 100).divide(java.math.BigDecimal(totalDays),
        1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%"
}
internal fun calendarProgress(today: LocalDate, mode: CalendarProgressMode): CalendarProgress {
    val first = if (mode == CalendarProgressMode.MONTH) today.withDayOfMonth(1) else today.withDayOfYear(1)
    val total = if (mode == CalendarProgressMode.MONTH) today.lengthOfMonth() else today.lengthOfYear()
    return CalendarProgress(ChronoUnit.DAYS.between(first, today).toInt(), total)
}
internal fun calendarProgress(nowMillis: Long, zone: ZoneId, mode: CalendarProgressMode) =
    calendarProgress(Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate(), mode)
