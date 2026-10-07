package com.jiligulu.app.ui.littleworld

import java.time.LocalDate
import kotlin.random.Random

/** A local, date-stable little play; it makes no factual prediction or paid API request. */
internal data class DailyLuck(val title: String, val mood: Int, val inspiration: Int, val company: Int,
    val luckyColor: String, val goodFor: String, val letGo: String, val message: String)

internal object DailyLuckEngine {
    val signs = listOf("随缘星座", "白羊", "金牛", "双子", "巨蟹", "狮子", "处女", "天秤", "天蝎", "射手", "摩羯", "水瓶", "双鱼")
    private val colors = listOf("雾紫", "奶油白", "鼠尾草绿", "桃子粉", "晴空蓝", "暖杏")
    private val good = listOf("收藏一个小瞬间", "和老朋友聊两句", "给自己腾一块空位", "把喜欢的歌再听一遍", "给愿望放一颗星星", "认真吃一顿饭", "出门看看云", "给未来寄一句话")
    private val letGo = listOf("替明天着急", "和别人比进度", "睡前反复纠结", "为了优惠硬凑单", "把小失误放很大", "对自己太严格")
    fun forDate(date: LocalDate, sign: String, rewritten: Boolean): DailyLuck {
        val safeSign = sign.takeIf { it in signs } ?: signs.first()
        val random = Random((date.toEpochDay() xor safeSign.hashCode().toLong()).toInt())
        val mood = random.nextInt(2, 6)
        val inspiration = random.nextInt(2, 6)
        val company = random.nextInt(2, 6)
        val title = listOf("慢慢来的小吉", "刚刚好的中吉", "口袋满满的大吉")[random.nextInt(3)]
        return DailyLuck(if (rewritten) "阿噜特批大吉" else title,
            if (rewritten) 5 else mood, if (rewritten) maxOf(4, inspiration) else inspiration,
            if (rewritten) maxOf(4, company) else company, colors[random.nextInt(colors.size)],
            good[random.nextInt(good.size)], letGo[random.nextInt(letGo.size)],
            if (rewritten) "阿噜盖过章啦！今天给你多留一点勇气。" else "运势是小玩笑，认真照顾自己是今天的小魔法。")
    }
}

internal data class WheelTask(val text: String, val destination: String = "")
internal object FortuneWheelTasks {
    val labels = listOf("喝口水", "放松一下", "寄封信", "翻照片", "小愿望", "悄悄话")
    val groups = listOf(
        listOf(WheelTask("给水杯续一口温柔"), WheelTask("慢慢喝一杯水"), WheelTask("和阿噜碰个杯")),
        listOf(WheelTask("把肩膀放下来两分钟"), WheelTask("找一首喜欢的歌"), WheelTask("去看看窗外的云"), WheelTask("给桌面腾一小块空位")),
        listOf(WheelTask("写封未来信", "future"), WheelTask("给未来的自己留个鼓励", "future"), WheelTask("寄一张今天的小心情", "future")),
        listOf(WheelTask("看一张照片", "memories"), WheelTask("翻一页珍藏的生活", "memories"), WheelTask("找找一张会让你笑的照片", "memories")),
        listOf(WheelTask("想个小愿望"), WheelTask("给今天的自己盖个赞"), WheelTask("想买的东西先留一晚"), WheelTask("认真尝一口喜欢的味道")),
        listOf(WheelTask("听一句悄悄话", "paper"), WheelTask("去秘密纸条歇一会儿", "paper"), WheelTask("让阿噜说一句好听的", "paper")))
}
