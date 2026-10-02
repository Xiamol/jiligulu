package com.jiligulu.app.ui.settings

/** Local notes: a complete lap before repeating, without an AI request or timer. */
internal object CompanionCornerNotes {
    private val notes = listOf(
        "{name}，小账单交给我，\n今天的小开心留给你。",
        "有些日子没什么大事，\n吃好一顿饭也很值得。",
        "阿噜的脑袋软乎乎，\n装着账单，也装着你。",
        "买到喜欢的东西了？\n那份开心也算收获呀。",
        "{name}，先伸个懒腰，\n再来慢慢安排今天。",
        "零钱一枚枚收好，\n日子一天天过好。",
        "今天有点累的话，\n这块小角落借你坐坐。",
        "阿噜今天的待办：\n记账，还有等你来。",
        "{name}，路过花店时，\n也可以只看看花呀。",
        "钱包要照顾，\n肚子也别受委屈。",
        "记下今天的一杯茶，\n以后翻到会想起香气。",
        "没什么新鲜事也没关系，\n平平安安就很好。",
        "{name}，给自己留一点\n不用赶路的时间吧。",
        "预算可以慢慢调整，\n生活也可以慢慢来。",
        "今天走了多少路呀？\n阿噜在这儿等你歇脚。",
        "如果心里有点乱，\n先整理一张小账单吧。",
        "{name}，小水杯报到！\n喝完水再忙也来得及。",
        "你认真记下的每一笔，\n阿噜都有好好收着。",
        "攒钱也好，奖励自己也好，\n都是在安排自己的生活。",
        "小角落今日营业：\n一份安静，一只阿噜。",
        "{name}，记得看看窗外，\n今天的天空什么颜色？",
        "有机会的话，\n和喜欢的人好好吃顿饭。",
        "今天的小目标：\n少皱一会儿眉头。",
        "阿噜不催你变厉害，\n陪你把今天过完就好。",
        "{name}，晚点也没关系，\n忘记的账还能补回来。",
        "喜欢的歌再听一遍，\n喜欢的日子慢慢收藏。",
        "给明天留一点零钱，\n也给今天留一点甜。",
        "不用每天都很精彩，\n普通的一天也属于你。",
        "{name}，刚才戳的是阿噜，\n收到的是一份小小惦记。",
        "阿噜的小芽摇了摇：\n嗯，今天也见到你啦。",
        "东西要买合适的，\n日子要过舒服的。",
        "悄悄话说得轻一点，\n陪你的心意放得满一点 ♡"
    )

    fun randomIndex(): Int = notes.indices.random()

    fun nextIndex(current: Int): Int = (current.mod(notes.size) + 1) % notes.size

    fun render(index: Int, nickname: String, suffix: String): String {
        val address = nickname.trim().take(20).let { name ->
            if (name.isBlank()) "朋友" else name + suffix.trim().ifBlank { "大人" }.take(8)
        }
        return notes[index.mod(notes.size)].replace("{name}", address)
    }
}
