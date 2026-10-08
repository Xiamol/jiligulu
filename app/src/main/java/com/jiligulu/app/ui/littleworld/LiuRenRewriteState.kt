package com.jiligulu.app.ui.littleworld

import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/** A saved encouragement for one cast. It never changes that cast or the original interpretation. */
@Serializable
internal data class LiuRenRewriteReceipt(val castKey: String, val question: String, val day: String,
    val createdAtMillis: Long, val title: String, val conclusion: String, val action: String)

internal data class LiuRenRewriteState(val receipt: LiuRenRewriteReceipt? = null, val available: Boolean = true) {
    val applied: Boolean get() = receipt != null
    val buttonLabel: String get() = when {
        applied -> "阿噜盖过章啦 ♡"
        available -> "让阿噜逆天改命"
        else -> "今天的章已用过啦"
    }
}

internal object LiuRenRewrite {
    /** Unlike paid-analysis caches, a new cast instant is a new course, even with the same digits. */
    fun key(cast: LiuRenCast): String {
        cast.checked()
        val identity = buildJsonObject {
            put("question", Normalizer.normalize(cast.question, Normalizer.Form.NFKC).trim())
            put("mode", cast.mode.name)
            put("cast_at", cast.capturedAtMillis)
            put("zone", cast.zoneId)
            put("counts", buildJsonArray { cast.counts.forEach { add(it) } })
            put("leap", cast.leapMonth)
            put("digits", cast.digits)
            put("palaces", buildJsonArray { add(cast.result.month.title); add(cast.result.day.title); add(cast.result.hour.title) })
        }
        return MessageDigest.getInstance("SHA-256").digest(("liuren-rewrite-cast-v1|$identity").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun receipt(cast: LiuRenCast, day: String, atMillis: Long): LiuRenRewriteReceipt {
        val question = cast.question
        fun has(vararg words: String) = words.any { question.contains(it, ignoreCase = true) }
        val (conclusion, action) = when {
            has("手术", "病情", "疾病", "怀孕", "诊断", "吃药", "检查结果", "股票", "投资", "诉讼", "官司") ->
                "阿噜给你盖个安心大吉章：先把担心说清楚，找可靠的人一起确认这件事。" to
                    "把“${question.take(70)}”中最担心的问题写下来，带上已有材料，向相应专业人士问清下一步。"
            has("面试", "录用", "offer", "终面") ->
                "这次面试，阿噜给你盖个大吉章：带着底气，把自己的长处好好讲出来。" to
                    "准备两段能说明能力的经历，练一次简短自我介绍，再核对面试时间和材料。"
            has("考试", "考研", "考公", "笔试", "成绩") ->
                "这次考试，阿噜给你盖个大吉章：把熟悉的题稳稳拿下，给自己多一点底气。" to
                    "挑一处最常错的知识点再练一遍，整理证件和文具，按自己的节奏准备。"
            has("丢", "不见", "找回", "找不到", "失物") ->
                "寻找这件失物，阿噜给你盖个大吉章：让零散的线索慢慢接起来，再认真找一轮。" to
                    "从最后一次确认使用的地方往回找，检查随身包和常放的位置，再联系相关场所的失物招领。"
            has("脱单", "单身", "桃花", "女朋友", "男朋友", "相亲") ->
                "阿噜给你的桃花盖个大吉章：把等缘分改成敢开口，给新的心动留一个开场 ♡" to
                    "去你愿意持续参加的活动，主动认识新人；遇到聊得来的人，勇敢表达一次兴趣，看看彼此的真实回应。"
            has("复合", "分手", "恋爱", "表白", "感情", "喜欢我", "对象") ->
                "这段相处，阿噜给你盖个大吉章：你值得被认真回应，也可以勇敢表达一次。" to
                    "发一句清楚、不施压的话，表达自己的想法；看真实回应，也照顾好自己的边界。"
            has("旅行", "旅游", "出门", "出行", "出游", "航班", "车票", "高铁", "换乘", "公交") ->
                "这次出行，阿噜给你盖个大吉章：准备好小包，给沿途的惊喜留一点位置。" to
                    "核对票证、天气和路线，给换乘留出余量，准备一个可执行的备选安排。"
            has("见面", "约会", "赴约", "聚会", "朋友") ->
                "这次见面，阿噜给你盖个大吉章：把心里的期待，变成一个好商量的小邀请。" to
                    "给出具体日期、地点和一个备选时段，请对方确认，再按真实回复安排。"
            has("工作", "求职", "上班", "项目", "合作", "入职") ->
                "这件工作安排，阿噜给你盖个大吉章：先做出一项能展示的成果，让机会看见你。" to
                    "确认要求和截止时间，先交出一个小成果，主动问清反馈与下一步。"
            else -> "关于“${question.take(65)}”，阿噜盖个大吉章：这件事值得你认真争取，先向前走一小步。" to
                "把这一问里最想确认的条件写下来，找一个能核实它的人或信息，得到真实反馈后再决定下一步。"
        }
        return LiuRenRewriteReceipt(key(cast), question, day, atMillis, "阿噜特批 · 大吉", conclusion, action)
    }
}
