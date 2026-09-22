package com.jiligulu.app.domain.chat

/**
 * 本地关键词预判，决定本轮要不要给模型塞「候选账单 / 回收站候选 / 其他账单」。
 *
 * 为什么要本地先判一次：候选三段的体积远大于账本摘要，而绝大多数输入（闲聊、记账）
 * 根本用不到它们。把它们无条件塞进去，既烧 token，又会让模型看到一堆用不上的 id 后瞎猜。
 *
 * ⚠️ 下面三条正则是**经验值**，一定会误判——漏判时模型拿不到 target_id，会退化成「反问是哪一笔」，
 * 不会瞎改；多判时只是多花一点 token。首版先按这套上线，将来按线上日志的漏判/多判比例调参。
 * 纯函数、无 Android 依赖，方便单测直接喂字符串。
 */
object ChatIntent {
    /** 改/删/恢复：命中才注入 `{candidates}`。覆盖「改删恢复」的常见说法与时间指代（那笔/这笔/星期X）。 */
    private val CANDIDATE = Regex("改|删|恢复|撤销|撤|捞|还回|纠正|更正|记错|算错|不对|弄错|整理|那笔|这笔|刚才|之前那|星期[一二三四五六日天]")

    /** 恢复/回收站：命中才注入 `{trashCandidates}`。 */
    private val RESTORE = Regex("恢复|撤销|撤|捞|还回|回收站|垃圾桶|清空")

    /** 整理「其他」：命中才注入 `{otherBills}`（R2-整理）。 */
    private val OTHER = Regex("整理|收纳|收拾|其他里|一类|归类")

    fun needsCandidates(s: String) = CANDIDATE.containsMatchIn(s)

    fun needsTrash(s: String) = RESTORE.containsMatchIn(s)

    fun needsOtherBills(s: String) = OTHER.containsMatchIn(s)
}
