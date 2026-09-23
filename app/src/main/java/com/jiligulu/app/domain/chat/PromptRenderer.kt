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
 * v0.6 R9 起拆成两半，各管一段、都不许越界：
 * - [renderSystem]：读逐字不变的 system 资源，**不做任何替换**。它是 DeepSeek 前缀缓存的地基——
 *   缓存按前缀 64 token 一块地匹配，任何一个随请求变化的字符都会让整段命中率归零
 *   （这正是 v0.5.5 命中率为 0 的根因：旧模板第 3 行的 `{now}` 每次都变，前缀在第 3 行就断了）。
 * - [renderContext]：读动态上下文模板，按固定顺序填占位符。称呼、分类、账本、候选、时间、输入都在这里。
 *
 * 拆出来还有一个老理由：能单独测。提示词里少一个 `{bills}` 占位符、或者候选账单忘了带 id，
 * 模型就会静默地瞎猜，而单元测试能立刻抓到。
 */
class PromptRenderer(
    private val systemTemplate: String,
    private val contextTemplate: String,
    private val categories: List<CategoryEntity>,
    private val context: ChatContext,
    private val nickname: String,
    private val suffix: String,
    private val candidates: CandidateBills,
    private val pending: PendingDraft?,
    private val zone: ZoneId,
    /**
     * 回收站候选（恢复用）。T02a 还没有数据源，先留空；T02b 接
     * [com.jiligulu.app.data.repository.BillRepository.trashCandidates]。
     */
    private val trashCandidates: List<BillEntity> = emptyList(),
    /** 「待定」分类下的活账单（整理用）。同样 T02b 才接数据源。 */
    private val otherBills: List<BillEntity> = emptyList(),
    /** A single local settings snapshot; never enters the immutable system or chat history. */
    private val appSettings: String = ""
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

    /**
     * 固定 system 段。**逐字返回，不做任何替换**。
     *
     * 这里有且只有静态内容：人设、五类任务规则、新建分类规则、reply 要求、输出 JSON 格式。
     * 昵称/时间/账本一旦出现在这里，改一次就得整段重算，前缀缓存的意义就没了。
     */
    fun renderSystem(): String = systemTemplate

    /**
     * 动态上下文段。占位符顺序**固定**（称呼 → 分类 → 待补充 → 账本 → 候选 → 时间 → 输入），
     * 刻意用连续的 `replace` 而不是 Map：顺序一乱，模型读到的因果就乱了；这里也不允许换成无序结构。
     *
     * 候选三段「按需注入」：只有 [ChatIntent] 的本地关键词命中才注入，不命中就整段留空，
     * 既省 token，也避免模型看到一堆用不上的候选后瞎改。
     */
    fun renderContext(input: String): String = contextTemplate
        .replace("{address}", renderAddress())
        .replace("{categories}", renderCategories())
        .replace("{pending}", renderPending())
        .replace("{bills}", renderBills())
        .replace("{candidates}", if (ChatIntent.needsCandidates(input)) candidatesSection() else "")
        .replace("{trashCandidates}", if (ChatIntent.needsTrash(input)) trashSection() else "")
        .replace("{otherBills}", if (ChatIntent.needsOtherBills(input)) otherSection() else "")
        .replace("{appSettings}", appSettings)
        .replace("{now}", context.now)
        .replace("{timezone}", zone.id)
        .replace("{input}", input)

    /**
     * 称呼说明。昵称刻意放在动态段——它是「极低频率变化」，但一旦进了 system 就会污染前缀；
     * 放这里之后，改一次称呼最多影响本轮这一小段，前面的 [renderSystem] 照样命中。
     */
    private fun renderAddress(): String {
        val name = "$nickname$suffix"
        return if (name.isBlank()) {
            "用户还没告诉你该怎么称呼他，用「阿噜」的语气自然说话就好，不用硬叫名字。"
        } else {
            "用户希望你称他为：$name（不必每句都叫，自然一点）"
        }
    }

    private fun renderCategories(): String = categories.joinToString("\n") { category ->
        "- ${CategoryLabels.displayName(category.name)}（关键词：${category.keywords.ifBlank { "无" }}）"
    }

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

    /** 候选账单整段（含标题）。标题写在返回值里而不是模板里，这样不注入时整段为空、标题也不会漏出来。 */
    private fun candidatesSection(): String =
        "【候选账单】（改账/删账只能从这里面挑，方括号里是 id）\n" + renderCandidates()

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

    /** 回收站候选整段（含标题）。T02a 数据源未接、列表恒空，返回空串，不留一个空标题。 */
    private fun trashSection(): String {
        if (trashCandidates.isEmpty()) return ""
        return "【回收站候选】（恢复只能从这里面挑，方括号里是 id）\n" +
            trashCandidates.joinToString("\n") { line(it) }
    }

    /** 「待定」分类下的账单整段（含标题）。同样列表为空时返回空串。 */
    private fun otherSection(): String {
        if (otherBills.isEmpty()) return ""
        return "【「待定」分类下的账单】（整理用，方括号里是 id）\n" +
            otherBills.joinToString("\n") { line(it) }
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
