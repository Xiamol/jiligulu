package com.jiligulu.app.ui.chat

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun ChatItem.withSentAt(value: Long): ChatItem = when (this) {
    is ChatItem.UserMsg -> copy(sentAt = value)
    is ChatItem.GuluMsg -> copy(sentAt = value)
    is ChatItem.DraftCard -> copy(sentAt = value)
    is ChatItem.ActionCard -> copy(sentAt = value)
    is ChatItem.AppActionCard -> copy(sentAt = value)
    is ChatItem.CommandCard -> copy(sentAt = value)
}

/** Conversation timestamps, never the date of the expense being discussed. */
internal object ChatTimeLabels {
    const val GAP_MILLIS = 5 * 60_000L
    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    private val monthDay = DateTimeFormatter.ofPattern("M月d日")
    private val yearDay = DateTimeFormatter.ofPattern("yyyy年M月d日")
    private val weekdays = mapOf(DayOfWeek.MONDAY to "星期一", DayOfWeek.TUESDAY to "星期二",
        DayOfWeek.WEDNESDAY to "星期三", DayOfWeek.THURSDAY to "星期四",
        DayOfWeek.FRIDAY to "星期五", DayOfWeek.SATURDAY to "星期六", DayOfWeek.SUNDAY to "星期日")

    fun separators(items: List<ChatItem>, today: LocalDate, zone: ZoneId): Map<Long, String> {
        val result = LinkedHashMap<Long, String>()
        var previous: Long? = null
        items.forEach { item ->
            if (item.sentAt <= 0 || item is ChatItem.GuluMsg && item.text.isBlank() && !item.loading) return@forEach
            val previousTime = previous
            val at = Instant.ofEpochMilli(item.sentAt).atZone(zone)
            val previousDate = previousTime?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            if (previousTime == null || item.sentAt - previousTime >= GAP_MILLIS ||
                item.sentAt < previousTime || at.toLocalDate() != previousDate) {
                result[item.id] = format(item.sentAt, today, zone)
            }
            previous = item.sentAt
        }
        return result
    }

    fun format(millis: Long, today: LocalDate, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(millis).atZone(zone)
        val day = at.toLocalDate()
        val prefix = when {
            day == today -> ""
            day == today.minusDays(1) -> "昨天 "
            day < today && day >= today.minusDays(6) -> "${weekdays.getValue(day.dayOfWeek)} "
            day.year == today.year -> "${at.format(monthDay)} "
            else -> "${at.format(yearDay)} "
        }
        return prefix + at.format(clock)
    }
}
