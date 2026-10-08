package com.jiligulu.app.ui.littleworld

import android.content.SharedPreferences
import java.time.LocalDate

internal enum class FortuneRewriteKind(val preferenceKey: String) {
    HOROSCOPE("rewritten_day"), LIU_REN("liuren_rewritten_day")
}

/** Preserve the existing horoscope stamp without spending the other game's daily stamp. */
internal class FortuneRewriteUsage(private val prefs: SharedPreferences) {
    fun used(kind: FortuneRewriteKind, date: LocalDate): Boolean = prefs.getString(kind.preferenceKey, "") == date.toString()
    @Synchronized fun mark(kind: FortuneRewriteKind, date: LocalDate): Boolean {
        if (used(kind, date)) return false
        prefs.edit().putString(kind.preferenceKey, date.toString()).apply()
        return true
    }
}
