package com.jiligulu.app.ui.littleworld

import android.content.SharedPreferences
import java.time.LocalDate

/** One bounded daily selection. A new local day resets it; reopening today never rerolls. */
internal class XiaoLiuRenStore(private val prefs: SharedPreferences) {
    fun loadForDay(today: LocalDate, initial: () -> XiaoLiuRenInput): XiaoLiuRenInput {
        val values = prefs.all
        val stored = if (values["anchor"] == today.toString()) runCatching {
            val date = LocalDate.parse(values["date"] as String)
            require(date.year in 1900..2100)
            XiaoLiuRenInput(date, values["month"] as Int, values["day"] as Int, values["shichen"] as Int,
                values["leap"] as? Boolean ?: false, values["manual"] as? Boolean ?: false)
        }.getOrNull() else null
        return stored ?: initial().also { save(today, it) }
    }
    fun save(today: LocalDate, input: XiaoLiuRenInput) {
        prefs.edit().putString("anchor", today.toString()).putString("date", input.date.toString())
            .putInt("month", input.lunarMonth).putInt("day", input.lunarDay).putInt("shichen", input.shichen)
            .putBoolean("leap", input.leapMonth).putBoolean("manual", input.manualNumbers).apply()
    }
}
