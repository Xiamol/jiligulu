package com.jiligulu.app.core.ai

import com.jiligulu.app.domain.time.BillTimeResolver

/** Conservative offline extraction: dates and clock numbers never count as money. */
object LocalBillParser {
    private const val NUMBER = "(?:\\d+(?:[.点][零一二三四五六七八九\\d]{1,2})?|[零〇一二两三四五六七八九十百]+(?:点[零一二三四五六七八九]+)?)"
    private val money = Regex("(?<![\\d.点负-])(?:[¥￥]\\s*($NUMBER)|($NUMBER)\\s*(?:块钱|块|元))(?:(($NUMBER)(?:角|毛))|([零一二两三四五六七八九\\d])(?=\\s*(?:钱|$|[，,；;。])))?")

    fun parse(input: String): List<AiBillDraft> {
        val clauses = BillTimeResolver.splitClauses(input)
        if (clauses.any { money.findAll(it).count() > 1 }) return emptyList()
        var inheritedTime = ""
        return clauses.flatMap { clause ->
            val text = clause.trim()
            if (BillTimeResolver.hasTimeExpression(text)) inheritedTime = text
            val matches = money.findAll(text).toList()
            if (matches.isEmpty()) {
                // Accept the common "早餐 12" form only when no date/time or other number exists.
                val plain = Regex("^([^\\d]+?)\\s*(\\d+(?:\\.\\d{1,2})?)$").matchEntire(text)
                if (plain != null && !BillTimeResolver.hasTimeExpression(text)) {
                    listOf(draft(text, plain.groupValues[2].toDouble(), plain.groupValues[1], inheritedTime))
                } else emptyList()
            } else if (matches.size == 1) {
                val match = matches.single()
                val amount = parseNumber(match.groupValues[1].ifBlank { match.groupValues[2] }) +
                    (match.groupValues[4].ifBlank { match.groupValues[5] }.takeIf(String::isNotBlank)?.let(::parseNumber) ?: 0.0) / 10
                listOf(draft(text, amount, text.removeRange(match.range).trim(' ', '花', '了', '共', '付', '费'), inheritedTime))
            } else {
                // Without a reliable boundary, amounts could include totals, discounts or change.
                emptyList()
            }
        }
    }

    private fun draft(text: String, amount: Double, detail: String, time: String) = AiBillDraft(
        amountYuan = amount,
        type = if (Regex("收入|工资|转入|收到|报销").containsMatchIn(text)) "INCOME" else "EXPENSE",
        detail = detail.ifBlank { "待补充名称" },
        timeExpression = if (BillTimeResolver.hasTimeExpression(text)) text else time
    )

    private fun parseNumber(text: String): Double {
        text.toDoubleOrNull()?.let { return it }
        val parts = text.split('点', limit = 2)
        val whole = BillTimeResolver.number(parts[0]).toDouble()
        return if (parts.size == 1) whole else whole + ("0." + parts[1].map { BillTimeResolver.number(it.toString()) }.joinToString("")).toDouble()
    }
}
