package com.jiligulu.app.core.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 金额/时间格式化工具。金额内部统一用"分"(Long)，展示时才转元。 */
object Formatters {

    /** 分 → "12.5" / "1034.56"（去尾零） */
    fun fenToYuanText(fen: Long): String =
        BigDecimal(fen).divide(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()

    /** "12.5" → 1250 分；非法输入返回 null */
    fun yuanTextToFen(text: String): Long? = runCatching {
        BigDecimal(text.trim()).multiply(BigDecimal(100))
            .setScale(0, RoundingMode.HALF_UP).longValueExact()
    }.getOrNull()?.takeIf { it > 0 }

    fun timeLabel(timestamp: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

    /** "9月21日 周一" */
    fun dayLabel(timestamp: Long): String {
        val date = Date(timestamp)
        val md = SimpleDateFormat("M月d日", Locale.getDefault()).format(date)
        val week = when (Calendar.getInstance().apply { time = date }.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "周一"
            Calendar.TUESDAY -> "周二"
            Calendar.WEDNESDAY -> "周三"
            Calendar.THURSDAY -> "周四"
            Calendar.FRIDAY -> "周五"
            Calendar.SATURDAY -> "周六"
            else -> "周日"
        }
        return "$md $week"
    }

    fun monthLabel(timestamp: Long): String =
        SimpleDateFormat("yyyy年M月", Locale.getDefault()).format(Date(timestamp))

    /** 当天 0 点时间戳 */
    fun dayStart(timestamp: Long): Long = Calendar.getInstance().apply {
        timeInMillis = timestamp
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 本月 [起, 止) 时间戳 */
    fun currentMonthRange(): Pair<Long, Long> = monthRange(0)

    /** 相对本月偏移 monthOffset 的月份 [起, 止)；统计页月份切换用 */
    fun monthRange(monthOffset: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            add(Calendar.MONTH, monthOffset)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        return start to cal.timeInMillis
    }

    /** 某月有多少天（monthOffset 相对本月） */
    fun daysInMonth(monthOffset: Int): Int = Calendar.getInstance().apply {
        add(Calendar.MONTH, monthOffset)
    }.getActualMaximum(Calendar.DAY_OF_MONTH)

    /** 某月第 day 天 0 点（monthOffset 相对本月） */
    fun dayOfMonth(monthOffset: Int, day: Int): Long = Calendar.getInstance().apply {
        add(Calendar.MONTH, monthOffset)
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
