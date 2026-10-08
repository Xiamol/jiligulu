package com.jiligulu.app.ui.littleworld

import java.time.LocalDate

internal enum class LiuRenPalace(val title: String, val mark: String, val message: String) {
    DA_AN("大安", "🌿", "先把手边的小事安稳做好。阿噜给你留一块慢慢来的地方。"),
    LIU_LIAN("留连", "🍃", "不急着给答案，再想一小会儿也好。让念头先在窗边坐坐。"),
    SU_XI("速喜", "🌸", "想到的小快乐，今天就试着留下来吧。阿噜想听你的好消息。"),
    CHI_KOU("赤口", "☕", "回复之前缓一口气，温柔一点说。话可以慢慢讲，茶也可以慢慢喝。"),
    XIAO_JI("小吉", "⭐", "把目标缩成一小步，走完就给自己盖个章。小小的好运也值得收藏。"),
    KONG_WANG("空亡", "☁️", "空白也能装下新的可能。先放下一个纠结，给今天腾一点位置。")
}

internal data class XiaoLiuRenInput(val date: LocalDate, val lunarMonth: Int, val lunarDay: Int,
    val shichen: Int, val leapMonth: Boolean = false, val manualNumbers: Boolean = false) {
    init { require(lunarMonth in 1..12 && lunarDay in 1..30 && shichen in 1..12) }
}

internal data class XiaoLiuRenResult(val month: LiuRenPalace, val day: LiuRenPalace, val hour: LiuRenPalace)

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
        val month = input.lunarMonth - 1
        val day = month + input.lunarDay - 1
        val hour = day + input.shichen - 1
        val palaces = LiuRenPalace.entries
        return XiaoLiuRenResult(palaces[month % 6], palaces[day % 6], palaces[hour % 6])
    }
}
