package com.jiligulu.app.domain.chat

import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.domain.category.CategoryLabels
import com.jiligulu.app.ui.chat.CommandCardPayload
import com.jiligulu.app.ui.chat.CommandCardCodec
import com.jiligulu.app.ui.chat.PendingDraft
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 提示词的「填空」逻辑集中在这里。
 *
 * 原来这些都在 [com.jiligulu.app.data.repository.AiRepository] 里，
 * 拆出来是为了能单独测：提示词里少一个 `{bills}` 占位符、或者候选账单忘了带 id，
 * 模型就会静默地瞎猜，而单元测试能立刻抓到。
 */
class PromptRenderer(
    private val template: String,
    private val categories: List<CategoryEntity>,
    private val context: ChatContext,
    private val nickname: String,
    private val suffix: String,
    private val candidates: CandidateBills,
    private val pending: PendingDraft?,
    private val zone: ZoneId
) {
    /** 账本候选：按「模型可能要改/删的范围」分组，每组各自截断。 */
    data class CandidateBills(
        val latest: List<BillEntity> = emptyList(),
        val today: List<BillEntity> = emptyList(),
        val yesterday: List<BillEntity> = emptyList(),
        val beforeYesterday: List<BillEntity> = emptyList(),
        val thisWeek: List<BillEntity> = emptyList()
    ) {
        /** 本次实际喂给模型的账单总条数，用于观测和测试。 */
        val total: Int
            get() = latest.size + today.size + yesterday.size + beforeYesterday.size + thisWeek.size
    }

    fun render(input: String): String = template
        .replace("{nickname}", nickname)
        .replace("{suffix}", suffix)
        .replace("{categories}", renderCategories())
        .replace("{now}", context.now)
        .replace("{timezone}", zone.id)
        .replace("{history}", renderHistory())
        .replace("{bills}", renderBills())
        .replace("{pending}", renderPending())
        .replace("{candidates}", renderCandidates())
        .replace("{input}", input)

    private fun renderCategories(): String = categories.joinToString("\n") { category ->
        "- ${CategoryLabels.displayName(category.name)}（关键词：${category.keywords.ifBlank { "无" }}）"
    }

    private fun renderHistory(): String =
        if (context.recentMessages.isEmpty()) "（这是你们第一次说话）"
        else context.recentMessages.joinToString("\n") { "${it.role}：${it.text}" }

    private fun renderBills(): String =
        if (context.recentBills.isEmpty()) "（最近三天还没有记过账）"
        else context.recentBills.joinToString("\n") { it.label }

    /**
     * 挂起中的账。**这是「5 → 面条」能串起来的关键**：
     * 上一轮用户只报了金额，这里明确告诉模型「有一条 5 元的账还欠个名目」，
     * 它才知道这轮该合并成一条完整的 add 而不是再追一次。
     */
    private fun renderPending(): String =
        if (pending == null) "（没有待补充的账）"
        else "用户之前说了「${pending.rawInput}」，记着一笔 ${pending.amountText} 元的${typeName(pending.type)}，" +
            "但还没说是什么。如果这轮补上了名目，请合并成一条完整的 add，不要再追问。"

    /**
     * 候选账单。
     *
     * 「最近三天」对改账够用，但对「星期一那顿面」就不够——所以额外带一周汇总。
     * 每组都带 `[id]`，模型只能靠这个精确命中，靠描述猜必然出错。
     */
    private fun renderCandidates(): String {
        if (candidates.total == 0) return "（候选账单为空）"
        val blocks = buildList {
            block("最近 20 笔（最可能被改/删）", candidates.latest)
            block("今天", candidates.today)
            block("昨天", candidates.yesterday)
            block("前天", candidates.beforeYesterday)
            block("本周更早", candidates.thisWeek)
        }
        return blocks.joinToString("\n\n")
    }

    private fun MutableList<String>.block(title: String, bills: List<BillEntity>) {
        if (bills.isEmpty()) return
        add("【$title】\n" + bills.joinToString("\n") { line(it) })
    }

    private fun line(bill: BillEntity): String {
        val when_ = Instant.ofEpochMilli(bill.timestamp).atZone(zone).format(dateFormat)
        val category = CategoryLabels.displayName(
            categories.firstOrNull { it.id == bill.categoryId }?.name ?: ""
        )
        val name = bill.detail.ifBlank { category }
        val amount = "%.2f".format(bill.amountFen / 100.0)
        val type = if (bill.type == BillType.EXPENSE) "支出" else "收入"
        val note = if (bill.note.isNotBlank()) " · ${bill.note}" else ""
        return "[${bill.id}] $when_ · $category · $name · $amount 元 · $type$note"
    }

    private fun typeName(raw: String) = if (raw.equals("INCOME", true)) "收入" else "支出"

    companion object {
        private val dateFormat = DateTimeFormatter.ofPattern("M月d日 EEE HH:mm", java.util.Locale.CHINA)
        /**
         * 编解码必须带上默认值。
         *
         * 挂起账的 `detail` / `type` 常常就是默认值，`PendingDraft` 的 `createdAt` 也一样。
         * 不开 `encodeDefaults`，这些字段会被省掉，`decodeFromString` 虽然还能解出来，
         * 但往返一致性会悄悄丢掉「用户当时到底填了什么」，出问题时极难查。
         */
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** 每组候选的条数上限。总量控制在 45 条上下，prompt 不至于翻倍。 */
        const val LATEST_LIMIT = 20
        const val TODAY_LIMIT = 25
        const val DAY_LIMIT = 15
        const val WEEK_LIMIT = 10

        /**
         * 按时间边界切候选账单。所有入参都是「活账单」（调用方已过滤回收站）。
         *
         * 分组有重叠是故意的：模型看到同一条账单出现在「最近 20 笔」和「今天」里不会困惑，
         * 但少了「本周更早」它就找不到三天前的账了。
         */
        fun candidatesFrom(
            bills: List<BillEntity>,
            requestMillis: Long,
            zone: ZoneId
        ): CandidateBills {
            val todayStart = startOfDay(requestMillis, zone)
            val day = 86_400_000L
            val weekStart = todayStart - 6 * day
            val sorted = bills.sortedByDescending { it.timestamp }
            return CandidateBills(
                latest = sorted.take(LATEST_LIMIT),
                today = sorted.filter { it.timestamp >= todayStart }.take(TODAY_LIMIT),
                yesterday = sorted.filter { it.timestamp in (todayStart - day) until todayStart }.take(DAY_LIMIT),
                beforeYesterday = sorted.filter { it.timestamp in (todayStart - 2 * day) until (todayStart - day) }.take(DAY_LIMIT),
                thisWeek = sorted.filter { it.timestamp in weekStart until (todayStart - 2 * day) }.take(WEEK_LIMIT)
            )
        }

        private fun startOfDay(millis: Long, zone: ZoneId): Long =
            Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
                .atStartOfDay(zone).toInstant().toEpochMilli()

        /** 从一条消息里读出挂起账；不是挂起记录或载荷坏了都返回 null。 */
        fun pendingOf(message: ChatMessageEntity?): PendingDraft? {
            if (message == null || message.kind != "PENDING_DRAFT") return null
            return runCatching { json.decodeFromString(PendingDraft.serializer(), message.draftPayload) }.getOrNull()
        }

        /** 把挂起账编成可以塞进 `chat_messages.draftPayload` 的字符串。 */
        fun encodePending(draft: PendingDraft): String =
            json.encodeToString(PendingDraft.serializer(), draft)

        /** 从一条指令卡消息里读出载荷。 */
        fun commandOf(message: ChatMessageEntity?): CommandCardPayload? =
            message?.takeIf { it.kind == "COMMAND" }?.let { CommandCardCodec.decode(it.draftPayload) }
    }
}
