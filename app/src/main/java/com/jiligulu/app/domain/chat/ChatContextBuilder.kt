package com.jiligulu.app.domain.chat

import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.domain.category.CategoryLabels
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 每轮对话要回灌给模型的动态上下文（时间 + 最近三天账本摘要）。
 *
 * R9 之后对话历史改走 messages 数组（见 AiRepository.recentTurns），本类只剩账本段；
 * R6 定稿「历史只追加、永不回改」，原先基于消息表的摘要函数（toLine / draftSummary /
 * commandSummary 与 ChatContext.recentMessages）随「三态摘要」方案一并删除——
 * QA 已验证它们在全仓无任何生产调用方，是死代码。
 *
 * 两条边界（coder 拍板）：
 * - 账单：**最近 3 天**，含分类/细则/金额/时间
 * - 不做 Function Calling：预灌即可，一趟往返，省时省钱
 */
data class ChatContext(
    val now: String,
    val timeZone: String,
    val recentBills: List<BillLine>
) {
    data class BillLine(val label: String)
}

object ChatContextBuilder {

    /** 对话时间窗：24 小时。账本段虽已不读消息表，这个时间窗仍被 recentTurns 引用。 */
    const val MESSAGE_WINDOW_HOURS = 24L

    /** 账单时间窗：3 天。 */
    const val BILL_WINDOW_DAYS = 3L

    /**
     * 账本条数上限。v0.6 从 60 收敛到 30：账本段落在动态区、每轮都是实打实的缓存 miss，
     * 裁掉一半确实省 token。历史消息不受此影响（见 [MAX_MESSAGES]）。
     */
    const val MAX_BILLS = 30

    /**
     * 消息条数上限，约 30 轮。
     *
     * ⚠️ **刻意不收敛到 12 轮**：用户明确要求「历史对话是刚需，否则他可能无法接着跟我聊」；
     * 而且 append-only 下历史越长，`[system + 历史]` 这个前缀命中得越多，裁切是双重损失。
     * 所以这里保持 60。
     */
    const val MAX_MESSAGES = 60

    private val dateFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm")

    fun build(
        bills: List<BillEntity>,
        categories: List<CategoryEntity>,
        requestMillis: Long,
        zone: ZoneId
    ): ChatContext {
        val nowZoned = Instant.ofEpochMilli(requestMillis).atZone(zone)
        // v0.6：`{now}` 粒度降到分钟。秒级时间每轮都在变，会让动态段最后一行的前缀永远错位；
        // 记账也只需要到分钟，带上秒纯属噪声。
        val clock = nowZoned.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val weekday = nowZoned.format(DateTimeFormatter.ofPattern("EEEE", java.util.Locale.CHINA))

        val billCutoff = requestMillis - BILL_WINDOW_DAYS * 86_400_000L
        val categoryNames = categories.associate { it.id to CategoryLabels.displayName(it.name) }
        val billLines = bills
            .filter { it.timestamp >= billCutoff }
            .sortedByDescending { it.timestamp }
            .take(MAX_BILLS)
            .map { bill ->
                ChatContext.BillLine(formatBill(bill, categoryNames[bill.categoryId], zone))
            }

        return ChatContext(
            now = "$clock（$weekday）",
            timeZone = zone.id,
            recentBills = billLines
        )
    }

    /**
     * 「[12] 9月21日 12:30 · eating · 牛肉面 · 12.00 元 · 支出」
     *
     * 开头的 `[id]` 是给模型用的——改账/删账时它要回填 target_id。
     * 没有这个 id，模型就只能靠描述猜，那就必然猜错。
     */
    private fun formatBill(bill: BillEntity, categoryName: String?, zone: ZoneId): String {
        val when_ = Instant.ofEpochMilli(bill.timestamp).atZone(zone).format(dateFormat)
        val category = categoryName?.ifBlank { null } ?: "未分类"
        val name = bill.detail.ifBlank { category }
        val amount = "%.2f".format(bill.amountFen / 100.0)
        val type = if (bill.type == BillType.EXPENSE) "支出" else "收入"
        val note = if (bill.note.isNotBlank()) " · ${bill.note}" else ""
        return "[${bill.id}] $when_ · $category · $name · $amount 元 · $type$note"
    }
}
