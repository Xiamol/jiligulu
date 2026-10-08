package com.jiligulu.app.ui.littleworld

import android.icu.util.Calendar
import android.icu.util.ChineseCalendar
import android.icu.util.TimeZone
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant

/** A civil date is converted at China-standard noon; a device timezone cannot shift its date. */
internal object XiaoLiuRenCalendar {
    fun cast(question: String, mode: LiuRenMode, digits: String, instantMillis: Long, zone: ZoneId): LiuRenCast {
        val local = Instant.ofEpochMilli(instantMillis).atZone(zone)
        val calendar = forDate(local.toLocalDate(), XiaoLiuRen.shichen(local.hour))
        return LiuRenCast(question.trim(), mode, instantMillis, zone.id, calendar.lunarMonth, calendar.lunarDay,
            calendar.shichen, calendar.leapMonth, if (mode == LiuRenMode.NUMBERS) digits.trim() else "").checked()
    }
    fun forDate(date: LocalDate, shichen: Int): XiaoLiuRenInput {
        require(date.year in 1900..2100)
        val lunar = ChineseCalendar(TimeZone.getTimeZone("Asia/Shanghai"))
        lunar.timeInMillis = date.atTime(12, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        return XiaoLiuRenInput(date, lunar.get(Calendar.MONTH) + 1, lunar.get(Calendar.DAY_OF_MONTH),
            shichen, leapMonth = lunar.get(Calendar.IS_LEAP_MONTH) == 1)
    }
}
