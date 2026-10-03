package com.jiligulu.app.ui.littleworld

import java.time.LocalDate

data class LittleFortune(val id: Int, val title: String, val text: String, val mark: String)

/** Each local calendar day has one stable note, including after a restart. */
object DailyFortunes {
    val all = listOf(
        LittleFortune(0, "小小的晴天", "找一小块阳光待五分钟吧，阿噜给你留了个位置。", "☀️"),
        LittleFortune(1, "慢一点也好", "今天允许自己慢半拍。走得稳稳的，也是在往前走。", "🐢"),
        LittleFortune(2, "给生活加糖", "认真尝一口喜欢的东西。甜不甜，都值得好好吃完。", "🍬"),
        LittleFortune(3, "一颗小勇气", "那个拖着没做的小事，先做两分钟。阿噜陪你开个头。", "🌱"),
        LittleFortune(4, "遇见旧朋友", "给想到的人发一句问候吧，不用想好长长的开场白。", "💌"),
        LittleFortune(5, "口袋里的月亮", "今天不需要很厉害。把自己照顾好，也是一件大事。", "🌙"),
        LittleFortune(6, "窗边有风", "抬头看看窗外。云今天走到哪里啦？", "☁️"),
        LittleFortune(7, "给桌面松口气", "收拾一个巴掌大的地方就够了。小小的整齐，也很舒服。", "🧸"),
        LittleFortune(8, "一口温柔", "喝水的时候放下手机，专心喝完这一杯。阿噜也举杯啦。", "🥤"),
        LittleFortune(9, "星星不着急", "愿望可以慢慢攒。今天放进去一颗星星，也已经很棒。", "⭐"),
        LittleFortune(10, "给自己留座", "忙归忙，记得给午饭留一点时间。你也值得被认真招待。", "🍚"),
        LittleFortune(11, "今天的票根", "拍下一个让你停顿的小瞬间。以后翻到，可能会笑一下。", "📷"),
        LittleFortune(12, "好好休息许可", "早点躺下来吧。今天没做完的，明天可以接着做。", "🛌"),
        LittleFortune(13, "小小的冒险", "回家的路上换一条熟悉又不太一样的路，看看新的风景。", "🚌"),
        LittleFortune(14, "好运先放口袋", "给自己夸一句具体的：今天哪件小事，你做得还不错？", "🍀"),
        LittleFortune(15, "歌声来陪你", "找一首很久没听的歌。阿噜想知道，它会把你带回哪一天。", "🎵"),
        LittleFortune(16, "柔软一点", "肩膀放松，手腕转转。你不是一根一直绷着的弦。", "🫶"),
        LittleFortune(17, "一封未来的信", "给过阵子的自己留一句话吧。到时候阿噜帮你递过去。", "✉️"),
        LittleFortune(18, "今天也有花", "去看看附近的一棵树、一盆花，或者路边认真长着的小草。", "🌷"),
        LittleFortune(19, "小口袋计划", "想买的东西先放进愿望候场区。喜欢可以留着慢慢想。", "🎁"),
        LittleFortune(20, "阿噜的抱抱", "如果今天有点难，就先把事情缩小一点。阿噜在这里。", "💜"),
        LittleFortune(21, "不用等完美", "把那个小作品、小想法留下来。现在的样子也值得记录。", "🖍️"),
        LittleFortune(22, "坐下来看看", "今天挑个舒服的位置，什么都不做一会儿。发呆也有用。", "🪑"),
        LittleFortune(23, "把日子装好", "睡前想一件今天的小好事，给这一页盖个轻轻的印章。", "📖"),
        LittleFortune(24, "零碎也算数", "攒钱不用等一大笔。愿望瓶里，一颗一颗星星都会留下。", "✨"),
        LittleFortune(25, "和自己和好", "没有按计划完成，也不等于白忙。给今天留一点余地吧。", "🕊️"),
        LittleFortune(26, "小小感谢卡", "想到一件被照顾的小事，就说声谢谢。也别忘了谢谢自己。", "🎀"),
        LittleFortune(27, "闻到生活啦", "留意一种好闻的味道：饭香、洗好的衣服，或刚下过雨的路。", "🌧️"),
        LittleFortune(28, "你的步子很好", "花两分钟站起来走走。走到门口再回来，也算一次小出发。", "👟"),
        LittleFortune(29, "一页一页来", "别一次背着整个月。先把今天这一页过好，阿噜给你压着书角。", "📚"),
        LittleFortune(30, "今晚的温柔", "把灯调暗一点，给眼睛也放个小假。", "🕯️"),
        LittleFortune(31, "路过的惊喜", "今天发现一个可爱的东西，就偷偷记下来。阿噜想一起看。", "🐾")
    )

    fun forDate(date: LocalDate): LittleFortune = all[Math.floorMod(date.toEpochDay(), all.size.toLong()).toInt()]
}
