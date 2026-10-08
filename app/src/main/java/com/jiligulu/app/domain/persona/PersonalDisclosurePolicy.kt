package com.jiligulu.app.domain.persona

import java.time.MonthDay

/** Assistant text supplies a question slot, never a value or personal evidence. */
data class MemoryConversationTurn(val id: Long, val role: String, val text: String, val sentAt: Long)
data class PersonalDisclosure(val facts: List<CompanionFact>, val personalOnly: Boolean,
    val billInput: String, val rejectedAmount: String? = null)

object PersonalDisclosurePolicy {
    private enum class Slot { AGE, GENDER, BIRTHDAY, SCHOOL, GRADE, OCCUPATION, STUDY }
    private const val QUESTION_WINDOW = 30 * 60_000L
    private val numbers = "[0-9零〇一二三四五六七八九十百两]{1,5}"
    private val grades = "(?:大[一二三四五]|研[一二三]|博[一二三四五]|高[一二三]|初[一二三]|[一二三四五六七八九]年级)"
    private val clausePattern = Regex("[^，,。；;！!？?\\n]+[？?]?")
    private val standaloneGender = Regex("^(男生|女生|男性|女性|男孩|女孩|男孩子|女孩子)[哦呀啦啊呢]?$", RegexOption.IGNORE_CASE)
    private val ageStatement = Regex("^(?:(?:我(?:今年|现在)?(?:的年龄(?:是|为))?|年龄(?:是|为)?|我说的(?:$numbers)?(?:是|指的是)?)\\s*)?($numbers)\\s*岁[哦呀啦啊呢了]?$", RegexOption.IGNORE_CASE)
    private val birthday = Regex("^(?:(?:我(?:的)?生日|生日)(?:是|在|为)?\\s*)?(?:[0-9]{4}年)?($numbers)月\\s*($numbers)(?:日|号)?$")
    private val correction = Regex("(?:没有|不是)\\s*([0-9]{1,3})(?:元|块)?(?:的)?(?:账单|金额)|我说的\\s*([0-9]{1,3})(?:是|指的是).*岁")

    fun analyze(input: String, now: Long, previous: List<MemoryConversationTurn> = emptyList()): PersonalDisclosure {
        if (input.length > 10_000 || Regex("^\\s*【(?:图片|截图|账单识别|OCR)").containsMatchIn(input))
            return PersonalDisclosure(emptyList(), false, input)
        val slots = replySlots(previous, now)
        val strict = CompanionMemoryPolicy.strictExplicitFacts(input, now).toMutableList()
        val extra = mutableListOf<CompanionFact>()
        val personalClauses = mutableSetOf<IntRange>()
        var personalAnswer = false
        var rejected = correction.find(input)?.let { match -> match.groupValues.drop(1).firstOrNull { it.isNotBlank() } }
        for (clause in clausePattern.findAll(input)) {
            val evidence = clause.value.trim().replace(Regex("^(?:请)?(?:记住|记一下|记得|记好)[：:\\s]*"), "").trim()
            if (!safeClaim(input, clause.range.first, evidence)) continue
            val bare = input.trim().trimEnd('。', '！', '!') == evidence
            val startsDisclosure = input.substring(0, clause.range.first).isBlank()
            val declaredEarlier = personalClauses.isNotEmpty()
            fun add(kind: String, value: String) {
                if (CompanionMemoryPolicy.validValue(value)) {
                    extra += CompanionFact(CompanionMemoryPolicy.id(kind, value), kind, value, evidence, now)
                    personalClauses += clause.range
                    personalAnswer = true
                }
            }
            val age = ageStatement.matchEntire(evidence)?.groupValues?.get(1)?.let(::number)
                ?.takeIf { evidence.startsWith("我") || evidence.startsWith("年龄") || startsDisclosure || declaredEarlier || Slot.AGE in slots }
            if (age != null) {
                personalAnswer = true; personalClauses += clause.range
                if (age in 1..120) add("age", "${age}岁")
                rejected = rejected ?: age.toString()
            } else if (bare && Slot.AGE in slots && evidence.matches(Regex("$numbers(?:岁)?"))) {
                personalAnswer = true; personalClauses += clause.range
                val years = number(evidence.removeSuffix("岁"))
                if (years != null && years in 1..120) add("age", "${years}岁")
                rejected = rejected ?: years?.toString()
            }
            val gender = standaloneGender.matchEntire(evidence)?.groupValues?.get(1)
                ?.takeIf { bare || declaredEarlier || Slot.GENDER in slots }
                ?: evidence.takeIf { bare && Slot.GENDER in slots && it in setOf("男", "女", "male", "female") }
            if (gender != null) add("gender", if (gender in setOf("男生", "男性", "男孩", "男孩子", "男", "male")) "男" else "女")
            val birth = birthday.matchEntire(evidence)
            if (birth != null && (evidence.startsWith("我") || evidence.startsWith("生日") && (startsDisclosure || declaredEarlier) || bare && Slot.BIRTHDAY in slots)) {
                personalAnswer = true; personalClauses += clause.range
                val month = number(birth.groupValues[1]); val day = number(birth.groupValues[2])
                if (month != null && day != null && runCatching { MonthDay.of(month, day) }.isSuccess) add("birthday", "${month}月${day}日")
            }
            val school = Regex("^我(?:是|在|就读于|就读|的学校是)\\s*([^，,。\\s]{2,60}?(?:大学|学院|学校|中学|小学|高中)(?:[^，,。\\s]{0,20}?校区)?)(?=读|上|念|学习|就读|的学生|学生|$)").find(evidence)?.groupValues?.get(1)
                ?: evidence.takeIf { bare && Slot.SCHOOL in slots && it.length in 2..60 && it.matches(Regex("[^，,。？?]+(?:大学|学院|学校|中学|小学|高中)(?:[^，,。？?]{0,20}校区)?")) }
            school?.let { add("school", it) }
            val grade = Regex("^我的年级(?:是|为)?\\s*($grades)$").matchEntire(evidence)?.groupValues?.get(1)
                ?: Regex("^(?:我(?:现在|目前|今年)?(?:是|读|上|在读)?\\s*)?($grades)(?:的学生|学生|在读)?$").matchEntire(evidence)?.groupValues?.get(1)
                ?.takeIf { evidence.startsWith("我") || bare && (Slot.GRADE in slots || Slot.STUDY in slots) }
                ?: Regex("^我.*(?:读|上|念)\\s*($grades)(?:学生|在读)?$").find(evidence)?.groupValues?.get(1)
            grade?.let { add("grade", it) }
            if (bare && Slot.OCCUPATION in slots && evidence.matches(Regex("[\\p{L}]{2,30}")) &&
                evidence !in setOf("不知道", "不告诉你", "还没想好", "没有", "不是", "嗯嗯", "好的")) add("occupation", evidence)
            if (bare && Slot.STUDY in slots && evidence.matches(Regex("(?:大学|高中|初中|小学|本科|硕士|博士|大专)(?:生|学生|在读)?"))) add("study", evidence)
            if (strict.any { it.evidence == evidence }) personalClauses += clause.range
        }
        // A school/year declaration gets its own slot; retain existing study facts in storage.
        strict.removeAll { it.kind == "study" && extra.any { new -> new.kind in setOf("school", "grade") && it.value.contains(new.value) } }
        val facts = (strict + extra).distinctBy { it.id }.take(CompanionMemoryPolicy.MAX_UPDATES_PER_TURN)
        val remaining = clausePattern.findAll(input).filter { it.range !in personalClauses }
            .map { it.value.trim() }.filterNot { it.matches(Regex("^(?:没有|不是)\\s*[0-9]{1,3}(?:元|块)?(?:的)?(?:账单|金额)$")) }
            .joinToString("，")
        val bill = hasBillIntent(remaining)
        val personalOnly = (facts.isNotEmpty() || personalAnswer) && !bill
        return PersonalDisclosure(facts, personalOnly, if (personalClauses.isEmpty()) input else remaining,
            rejected)
    }

    /** Separate windows have separate question contexts; callers never bridge a missing history gap. */
    fun historyFacts(turns: List<MemoryConversationTurn>): List<CompanionFact> {
        val prior = ArrayList<MemoryConversationTurn>()
        var facts = emptyList<CompanionFact>()
        for (turn in turns.sortedBy { it.id }) {
            if (turn.role == "USER") facts = CompanionMemoryPolicy.merge(facts, analyze(turn.text, turn.sentAt, prior.takeLast(8)).facts)
            prior += turn
        }
        return facts
    }

    private fun replySlots(previous: List<MemoryConversationTurn>, now: Long): Set<Slot> {
        var slots = emptySet<Slot>()
        var at = 0L
        var fromUserQuestion = false
        for (turn in previous.takeLast(8)) {
            if (turn.role == "USER") {
                slots = questionSlots(turn.text, ownQuestion = true)
                at = turn.sentAt; fromUserQuestion = slots.isNotEmpty()
            } else if (turn.role == "ASSISTANT") {
                val asked = questionSlots(turn.text, ownQuestion = false)
                if (asked.isNotEmpty()) { slots = asked; at = turn.sentAt; fromUserQuestion = false }
                else if (!fromUserQuestion) slots = emptySet()
            }
        }
        return slots.takeIf { now - at in 0..QUESTION_WINDOW } ?: emptySet()
    }

    private fun questionSlots(text: String, ownQuestion: Boolean): Set<Slot> {
        if (text.length > 10_000 || !Regex("[？?]|多少|几岁|多大|哪|什么|还是|大几|研几|几年级").containsMatchIn(text)) return emptySet()
        val subject = if (ownQuestion) "我" else "你"
        if (Regex("$subject(?:妈妈|爸爸|弟弟|妹妹|哥哥|姐姐|朋友|儿子|女儿)").containsMatchIn(text)) return emptySet()
        val found = mutableSetOf<Slot>()
        if (Regex("$subject(?:今年|现在|的年龄(?:是|有)?)?\\s*(?:多少岁|几岁|多大)|${subject}的年龄").containsMatchIn(text)) found += Slot.AGE
        if (Regex("$subject(?:是|的性别)?[^。！？?]{0,12}(?:男生|女生|男孩|女孩|性别)").containsMatchIn(text)) found += Slot.GENDER
        if (Regex("${subject}(?:的)?生日").containsMatchIn(text)) found += Slot.BIRTHDAY
        if (Regex("$subject[^。！？?]{0,20}(?:哪所|哪个|什么)(?:学校|大学|学院)|$subject[^。！？?]{0,20}(?:学校|大学|学院)[^。！？?]{0,8}(?:哪|什么)|$subject[^。！？?]{0,8}在哪[^。！？?]{0,8}(?:上学|读书|读|就读)").containsMatchIn(text)) found += Slot.SCHOOL
        if (Regex("$subject[^。！？?]{0,20}(?:几年级|哪个年级|什么年级|年级|大几|研几|博几|高几|初几)").containsMatchIn(text)) found += Slot.GRADE
        if (Regex("$subject[^。！？?]{0,20}(?:职业|工作|从事)").containsMatchIn(text)) found += Slot.OCCUPATION
        if (Regex("$subject[^。！？?]{0,20}(?:在读|上学|学生|学历)").containsMatchIn(text)) found += Slot.STUDY
        return found
    }

    private fun safeClaim(input: String, offset: Int, evidence: String): Boolean {
        if (evidence.length !in 1..160 || evidence.any(Char::isISOControl) ||
            Regex("[？?]|吗|是不是|是否|假如|如果|假设|例如|比如|扮演|角色|不是.*岁").containsMatchIn(evidence)) return false
        if (Regex("^(?:我(?:的)?(?:妈妈|爸爸|弟弟|妹妹|哥哥|姐姐|朋友|同学|同事|对象|男朋友|女朋友|儿子|女儿)|(?:妈妈|爸爸|他|她|朋友|哥哥|姐姐|弟弟|妹妹|儿子|女儿))").containsMatchIn(evidence)) return false
        if (Regex("^(?:不是|不确定|不清楚|不知道|可能|也许|猜|应该)").containsMatchIn(evidence)) return false
        val before = input.substring(0, offset).takeLast(400)
        val topicPrefix = before.substringAfterLast('。').substringAfterLast('！').substringAfterLast('？').substringAfterLast('\n')
        val otherOwner = Regex("([\\p{L}]{1,20})的(?:年龄|性别|生日|学校|年级|职业)").findAll(topicPrefix)
            .any { it.groupValues[1] !in setOf("我", "本人") }
        if (!evidence.startsWith("我") && (otherOwner || Regex("(?:我(?:的)?|给|送|替|帮)?(?:妈妈|爸爸|弟弟|妹妹|哥哥|姐姐|朋友|同事|同学|对象|男朋友|女朋友|儿子|女儿)").containsMatchIn(topicPrefix))) return false
        if (Regex("截图|图片|OCR|识别出的|转述|聊天记录|角色扮演|假设|如果|假如|(?:妈妈|爸爸|他|她|朋友)(?:说|告诉|写)").containsMatchIn(before)) return false
        if (listOf('“' to '”', '「' to '」', '『' to '』').any { (open, close) -> before.lastIndexOf(open) > before.lastIndexOf(close) } ||
            before.count { it == '"' } % 2 != 0) return false
        return true
    }

    private fun hasBillIntent(text: String): Boolean = Regex("[¥￥]\\s*\\d|\\d+(?:\\.\\d+)?\\s*(?:元|块钱?|毛|角)|(?:买|花|付|消费|支出|收入|工资|奖金|报销|退款|记账|午饭|晚饭|早餐|吃饭|奶茶|饮料|打车|购物)[^。！？?]*\\d|(?:查|查询|找|统计|合计|整理|恢复|删|改|清空)[^。！？?]*(?:账|消费|支出|收入|分类|金额|回收站)").containsMatchIn(text)

    private fun number(raw: String): Int? {
        raw.toIntOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (raw.none { it == '十' || it == '百' }) return raw.map { digits[it] ?: return null }.joinToString("").toIntOrNull()
        var result = 0; var current = 0
        for (char in raw) when (char) {
            '十' -> { result += (if (current == 0) 1 else current) * 10; current = 0 }
            '百' -> { result += (if (current == 0) 1 else current) * 100; current = 0 }
            else -> current = digits[char] ?: return null
        }
        return result + current
    }
}
