package com.jiligulu.app.ui.littleworld

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.text.Normalizer

internal enum class LiuRenElement(val label: String) { WOOD("木"), FIRE("火"), EARTH("土"), METAL("金"), WATER("水") }
@Serializable internal enum class LiuRenRelation(val label: String) {
    SAME("五行比和"), GENERATES("前宫生后宫"), GENERATED_BY("后宫生前宫"),
    CONTROLS("前宫克后宫"), CONTROLLED_BY("后宫克前宫")
}
@Serializable internal data class LiuRenReadingStage(val palace: String, val text: String)
@Serializable internal data class LiuRenReadingLink(val from: String, val to: String, val text: String,
    val relation: LiuRenRelation)
@Serializable internal data class LiuRenReading(val question: String, val summary: String,
    val stages: List<LiuRenReadingStage>, val links: List<LiuRenReadingLink>, val advice: String) {
    fun plainText(): String = buildString {
        appendLine(summary)
        stages.forEachIndexed { index, stage ->
            appendLine("${LiuRenReadingPolicy.roles[index]} · ${stage.palace}：${stage.text}")
            links.getOrNull(index)?.let { appendLine("${it.from}→${it.to}：${it.text}") }
        }
        append(advice)
    }
}

/** A declared six-palace interpretation convention, not a claim of a universal school. */
internal object LiuRenReadingPolicy {
    const val VERSION = "six-palace-question-chain-v4"
    const val MAX_STORED_CHARS = 2400
    val roles = listOf("起点", "过程", "趋向")
    private val json = Json { ignoreUnknownKeys = true }
    fun canonicalQuestion(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).trim().replace(Regex("[\\s\\p{Z}]+"), " ")
    fun palaces(cast: LiuRenCast) = listOf(cast.result.month, cast.result.day, cast.result.hour)
    fun element(palace: LiuRenPalace): LiuRenElement = when (palace) {
        LiuRenPalace.DA_AN -> LiuRenElement.WOOD; LiuRenPalace.SU_XI -> LiuRenElement.FIRE
        LiuRenPalace.CHI_KOU -> LiuRenElement.METAL; LiuRenPalace.XIAO_JI -> LiuRenElement.WATER
        LiuRenPalace.LIU_LIAN, LiuRenPalace.KONG_WANG -> LiuRenElement.EARTH
    }
    fun relation(from: LiuRenPalace, to: LiuRenPalace) = relation(element(from), element(to))
    fun relation(from: LiuRenElement, to: LiuRenElement): LiuRenRelation {
        val generate = listOf(LiuRenElement.WOOD, LiuRenElement.FIRE, LiuRenElement.EARTH, LiuRenElement.METAL, LiuRenElement.WATER)
        val control = listOf(LiuRenElement.WOOD, LiuRenElement.EARTH, LiuRenElement.WATER, LiuRenElement.FIRE, LiuRenElement.METAL)
        fun next(order: List<LiuRenElement>, value: LiuRenElement) = order[(order.indexOf(value) + 1) % order.size]
        return when {
            from == to -> LiuRenRelation.SAME
            next(generate, from) == to -> LiuRenRelation.GENERATES
            next(generate, to) == from -> LiuRenRelation.GENERATED_BY
            next(control, from) == to -> LiuRenRelation.CONTROLS
            else -> LiuRenRelation.CONTROLLED_BY
        }
    }
    fun symbol(palace: LiuRenPalace) = when (palace) {
        LiuRenPalace.DA_AN -> "稳定、已有基础、宜先守住"
        LiuRenPalace.LIU_LIAN -> "反复、拖延、尚未理顺"
        LiuRenPalace.SU_XI -> "消息、转机、推进较快"
        LiuRenPalace.CHI_KOU -> "沟通分歧、措辞与细节"
        LiuRenPalace.XIAO_JI -> "协作、小进展、逐步落实"
        LiuRenPalace.KONG_WANG -> "线索或回应不足、尚未落实"
    }
    private fun movement(palace: LiuRenPalace) = when (palace) {
        LiuRenPalace.DA_AN -> "先稳住基础"; LiuRenPalace.LIU_LIAN -> "容易反复或等待"
        LiuRenPalace.SU_XI -> "留意消息和推进"; LiuRenPalace.CHI_KOU -> "留意沟通分歧"
        LiuRenPalace.XIAO_JI -> "争取一小步落实"; LiuRenPalace.KONG_WANG -> "还要核实，不能当作已落实"
    }
    private fun questionOutlook(question: String, topic: Topic, last: LiuRenPalace): String {
        val subject = when (topic.name) {
            "这次面试" -> if (Regex("怎么|怎样|如何|准备").containsMatchIn(question)) "面试准备" else "录用与下一步机会"
            "这次考试" -> "考试发挥与结果"; "寻找这件失物" -> "能否找回这件失物"
            "这段相处" -> "这段关系的推进"; "这次见面安排" -> "这次邀约能否落实"
            "这次出行" -> "行程能否落实"; "这件工作安排" -> "这件工作的具体进展"
            else -> "你最想确认的结果"
        }
        val outlook = when (last) {
            LiuRenPalace.DA_AN -> "更偏向稳住已有条件，不宜把稳定当成突然的大突破"
            LiuRenPalace.LIU_LIAN -> "还要留意等待和反复，别太早把事情当成已经定下来"
            LiuRenPalace.SU_XI -> "重点看新消息和短期推进，再核实它能不能落实"
            LiuRenPalace.CHI_KOU -> "最后仍要留意沟通与细节分歧，不宜急着作定论"
            LiuRenPalace.XIAO_JI -> "可以争取一次具体的小推进，但小进展不等于全部目标达成"
            LiuRenPalace.KONG_WANG -> "目前不宜认定已落实，先核实信息、准备备选"
        }
        return "问$subject，$outlook。"
    }
    internal data class Topic(val name: String, val focuses: List<String>, val anchors: List<String>, val action: String)
    fun sensitive(question: String) = listOf("手术", "诊断", "癌", "吃药", "疾病", "病情", "治愈", "怀孕", "检查结果",
        "投资", "股票", "彩票", "官司", "诉讼", "判刑").any(question::contains)
    fun topic(question: String): Topic {
        fun found(vararg words: String) = words.any { question.contains(it, ignoreCase = true) }
        return when {
            found("面试", "终面", "录用", "offer") -> Topic("这次面试", listOf("面试准备", "现场交流与流程", "后续录用消息"),
                listOf("面试", "录用", "沟通", "流程", "准备", "材料"), "先核对面试时间、材料和两段能说明能力的经历，再问清后续通知方式。")
            found("考试", "考研", "考公", "成绩", "笔试") -> Topic("这次考试", listOf("复习基础", "考场发挥与节奏", "成绩与后续安排"),
                listOf("考试", "复习", "成绩", "考场", "题", "发挥"), "优先补一处复习薄弱点，确认考试时间和路线；成绩出来后再决定下一步。")
            found("丢", "不见", "找回", "找不到", "失物") -> Topic("寻找这件失物", listOf("已有线索", "查找与回溯", "新线索是否落实"),
                listOf("失物", "寻找", "线索", "查找", "找回", "找"), "从最后确认使用的位置倒着回想，再问相关场所的失物招领；没有线索时别凭签象猜方位。")
            found("复合", "分手", "恋爱", "表白", "感情", "喜欢我", "对象") -> Topic("这段相处", listOf("你们的交流基础", "表达与回应", "是否有明确互动"),
                listOf("相处", "关系", "交流", "表达", "回应", "沟通"), "用一次清楚而不施压的沟通表达自己的想法，观察真实回应，保留彼此的边界。")
            found("见面", "约会", "赴约", "聚会", "朋友") -> Topic("这次见面安排", listOf("邀约与时间安排", "商量地点和时刻", "约定是否落实"),
                listOf("见面", "邀约", "安排", "时间", "地点", "约定"), "把具体日期、地点和备选时段一次说清，再请对方确认；没有确认就保留备选安排。")
            found("旅行", "旅游", "出门", "出行", "出游", "航班", "车票") -> Topic("这次出行", listOf("出行准备", "路线与临时变化", "行程是否落实"),
                listOf("出行", "行程", "路线", "时间", "准备", "车票"), "检查票证、天气和路线，给换乘留出余量，再准备一个能执行的备选方案。")
            found("工作", "求职", "上班", "项目", "合作", "入职") -> Topic("这件工作安排", listOf("任务与准备", "沟通和执行", "是否有具体进展"),
                listOf("工作", "任务", "项目", "合作", "进展", "执行"), "先确认要求、责任和截止时间，推进一项能交付的小成果，再核实对方反馈。")
            else -> Topic("你问的这件事", listOf("已有条件", "推进中的变化", "事情是否落实"), emptyList(),
                "把“${question.take(60)}”中最想确认的条件列出来，先做一件能核实它的小事，再根据真实反馈调整。")
        }
    }
    fun targetKeywords(question: String): List<String> {
        val declared = topic(question).anchors
        if (declared.isNotEmpty()) return declared
        val nouns = question.replace(Regex("阿噜|帮我|算算|最近|明天|今天|这次|这件事|能不能|会不会|好不好|可以|能否|是否|怎么|怎样|什么|时候|什么时候|有没有|适不适合|我|你|他|她|它|的|吗|呢|呀|能|会|了|想|该"), " ")
        return Regex("[\\p{L}0-9]{2,24}").findAll(nouns).flatMap { token ->
            if (token.value.length <= 4) sequenceOf(token.value) else token.value.windowed(2).asSequence()
        }.distinct().take(24).toList()
    }
    fun local(cast: LiuRenCast): LiuRenReading {
        val question = cast.question; val topic = topic(question); val palaces = palaces(cast)
        val caution = sensitive(question)
        val stages = palaces.mapIndexed { index, palace -> LiuRenReadingStage(palace.title,
            if (caution) "${roles[index]}的${palace.title}是“${symbol(palace)}”的民俗象意；它不能判定“${question.take(50)}”的真实结果。"
            else "${palace.title}的象意是${symbol(palace)}。放到${if (topic.anchors.isEmpty()) "“${question.take(45)}”" else topic.name}里，" + when (palace) {
                LiuRenPalace.DA_AN -> "先看${topic.focuses[index]}是否能稳住，别跳过已经具备的条件。"
                LiuRenPalace.LIU_LIAN -> "${topic.focuses[index]}可能需要反复确认或等待；尚未得到回复的部分先别当成确定。"
                LiuRenPalace.SU_XI -> "${topic.focuses[index]}留意新增消息与推进机会，同时核实消息对应的是哪一步。"
                LiuRenPalace.CHI_KOU -> "${topic.focuses[index]}留意说法和细节分歧；问清楚，比急着推进更有用。"
                LiuRenPalace.XIAO_JI -> "${topic.focuses[index]}适合争取能确认的一小步，具体反馈比空泛承诺有用。"
                LiuRenPalace.KONG_WANG -> "${topic.focuses[index]}还缺线索或明确回应；眼下不能把目标当成已经实现。"
            }) }
        val links = palaces.zipWithNext().map { (from, to) -> LiuRenReadingLink(from.title, to.title,
            "${from.title}到${to.title}，象意由“${movement(from)}”转向“${movement(to)}”。" + when (relation(from, to)) {
                LiuRenRelation.SAME -> if (from == to) "同宫重复，强调这条线索延续，不等于事情自动变好。" else "同属${element(from).label}，但宫意不同，不能把比和直接当顺利。"
                LiuRenRelation.GENERATES -> "前宫生后宫，可理解为前一段为下一段提供条件；仍要看这次事情有没有实际推进。"
                LiuRenRelation.GENERATED_BY -> "后宫生前宫，可理解为后来的消息或行动补回前段条件，不是全程毫无阻碍。"
                LiuRenRelation.CONTROLS -> "前宫克后宫，前段的限制会牵制下一段；要先处理卡住的条件。"
                LiuRenRelation.CONTROLLED_BY -> "后宫克前宫，后段会反过来约束前段；不能只因开头顺就认定最后稳。"
            }, relation(from, to)) }
        return LiuRenReading(question,
            if (caution) "你问的是“${question.take(100)}”。三宫可以解释民俗象意，但不能判断这类医疗、财务或法律结果；阿噜先陪你整理可核实的条件。"
            else "关于“${question.take(60)}”：起头${movement(palaces[0])}，中途${movement(palaces[1])}，后段${movement(palaces[2])}。" + questionOutlook(question, topic, palaces[2]),
            stages, links, if (caution) "把担心、已有事实和待确认的问题分开，向有资质的专业人士核实；小盘不替你作现实判断。" else topic.action)
    }
    fun encode(reading: LiuRenReading) = json.encodeToString(LiuRenReading.serializer(), reading)
    fun decode(raw: String, cast: LiuRenCast): LiuRenReading? = runCatching {
        if (raw.length > MAX_STORED_CHARS) return null
        json.decodeFromString(LiuRenReading.serializer(), raw).takeIf { valid(it, cast) }
    }.getOrNull()
    fun decode(data: JsonObject, cast: LiuRenCast): LiuRenReading? = decode(data.toString(), cast)
    private fun valid(reading: LiuRenReading, cast: LiuRenCast): Boolean {
        if (canonicalQuestion(reading.question) != canonicalQuestion(cast.question) || reading.summary.length !in 20..240 || reading.advice.length !in 12..180 ||
            reading.stages.size != 3 || reading.links.size != 2) return false
        val palaces = palaces(cast)
        if (reading.stages.withIndex().any { (index, stage) -> stage.palace != palaces[index].title || stage.text.length !in 20..160 }) return false
        if (reading.links.withIndex().any { (index, link) -> link.from != palaces[index].title || link.to != palaces[index + 1].title ||
                link.relation != relation(palaces[index], palaces[index + 1]) || link.text.length !in 20..180 }) return false
        val text = listOf(reading.summary, reading.advice).plus(reading.stages.map { it.text }).plus(reading.links.map { it.text })
            .joinToString("\n").replace(cast.question, "").replace(cast.question.take(100), "")
            .replace(cast.question.take(60), "").replace(cast.question.take(50), "").replace(cast.question.take(45), "")
        fun assertion(pattern: Regex): Boolean = pattern.findAll(text).any { match ->
            val prefix = text.substring(0, match.range.first).takeLast(100)
                .substringAfterLast('。').substringAfterLast('，').substringAfterLast(',').substringAfterLast('；').substringAfterLast('\n')
            val suffix = text.substring(match.range.last + 1).takeWhile { it !in "。；;，,\n" }
            val denied = prefix.endsWith("不") || prefix.endsWith("未") ||
                Regex("不能|无法|不可|不可能|不意味|不表示|不等于|不构成|不保证|不一定|不必然|不代表|不是|并非|未必|是否|能否").containsMatchIn(prefix)
            !denied && !Regex("吗|[？?]").containsMatchIn(suffix)
        }
        if (assertion(Regex("保证|必定|一定会|百分之百|100%|必然|包你|肯定会|注定"))) return false
        val anchors = targetKeywords(cast.question)
        if (!sensitive(cast.question) && (anchors.isEmpty() || reading.stages.any { stage -> anchors.none(stage.text::contains) } ||
                anchors.none(reading.summary::contains) || anchors.none(reading.advice::contains))) return false
        if (sensitive(cast.question) && (!Regex("不能|无法|不可|不判断|不预测").containsMatchIn(reading.summary) ||
                assertion(Regex("会痊愈|能治愈|不用就医|能盈利|会涨|会跌|会胜诉|会败诉")))) return false
        return encode(reading).length <= MAX_STORED_CHARS
    }
}
