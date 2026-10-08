package com.jiligulu.app.ui.littleworld

import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

internal enum class LiuRenPalace(val title: String) {
    DA_AN("大安"), LIU_LIAN("留连"), SU_XI("速喜"), CHI_KOU("赤口"), XIAO_JI("小吉"), KONG_WANG("空亡")
}

internal data class XiaoLiuRenInput(val date: LocalDate, val lunarMonth: Int, val lunarDay: Int,
    val shichen: Int, val leapMonth: Boolean = false, val manualNumbers: Boolean = false) {
    init { require(lunarMonth in 1..12 && lunarDay in 1..30 && shichen in 1..12) }
}

internal data class XiaoLiuRenResult(val month: LiuRenPalace, val day: LiuRenPalace, val hour: LiuRenPalace)

@Serializable internal enum class LiuRenMode { TIME, NUMBERS }
@Serializable internal enum class LiuRenStep { QUESTION, METHOD, RESULT }

/** The instant and local calendar facts belong to this question, not the current screen clock. */
@Serializable
internal data class LiuRenCast(val question: String, val mode: LiuRenMode, val capturedAtMillis: Long,
    val zoneId: String, val lunarMonth: Int, val lunarDay: Int, val shichen: Int,
    val leapMonth: Boolean = false, val digits: String = "") {
    fun checked(): LiuRenCast {
        require(question.isNotBlank() && question.length <= 180)
        require(capturedAtMillis >= 0)
        require(Instant.ofEpochMilli(capturedAtMillis).atZone(ZoneId.of(zoneId)).year in 1900..2100)
        require(lunarMonth in 1..12 && lunarDay in 1..30 && shichen in 1..12)
        if (mode == LiuRenMode.NUMBERS) require(XiaoLiuRen.digitCounts(digits) != null)
        else require(digits.isEmpty())
        return this
    }
    val counts: List<Int> get() = if (mode == LiuRenMode.TIME) listOf(lunarMonth, lunarDay, shichen)
        else requireNotNull(XiaoLiuRen.digitCounts(digits))
    val result: XiaoLiuRenResult get() = XiaoLiuRen.forCounts(counts)
}

@Serializable
internal data class LiuRenSession(val question: String = "", val mode: LiuRenMode = LiuRenMode.TIME,
    val digits: String = "", val step: LiuRenStep = LiuRenStep.QUESTION, val cast: LiuRenCast? = null)

/**
 * Independently implemented inclusive counting, not a factual prediction or a random score.
 * Rule descriptions reviewed: github.com/kev1nzh37/liuren and github.com/Sneezry/XiaoLiuRen-MCP.
 * No source code, verses, interpretations or assets are copied from either project.
 */
internal object XiaoLiuRen {
    val branches = listOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
    fun shichen(hour: Int): Int {
        require(hour in 0..23)
        return ((hour + 1) % 24) / 2 + 1
    }
    fun forInput(input: XiaoLiuRenInput): XiaoLiuRenResult {
        return forCounts(listOf(input.lunarMonth, input.lunarDay, input.shichen))
    }
    /** Three successive inclusive counts, also reviewed against mxwz/astrbot_plugin_zhanbu/xlr.py. */
    fun forCounts(counts: List<Int>): XiaoLiuRenResult {
        require(counts.size == 3 && counts.all { it in 1..30 })
        val month = counts[0] - 1
        val day = month + counts[1] - 1
        val hour = day + counts[2] - 1
        val palaces = LiuRenPalace.entries
        return XiaoLiuRenResult(palaces[month % 6], palaces[day % 6], palaces[hour % 6])
    }
    /** This version uses the explicit reported-number convention 0 → 10, not a silent skipped step. */
    fun digitCounts(text: String): List<Int>? = text.takeIf { it.matches(Regex("[0-9]{3}")) }
        ?.map { if (it == '0') 10 else it.digitToInt() }
}
