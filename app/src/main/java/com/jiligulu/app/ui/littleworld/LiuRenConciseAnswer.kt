package com.jiligulu.app.ui.littleworld

import java.time.Instant
import java.time.ZoneId

/** Small, honest offline answers. Detailed rules remain in the expandable original course. */
internal object LiuRenConciseAnswer {
    private val relative = Regex("今年|年内|明年|今天|明天|后天|昨天|本周|这周|下周|本月|这个月|下个月|月底|周末|最近|周[一二三四五六日天]|星期[一二三四五六日天]")
    fun relativeDate(cast: LiuRenCast): String? = if (relative.containsMatchIn(cast.question))
        Instant.ofEpochMilli(cast.capturedAtMillis).atZone(ZoneId.of(cast.zoneId)).toLocalDate().toString() else null
    fun horizon(question: String): String {
        val clauses = Regex("[^，,。！？!?]+[！？!?]?").findAll(question).map { it.value }.toList()
        val goal = clauses.filter { Regex("能否|能不能|会不会|可不可以|能.+吗|多久|如何|怎么样|[？?]|吗").containsMatchIn(it) }
            .flatMap { relative.findAll(it).map { match -> match.value }.toList() }.lastOrNull { it != "昨天" }
        return goal ?: relative.findAll(question).map { it.value }.lastOrNull { it != "昨天" }.orEmpty()
    }
    fun answer(cast: LiuRenCast, topic: LiuRenReadingPolicy.Topic): String {
        val whenAsked = horizon(cast.question)
        val opening = if (whenAsked.isEmpty()) "按这课，" else "$whenAsked，"
        val final = cast.result.hour
        val p = LiuRenReadingPolicy.palaces(cast)
        val resistance = p.count { it in setOf(LiuRenPalace.LIU_LIAN, LiuRenPalace.KONG_WANG, LiuRenPalace.CHI_KOU) }
        val slow = final in setOf(LiuRenPalace.LIU_LIAN, LiuRenPalace.KONG_WANG, LiuRenPalace.CHI_KOU) || resistance >= 2
        val opportunity = p.any { it in setOf(LiuRenPalace.SU_XI, LiuRenPalace.XIAO_JI) }
        return opening + when (topic.name) {
            "恋爱与桃花" -> if (slow && opportunity) "有相识的机会，但稳定谈成恋爱偏慢，容易停在犹豫或暧昧。" else if (slow) "桃花偏慢，相识和关系推进都容易反复，先增加真实接触，不急着认定已经谈成。" else "恋爱有推进的机会，更适合主动认识、接触合适的人，再看双方是否愿意确定关系。"
            "恢复这段感情" -> if (slow) "复合偏慢；可以尝试恢复联系，但有回应不等于已经和好，原来的问题仍要磨合。" else "复合有推进余地；先看联系能否恢复，再看原来的矛盾是否真的改变。"
            "这次面试" -> if (slow) "面试有沟通推进的余地，但拿到明确录用还不稳，正面反馈先别当正式 offer。" else "录用有向前推进的机会，重点把终面表达做好，再等明确通知确认。"
            "这次考试" -> if (slow) "通过考试还不算稳，容易卡在准备或收尾；先补未掌握的部分，别只靠临场运气。" else "考试有发挥空间，但能否通过仍看准备与考场表现，优先补薄弱处。"
            "寻找这件失物" -> if (slow) "找回失物还有找线索的余地，但从线索到真正拿回可能反复，别把猜测当找到。" else "失物有出现新线索的余地，先沿最后确认的位置和招领信息查，不凭签象猜方位。"
            "这次出行" -> if (slow) "行程能否顺利还要看最后一段安排，换乘或确认容易拖慢，先准备备选交通。" else "出行有顺利推进的余地，先把票证、路线和换乘余量落实。"
            "这次见面安排" -> if (slow) "见面有商量成的余地，但约定仍容易变化，等对方明确确认后再把它当约成。" else "见面有落实的机会，具体约好时间地点，再请对方确认一次。"
            "这件工作安排" -> if (slow) "工作有小进展的余地，但最终落实偏慢，优先确认卡住的要求与反馈。" else "工作有推进机会，先交付一项明确成果，再核实后续安排。"
            else -> "“${cast.question.take(45)}”更偏${if (slow) "还需等待、反复确认" else "有推进的余地"}，先观察真实变化，再判断目标是否落实。"
        }
    }
    fun explainsChain(text: String, p: List<LiuRenPalace>): Boolean {
        val ends = Regex("(?:两头|首尾)(?:都是|是|同为|都为|均为)?(大安|留连|速喜|赤口|小吉|空亡)").find(text)?.groupValues?.get(1)
        if (ends != null && (p[0].title != ends || p[2].title != ends)) return false
        val named = p.fold(0) { cursor, palace -> if (cursor < 0) -1 else text.indexOf(palace.title, cursor).let { if (it < 0) -1 else it + palace.title.length } } >= 0
        if (named) return true
        if (p[0] == p[2] && p[0] != p[1] && text.contains(p[0].title) && text.contains(p[1].title) &&
            Regex("两头|首尾|前后|起点和|开头和").containsMatchIn(text) && Regex("中间|中途|中宫|过程").containsMatchIn(text)) return true
        if (p.distinct().size == 1 && text.contains(p[0].title) && Regex("三宫|三个|三段|都|全程|一路").containsMatchIn(text)) return true
        if (p[0] == p[1] && text.contains(p[0].title) && text.contains(p[2].title) && Regex("前两|开始和过程|起点和过程").containsMatchIn(text)) return true
        if (p[1] == p[2] && text.contains(p[0].title) && text.contains(p[1].title) && Regex("后两|中后|过程和|中间和").containsMatchIn(text)) return true
        if (p[0] == p[1] && Regex("前两|开始和过程|起点和过程").containsMatchIn(text) && Regex("最后|后段|收尾|结果|趋向").containsMatchIn(text)) return true
        if (p[1] == p[2] && Regex("后两|中后|过程和|中间和").containsMatchIn(text) && Regex("起初|开头|开始|起点|先").containsMatchIn(text)) return true
        return Regex("起初|开头|开始|起点|先").containsMatchIn(text) && Regex("中间|中途|过程|接着|随后").containsMatchIn(text) && Regex("最后|后段|后面|收尾|结果|落点").containsMatchIn(text)
    }
    fun reason(cast: LiuRenCast, topic: LiuRenReadingPolicy.Topic): String {
        val p = LiuRenReadingPolicy.palaces(cast)
        fun word(palace: LiuRenPalace) = when (palace) {
            LiuRenPalace.DA_AN -> "条件偏稳"; LiuRenPalace.LIU_LIAN -> "容易等待或反复"
            LiuRenPalace.SU_XI -> "有消息或推进"; LiuRenPalace.CHI_KOU -> "容易有沟通分歧"
            LiuRenPalace.XIAO_JI -> "有一点接触或进展"; LiuRenPalace.KONG_WANG -> "结果还没落实"
        }
        val chain = if (p.first() == p.last() && p.first() != p[1])
            "两头${p.first().title}、中间${p[1].title}：前后${word(p.first())}，中间${word(p[1])}。"
        else "${p[0].title}→${p[1].title}→${p[2].title}：先${word(p[0])}，中间${word(p[1])}，最后${word(p[2])}。"
        return chain + when (topic.name) {
            "恋爱与桃花" -> "所以认识人的机会和确定恋爱关系，要分开看。"
            "恢复这段感情" -> "所以先有联系与真正复合，不是同一步。"
            "这次面试" -> "所以面试反馈与正式录用，不能当成同一件事。"
            "寻找这件失物" -> "所以有线索不等于已经拿回失物。"
            "这次出行" -> "所以过程能推进，最后一段的交通安排仍要确认。"
            else -> "三段连起来看，前面的进展仍要到最后才能确认落实。"
        }
    }
}
