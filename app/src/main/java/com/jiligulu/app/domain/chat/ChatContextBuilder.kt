package com.jiligulu.app.domain.chat

import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.domain.category.CategoryLabels
import com.jiligulu.app.ui.chat.CommandCardCodec
import com.jiligulu.app.ui.chat.DraftHistoryCodec
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 每轮对话要回灌给模型的既有信息。
 *
 * coder 的诉求是让阿噜像「生活搭子」一样说话——不是只在记账时才读账本，
 * 而是每轮都能看到你最近吃了什么、喝了什么，从而组织语言
 * （「今天还没吃早饭？」「一天两顿面要补点蛋白质」）。
 *
 * 三条边界（coder 拍板）：
 * - 对话：**24 小时内**全部消息，按时间窗回滚，不用「最近 N 轮」
 * - 账单：**最近 3 天**，含分类/细则/金额/时间
 * - 不做 Function Calling：预灌即可，一趟往返，省时省钱
 */
data class ChatContext(
    val now: String,
    val timeZone: String,
    val recentMessages: List<MessageLine>,
    val recentBills: List<BillLine>
) {
    data class MessageLine(val role: String, val text: String)
    data class BillLine(val label: String)
}

object ChatContextBuilder {

    /** 对话时间窗：24 小时。 */
    const val MESSAGE_WINDOW_HOURS = 24L

    /** 账单时间窗：3 天。 */
    const val BILL_WINDOW_DAYS = 3L

    /** 再保险：账单条数上限，避免某天记了 200 笔把 prompt 撑爆。 */
    const val MAX_BILLS = 60

    /** 消息条数上限，同理。 */
    const val MAX_MESSAGES = 60

    private val dateFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm")
    private val weekdayFormat = DateTimeFormatter.ofPattern("EEEE", java.util.Locale.CHINA)

    fun build(
        messages: List<ChatMessageEntity>,
        bills: List<BillEntity>,
        categories: List<CategoryEntity>,
        requestMillis: Long,
        zone: ZoneId
    ): ChatContext {
        val nowZoned = Instant.ofEpochMilli(requestMillis).atZone(zone)
        val now = nowZoned.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val weekday = nowZoned.format(weekdayFormat)

        val messageCutoff = requestMillis - MESSAGE_WINDOW_HOURS * 3_600_000L
        val lines = messages
            .filter { it.createdAt >= messageCutoff }
            .takeLast(MAX_MESSAGES)
            .mapNotNull { it.toLine() }

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
            now = "$now（$weekday）",
            timeZone = zone.id,
            recentMessages = lines,
            recentBills = billLines
        )
    }

    /**
     * 把一条消息压成上下文行。
     *
     * 草稿卡只给「用户原话 + 结果」，不给可编辑字段——模型不需要知道
     * 用户后来把金额从 9 改成 12，它只需要知道这笔账最终有没有落库。
     */
    private fun ChatMessageEntity.toLine(): ChatContext.MessageLine? = when (kind) {
        "USER" -> content.takeIf { it.isNotBlank() }?.let { ChatContext.MessageLine("用户", it) }

        "ASSISTANT" -> {
            val text = when (status) {
                "PENDING" -> return null
                "INTERRUPTED" -> "（上次没整理成功）"
                else -> content
            }
            text.takeIf { it.isNotBlank() }?.let { ChatContext.MessageLine("叽里咕噜", it) }
        }

        "DRAFT" -> {
            val summary = draftSummary() ?: rawInput
            summary.takeIf { it.isNotBlank() }?.let { ChatContext.MessageLine("（账单草稿）", it) }
        }

        // 改账/删账卡：告诉模型上轮它提过这么一次变更，用户有没有确认。
        "COMMAND" -> commandSummary()?.let { ChatContext.MessageLine("（改账/删账）", it) }

        // 挂起账不进对话历史——它已经在提示词的「待补充的账」里了，重复喂会干扰判断。
        "PENDING_DRAFT" -> null

        else -> null
    }

    /** 指令卡摘要：「提议修改 2 笔账单（用户还没确认）」。 */
    private fun ChatMessageEntity.commandSummary(): String? = runCatching {
        val payload = CommandCardCodec.decode(draftPayload) ?: return@runCatching null
        if (payload.items.isEmpty()) return@runCatching null
        val verb = if (payload.kind.equals("DELETE", true)) "删除" else "修改"
        val state = if (payload.alreadyApplied >= payload.items.count { it.checked }) "用户已确认" else "用户还没确认"
        "提议$verb ${payload.items.size} 笔账单（$state）"
    }.getOrNull()

    /** 草稿卡摘要：「牛肉面 12 元（吃 · 已入账）」。解析失败就退回原话。 */
    private fun ChatMessageEntity.draftSummary(): String? = runCatching {
        val drafts = DraftHistoryCodec.decode(draftPayload)
        if (drafts.isEmpty()) return@runCatching null
        val state = when (status) {
            "CONFIRMED" -> "已入账"
            "DISMISSED" -> "用户取消了"
            else -> "还没确认"
        }
        drafts.take(4).joinToString("、") { draft ->
            val amount = draft.amountText.ifBlank { "?" }
            val name = draft.detail.ifBlank { draft.categoryName }
            "$name $amount 元"
        } + "（$state）"
    }.getOrNull()

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
