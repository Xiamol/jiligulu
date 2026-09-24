package com.jiligulu.app.domain.time

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class ResolvedBillTime(
    val timestamp: Long? = null,
    val needsReview: Boolean = false,
    val hint: String = "未提及时间，确认入账时记录此刻"
)

/** All relative dates are anchored to the request instant and the device's time zone. */
object BillTimeResolver {
    private const val N = "[零〇一二两三四五六七八九十百\\d]+"
    private const val MONEY_NUMBER = "(?:\\d+(?:[.点][零一二三四五六七八九\\d]+)?|[零〇一二两三四五六七八九十百]+(?:点[零一二三四五六七八九]+)?)"
    private val moneyAmount = Regex("(?:[¥￥]\\s*$MONEY_NUMBER|$MONEY_NUMBER(?:块钱|块|元)(?:$N[角毛])?)")
    private val temporal = Regex("今天|今日|昨天|昨日|前天|大前天|明天|后天|昨晚|昨早|今早|今晚|刚才|刚刚|现在|此刻|前几天|前阵子|前段时间|上个?月|本月|这月|去年|前年|今年|月初|月中|月底|周末|上周|本周|这周|周[一二三四五六日天]|星期|礼拜|${N}天前|${N}月${N}[日号]|${N}[日号]|${N}年|${N}月|(?:${N}|几)个?月前|\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}/\\d{1,2}|凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|深夜|${N}[点时]|\\d{1,2}[:：]\\d{2}")

    fun hasTimeExpression(text: String): Boolean = temporal.containsMatchIn(moneyAmount.replace(text, ""))

    fun splitClauses(input: String): List<String> = input.split(
        Regex("[，,；;。\\n、]|然后|以及|另外|(?=今天|今日|昨天|昨日|大前天|(?<!大)前天|明天|后天|昨晚|昨早|今早|今晚)")
    ).filter(String::isNotBlank)

    /** A time can scope later clauses, but never travel backwards from a later bill. */
    fun contextForBill(input: String, detail: String, count: Int): String {
        if (!hasTimeExpression(input)) return ""
        val clauses = splitClauses(input)
        val matching = clauses.indices.filter { detail.isNotBlank() && clauses[it].contains(detail) }
        if (matching.size == 1) {
            val index = matching.single()
            if (hasTimeExpression(clauses[index])) return clauses[index]
            return clauses.take(index).lastOrNull(::hasTimeExpression) ?: ""
        }
        val timedIndices = clauses.indices.filter { hasTimeExpression(clauses[it]) }
        return if (timedIndices.size == 1 && (count == 1 || timedIndices.single() == 0)) {
            clauses[timedIndices.single()]
        } else "时间待确认"
    }

    /** Keep each imported line intact even when the model paraphrases its merchant/detail. */
    internal fun sourceRowForBill(input: String, amountYuan: Double?, type: String?): String? {
        if (amountYuan == null || !amountYuan.isFinite() || amountYuan <= 0) return null
        val rows = input.lines().map { it.trim().replace(Regex("^\\d+[.、)）]\\s*"), "") }.filter(String::isNotBlank)
        if (rows.size < 2) return null
        val amount = java.math.BigDecimal.valueOf(amountYuan).setScale(2, java.math.RoundingMode.HALF_UP)
        val matches = rows.filter { row ->
            val values = Regex("(?<![\\d.])(?:[¥￥]\\s*)?(\\d+(?:\\.\\d{1,2})?)\\s*元").findAll(row)
            val hasAmount = values.any { it.groupValues[1].toBigDecimalOrNull()?.setScale(2) == amount }
            val direction = when (type?.uppercase()) {
                "INCOME" -> row.contains("收入") && !row.contains("支出")
                "EXPENSE" -> row.contains("支出") && !row.contains("收入")
                else -> true
            }
            hasAmount && direction
        }
        return matches.singleOrNull()
    }

    /** A model-supplied time never creates temporal information absent from the user's words. */
    fun expressionForBill(input: String, detail: String, count: Int, modelExpression: String, amountYuan: Double? = null, type: String? = null): String {
        sourceRowForBill(input, amountYuan, type)?.let { row ->
            return row
        }
        if (count == 1 && input.contains("日期待确认")) return input
        val context = contextForBill(input, detail, count)
        if (context == "时间待确认") return context
        if (context.isEmpty()) {
            val clauses = splitClauses(input)
            val matching = clauses.filter { detail.isNotBlank() && it.contains(detail) }
            val own = if (matching.size == 1) matching.single() else if (count == 1) input else ""
            return if (mealTime(own) != null) own else ""
        }
        // Retain the complete clause: a shortened model value such as "中午" can drop "昨天".
        // Original wording is also the only trustworthy fallback when the model invents a date.
        return if (modelExpression.isNotBlank() && !hasTimeExpression(context)) "" else context
    }

    /** Meal names imply a conventional time only when the meal window has clearly passed. */
    private fun mealTime(text: String): Pair<LocalTime, LocalTime>? = when {
        Regex("(?:早餐|早饭)(?!机|券|卡|奶)").containsMatchIn(text) -> LocalTime.of(8, 0) to LocalTime.of(10, 30)
        Regex("(?:午餐|午饭)(?!肉|券|卡)").containsMatchIn(text) -> LocalTime.NOON to LocalTime.of(14, 30)
        Regex("(?:晚餐|晚饭)(?!券|卡)").containsMatchIn(text) -> LocalTime.of(18, 0) to LocalTime.of(21, 0)
        else -> null
    }

    fun resolve(
        expression: String,
        occurredAt: String = "",
        requestMillis: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): ResolvedBillTime {
        val text = moneyAmount.replace(expression.trim().replace(" ", ""), "")
        if (text.isEmpty()) return ResolvedBillTime()
        if (text in listOf("刚才", "刚刚", "现在", "此刻")) {
            return ResolvedBillTime(requestMillis, hint = "按发送消息时的时间记录")
        }
        val now = Instant.ofEpochMilli(requestMillis).atZone(zone)
        Regex("(?:截图参考时间|日期待确认)(\\d{1,2})[:：](\\d{2})").find(text)?.let { clock ->
            return try {
                val time = LocalTime.of(clock.groupValues[1].toInt(), clock.groupValues[2].toInt())
                ResolvedBillTime(now.toLocalDate().atTime(time).atZone(zone).toInstant().toEpochMilli(),
                    needsReview = true, hint = if (text.contains("截图参考时间")) "仅识别到截图参考时间 ${time}，日期暂放今天；请核对日期，非支付完成时间"
                        else "已识别 ${time}，日期暂放今天；图片未显示日期，请核对")
            } catch (_: Exception) { ResolvedBillTime(needsReview = true, hint = "截图参考时间无效，请重新选择") }
        }
        return try {
            val date = resolveDate(text, now.toLocalDate())
            val time = resolveTime(text)
            // Vague periods must remain explicit in the editor, never silently become today.
            val vague = Regex("前几天|前阵子|前段时间|周末|时间待确认|日期待确认|日期不明|月初|月中|月底").containsMatchIn(text)
            if (vague) return ResolvedBillTime(needsReview = true, hint = "“$expression”还不够具体，请选择账单日期和时间")
            if (date == null && Regex("上个?月|去年|前年|今年|${N}年|${N}月|(?:${N}|几)个?月前|上周|本周|这周|星期|礼拜").containsMatchIn(text)) {
                return ResolvedBillTime(needsReview = true, hint = "还需要具体日期，请选择账单日期和时间")
            }
            val meal = mealTime(text)
            if (time == null && meal != null) {
                val explicitPresent = Regex("刚才|刚刚|现在|此刻|刚吃|正在吃|才吃").containsMatchIn(text)
                if ((date == null || date == now.toLocalDate()) && explicitPresent) {
                    return ResolvedBillTime(requestMillis, hint = "按你说的当前时间记录")
                }
                val earlierMeal = !now.toLocalTime().isBefore(meal.second)
                val explicitBackfill = Regex("补记|补录").containsMatchIn(text) && !now.toLocalTime().isBefore(meal.first)
                if (date != null && date != now.toLocalDate() || earlierMeal || explicitBackfill) {
                    val value = (date ?: now.toLocalDate()).atTime(meal.first).atZone(zone).toInstant().toEpochMilli()
                    return ResolvedBillTime(value, hint = "按餐次暂记 ${meal.first}，可以修改")
                }
                return ResolvedBillTime()
            }
            if (date != null || time != null) {
                val actualTime = time ?: LocalTime.NOON
                val value = (date ?: now.toLocalDate()).atTime(actualTime).atZone(zone).toInstant().toEpochMilli()
                val hint = when {
                    time == null -> "已识别日期；未说几点，暂按 12:00，可修改"
                    !Regex("${N}[点时]|\\d{1,2}[:：]\\d{2}").containsMatchIn(text) -> "按${actualTime}记录，可修改"
                    else -> "已按你说的时间整理"
                }
                ResolvedBillTime(value, hint = hint)
            } else if (Regex("刚才|刚刚|现在|此刻").containsMatchIn(text)) {
                ResolvedBillTime(requestMillis, hint = "按发送消息时的时间记录")
            } else if (hasTimeExpression(text) && occurredAt.isNotBlank()) {
                val local = LocalDateTime.parse(occurredAt.trim().replace(' ', 'T'))
                ResolvedBillTime(local.atZone(zone).toInstant().toEpochMilli(), hint = "AI 识别的时间，请确认")
            } else {
                ResolvedBillTime(needsReview = true, hint = "时间还没识别清楚，请选择账单日期和时间")
            }
        } catch (_: Exception) {
            ResolvedBillTime(needsReview = true, hint = "日期或时间无效，请重新选择")
        }
    }

    private fun resolveDate(text: String, today: LocalDate): LocalDate? {
        Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(text)?.let {
            return LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        Regex("(?:(\\d{4})年)?($N)月($N)[日号]").find(text)?.let {
            val implicitYear = today.year - when { text.contains("前年") -> 2; text.contains("去年") -> 1; else -> 0 }
            return LocalDate.of(it.groupValues[1].toIntOrNull() ?: implicitYear, number(it.groupValues[2]), number(it.groupValues[3]))
        }
        Regex("(?:(\\d{4})/)?(\\d{1,2})/(\\d{1,2})").find(text)?.let {
            return LocalDate.of(it.groupValues[1].toIntOrNull() ?: today.year, it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        Regex("(上个?月|本月|这月)($N)[日号]").find(text)?.let {
            val month = if (it.groupValues[1].startsWith("上")) today.minusMonths(1) else today
            return month.withDayOfMonth(number(it.groupValues[2]))
        }
        Regex("($N)天前").find(text)?.let { return today.minusDays(number(it.groupValues[1]).toLong()) }
        Regex("(上周|上个?星期|上礼拜|本周|这周|这星期|周|星期|礼拜)([一二三四五六日天])").find(text)?.let {
            val day = "一二三四五六日".indexOf(it.groupValues[2].replace("天", "日")) + 1
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val target = monday.plusDays(day.toLong() - 1)
            return when (it.groupValues[1]) {
                "上周", "上星期", "上个星期", "上礼拜" -> target.minusWeeks(1)
                "周", "星期", "礼拜" -> if (target.isAfter(today)) target.minusWeeks(1) else target
                else -> target
            }
        }
        return when {
            text.contains("大前天") -> today.minusDays(3)
            text.contains("前天") -> today.minusDays(2)
            Regex("昨天|昨日|昨晚|昨早").containsMatchIn(text) -> today.minusDays(1)
            text.contains("后天") -> today.plusDays(2)
            text.contains("明天") -> today.plusDays(1)
            Regex("今天|今日|今早|今晚").containsMatchIn(text) -> today
            else -> Regex("($N)[日号]").find(text)?.let { today.withDayOfMonth(number(it.groupValues[1])) }
        }
    }

    private fun resolveTime(text: String): LocalTime? {
        val afternoon = Regex("下午|傍晚|晚上|今晚|昨晚|夜里").containsMatchIn(text)
        val early = Regex("凌晨|深夜").containsMatchIn(text)
        val clock = Regex("(\\d{1,2})[:：](\\d{2})").find(text)
        val chinese = Regex("($N)[点时](?:(半)|($N)分?)?").find(text)
        if (clock != null || chinese != null) {
            var hour = clock?.groupValues?.get(1)?.toInt() ?: number(chinese!!.groupValues[1])
            val minute = clock?.groupValues?.get(2)?.toInt() ?: when {
                chinese!!.groupValues[2].isNotBlank() -> 30
                chinese.groupValues[3].isNotBlank() -> number(chinese.groupValues[3])
                else -> 0
            }
            if (afternoon && hour in 1..11) hour += 12
            if (text.contains("中午") && hour in 1..10) hour += 12
            if (early && hour == 12) hour = 0
            return LocalTime.of(hour, minute)
        }
        val hour = when {
            Regex("凌晨|深夜").containsMatchIn(text) -> 0
            Regex("清晨|早上|早晨|今早|昨早").containsMatchIn(text) -> 8
            text.contains("上午") -> 9
            text.contains("中午") -> 12
            text.contains("下午") -> 15
            text.contains("傍晚") -> 18
            Regex("晚上|今晚|昨晚|夜里").containsMatchIn(text) -> 20
            else -> return null
        }
        return LocalTime.of(hour, 0)
    }

    internal fun number(value: String): Int {
        value.toIntOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var total = 0
        var current = 0
        value.forEach { c ->
            when (c) {
                '十', '百' -> { total += (if (current == 0) 1 else current) * if (c == '十') 10 else 100; current = 0 }
                else -> current = digits[c] ?: throw IllegalArgumentException("Invalid number")
            }
        }
        return total + current
    }
}
