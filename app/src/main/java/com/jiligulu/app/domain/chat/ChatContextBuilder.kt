package com.jiligulu.app.domain.chat

import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.domain.category.CategoryLabels
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 每轮对话要回灌给模型的动态上下文（时间 + 用户选择范围内的最近账单）。
 *
 * R9 之后对话历史改走 messages 数组（见 AiRepository.recentTurns），本类只剩账本段；
 * R6 定稿「历史只追加、永不回改」，原先基于消息表的摘要函数（toLine / draftSummary /
 * commandSummary 与 ChatContext.recentMessages）随「三态摘要」方案一并删除——
 * QA 已验证它们在全仓无任何生产调用方，是死代码。
 *
 * 时间范围、条数和字符预算只约束聊天预灌，不删除账单，也不限制精确数据库查询。
 */
data class ChatContext(
    val now: String,
    val timeZone: String,
    val recentBills: List<BillLine>,
    val billsNotice: String = ""
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
    const val MAX_BILL_CONTEXT_CHARS = 48_000
    private const val MAX_BILL_LINE_CHARS = 2_000

    /**
     * 消息条数上限，约 30 轮。
     *
     * ⚠️ **刻意不收敛到 12 轮**：用户明确要求「历史对话是刚需，否则他可能无法接着跟我聊」；
     * 而且 append-only 下历史越长，`[system + 历史]` 这个前缀命中得越多，裁切是双重损失。
     * 所以 60 保留为最低窗口；请求允许逐批增长到 139，再一次性裁切。
     */
    const val MAX_MESSAGES = 60
    // User mostly chats continuously; larger batches amortize cold prefix rebuilds.
    // At ¥0.02 cached vs ¥1 uncached, 80 is close to the modeled warm-session minimum
    // while retaining less context than the 100/120-message batch alternatives.
    const val HISTORY_BATCH_SIZE = 80

    /** Keep at least the original window; evict a batch instead of breaking the prefix each turn. */
    fun historyWindowSize(eligibleCount: Int): Int =
        if (eligibleCount <= MAX_MESSAGES) MAX_MESSAGES
        else MAX_MESSAGES + (eligibleCount - MAX_MESSAGES) % HISTORY_BATCH_SIZE

    private val dateFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm")

    fun build(
        bills: List<BillEntity>,
        categories: List<CategoryEntity>,
        requestMillis: Long,
        zone: ZoneId,
        windowDays: Int? = BILL_WINDOW_DAYS.toInt(),
        maxBills: Int = MAX_BILLS
    ): ChatContext {
        require(windowDays == null || windowDays > 0)
        require(maxBills > 0)
        val nowZoned = Instant.ofEpochMilli(requestMillis).atZone(zone)
        // v0.6：`{now}` 粒度降到分钟。秒级时间每轮都在变，会让动态段最后一行的前缀永远错位；
        // 记账也只需要到分钟，带上秒纯属噪声。
        val clock = nowZoned.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val weekday = nowZoned.format(DateTimeFormatter.ofPattern("EEEE", java.util.Locale.CHINA))

        val billCutoff = windowDays?.let { requestMillis - it.toLong() * 86_400_000L }
        val categoryNames = categories.associate { it.id to CategoryLabels.displayName(it.name) }
        val eligibleBills = bills
            .filter { billCutoff == null || it.timestamp >= billCutoff }
            .sortedByDescending { it.timestamp }
        var usedChars = 0
        var textTruncated = false
        val billLines = buildList {
            for (bill in eligibleBills.take(maxBills)) {
                if (bill.detail.length > 800 || bill.note.length > 800 || (categoryNames[bill.categoryId]?.length ?: 0) > 100)
                    textTruncated = true
                val fullLine = formatBill(bill, categoryNames[bill.categoryId], zone)
                val line = if (fullLine.length > MAX_BILL_LINE_CHARS) {
                    textTruncated = true
                    fullLine.take(MAX_BILL_LINE_CHARS - 10) + "…（细则已截取）"
                } else fullLine
                if (usedChars + line.length + 1 > MAX_BILL_CONTEXT_CHARS) {
                    textTruncated = true
                    break
                }
                add(ChatContext.BillLine(line))
                usedChars += line.length + 1
            }
        }
        val rangeLabel = windowDays?.let { "最近${it}天" } ?: "全部时间范围"
        // The DAO already limits its input: always state the cap, even if omitted rows are unknown here.
        val notice = "$rangeLabel；按时间从新到旧，最多带入${maxBills}笔，本次${billLines.size}笔。" +
            if (textTruncated || eligibleBills.size > billLines.size) "明细已按条数或字符预算截取，不代表完整账本；精确查询仍以全账本为准。"
            else "此段为有限聊天上下文，不代表完整账本；精确查询仍以全账本为准。"

        return ChatContext(
            now = "$clock（$weekday）",
            timeZone = zone.id,
            recentBills = billLines,
            billsNotice = notice
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
        fun excerpt(value: String, max: Int) = if (value.length > max) value.take(max) + "…（已截取）" else value
        val category = excerpt(categoryName?.ifBlank { null } ?: "未分类", 100)
        val name = excerpt(bill.detail.ifBlank { category }, 800)
        val amount = "%.2f".format(java.util.Locale.ROOT, bill.amountFen / 100.0)
        val type = if (bill.type == BillType.EXPENSE) "支出" else "收入"
        val note = if (bill.note.isNotBlank()) " · ${excerpt(bill.note, 800)}" else ""
        return "[${bill.id}] $when_ · $category · $name · $amount 元 · $type$note"
    }
}
