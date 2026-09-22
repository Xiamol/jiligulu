package com.jiligulu.app.data.repository

import android.content.Context
import com.jiligulu.app.core.ai.AiConfig
import com.jiligulu.app.core.ai.AiParseResult
import com.jiligulu.app.core.ai.ChatTurn
import com.jiligulu.app.core.ai.DeepSeekClient
import com.jiligulu.app.core.ai.SvgIconValidator
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.local.entity.CreatedBy
import com.jiligulu.app.data.local.entity.IconType
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.domain.category.CategoryLabels
import com.jiligulu.app.domain.chat.ChatContext
import com.jiligulu.app.domain.chat.ChatContextBuilder
import com.jiligulu.app.domain.chat.PromptRenderer
import com.jiligulu.app.domain.time.BillTimeResolver
import com.jiligulu.app.ui.chat.CommandCardCodec
import com.jiligulu.app.ui.chat.CommandCardPayload
import com.jiligulu.app.ui.chat.CommandItem
import com.jiligulu.app.ui.chat.CommandKind
import com.jiligulu.app.ui.chat.PendingDraft
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 确认入库时的单条草稿（UI 层可编辑后的最终态） */
data class ConfirmItem(
    val amountText: String, // 元，可编辑
    val type: BillType,
    val categoryName: String,
    val isNewCategory: Boolean,
    val iconEmoji: String,
    val iconSvg: String,
    val keywords: String,
    val detail: String,
    val note: String,
    val checked: Boolean,
    val timestamp: Long? = null,
    /** 时间没识别清楚时必须让用户先选，不能默认成此刻。 */
    val timeNeedsReview: Boolean = false,
    val timeHint: String = ""
)

/**
 * 一次 AI 回合的结论。UI 层只认这个，不直接碰 API 返回体——
 * 动作分派、幻觉过滤、挂起合流都在这儿收口。
 */
sealed interface AiTurn {
    /** 闲聊或没识别出账目：只说一句话。 */
    data class Chat(val reply: String) : AiTurn

    /** 新增账单草稿。 */
    data class Drafts(val reply: String, val drafts: List<ConfirmItem>) : AiTurn

    /** 改账/删账指令，等用户在卡片上点确认。 */
    data class Commands(val reply: String, val kind: CommandKind, val items: List<CommandItem>) : AiTurn

    /** 话没说完：挂起等下一句。 */
    data class Pending(val reply: String, val draft: PendingDraft) : AiTurn
}

/**
 * AI 对话记账：prompt 组装 → DeepSeek 拆解 → 动作分派 → 用户确认后落库。
 *
 * 提示词的填空逻辑在 [PromptRenderer]，这里只负责取数据、判动作、写数据。
 */
class AiRepository(
    private val context: Context,
    private val categoryRepository: CategoryRepository,
    private val billRepository: BillRepository,
    private val userPrefs: UserPrefs,
    private val chatHistoryRepository: ChatHistoryRepository
) {
    /** Versioned with the app; the complete parsing contract lives in one asset. */
    private val promptTemplate: String by lazy {
        context.assets.open(AiConfig.PROMPT_ASSET_PATH).bufferedReader().use { it.readText() }
    }

    /**
     * 设置页自定义的 Key 优先；没填则回落到构建期注入的内置 Key。
     * 两者都为空说明这个包没有内置 Key（比如源码自行构建时未在 local.properties 配置），
     * 此时抛出明确提示而不是拿空 Bearer 去撞 401。
     */
    suspend fun effectiveApiKey(): String {
        val key = userPrefs.apiKeyOverride.first().ifBlank { AiConfig.DEFAULT_API_KEY }
        require(key.isNotBlank()) { "还没有配置 API Key，请到「我的 → AI 服务」里填写" }
        return key
    }

    suspend fun nicknameWithSuffix(): String {
        val nick = userPrefs.nickname.first()
        val suffix = userPrefs.nameSuffix.first()
        return if (nick.isBlank()) "" else "$nick$suffix"
    }

    // ---------- 解析 ----------

    /**
     * 一趟拿到结论：取上下文 → 渲染 prompt → 问模型 → 判动作。
     *
     * [parseRaw] 保留「未经动作分派」的结果，供降级路径（本地规则）复用。
     */
    suspend fun parse(input: String, requestMillis: Long, zone: ZoneId): Result<AiParseResult> {
        val categories = categoryRepository.getAll()
        val pending = PromptRenderer.pendingOf(chatHistoryRepository.latestPending())
        val context = buildContext(categories, requestMillis, zone)
        val candidates = PromptRenderer.candidatesFrom(
            billRepository.recent(BillRepository.CANDIDATE_SCAN_LIMIT), requestMillis, zone
        )
        val prompt = PromptRenderer(
            template = promptTemplate,
            categories = categories,
            context = context,
            nickname = userPrefs.nickname.first().ifBlank { "主人" },
            suffix = userPrefs.nameSuffix.first(),
            candidates = candidates,
            pending = pending,
            zone = zone
        ).render(input)
        return DeepSeekClient(effectiveApiKey())
            .parseBill(prompt, input, history = recentTurns(requestMillis))
    }

    /**
     * 把解析结果翻译成 UI 能直接执行的一步。
     *
     * 三条硬规则（coder 拍板「要智能一点」，但智能不能等于瞎猜）：
     * - `update` / `delete` 的 target_id 必须命中候选里的**活账单**，否则整条丢弃（防幻觉）
     * - 一条都没命中 → 退化成聊天，让用户自己说清楚是哪一笔
     * - `add` 的 type 判成收入时金额必须为正；改账把金额改成 0 或负数时，反问是否要删
     */
    suspend fun toTurn(
        parsed: AiParseResult,
        input: String,
        requestMillis: Long,
        zone: ZoneId,
        pending: PendingDraft?
    ): AiTurn {
        val categories = categoryRepository.getAll()
        val candidates = PromptRenderer.candidatesFrom(
            billRepository.recent(BillRepository.CANDIDATE_SCAN_LIMIT), requestMillis, zone
        )
        val byId = (candidates.latest + candidates.today + candidates.yesterday +
            candidates.beforeYesterday + candidates.thisWeek).associateBy { it.id }
        val categoryNames = categories.associate { it.id to CategoryLabels.displayName(it.name) }

        val updates = parsed.bills.filter { it.isUpdate }
        val deletes = parsed.bills.filter { it.isDelete }

        // 改账优先：一句话里同时要改和删是极罕见的，真出现时按改处理更保守。
        if (updates.isNotEmpty()) {
            val items = updates.mapNotNull { draft ->
                val bill = byId[draft.targetId] ?: return@mapNotNull null
                val newAmount = draft.amountYuan.takeIf { it.isFinite() && it > 0 }
                if (newAmount == null) {
                    // 「改成 0」——这更像删账，让用户自己说清楚，别偷偷把账单改成 0。
                    return AiTurn.Chat(parsed.reply.ifBlank { "把金额改成 0 是想删掉它吗？说一声「删掉」阿噜就帮你收起来～" })
                }
                buildUpdateItem(bill, draft, newAmount, categoryNames, zone)
            }
            if (items.isNotEmpty()) return AiTurn.Commands(parsed.reply.ifBlank { "这些要改的账，确认一下～" }, CommandKind.UPDATE, items)
            return AiTurn.Chat(parsed.reply.ifBlank { "阿噜没找到你说的那笔账，能说得再具体点儿吗？" })
        }

        if (deletes.isNotEmpty()) {
            val items = deletes.mapNotNull { draft ->
                byId[draft.targetId]?.let { buildDeleteItem(it, categoryNames, zone) }
            }
            if (items.isNotEmpty()) return AiTurn.Commands(parsed.reply.ifBlank { "这些账阿噜先收进回收站，确认一下～" }, CommandKind.DELETE, items)
            return AiTurn.Chat(parsed.reply.ifBlank { "阿噜没找到你要删的那笔，说个大概时间或名目？" })
        }

        val adds = parsed.bills.filter { it.isAdd || (!it.isUpdate && !it.isDelete) }
        // 模型报了「话没说完」；或者它这轮只回了一句话、又确实有挂着的账，就沿用挂起态。
        val carried = pending?.takeIf { adds.isEmpty() }
        val modelPending = parsed.pending?.takeIf { it.amountYuan > 0 }
        if (adds.isEmpty() && carried != null && modelPending == null) return AiTurn.Pending(
            parsed.reply.ifBlank { "还欠阿噜一个名目哦，这笔钱是花在哪儿啦？" },
            carried.copy(rawInput = carried.rawInput.ifBlank { input })
        )
        if (adds.isEmpty() && modelPending != null) return AiTurn.Pending(
            parsed.reply.ifBlank { "这笔多少钱是花在哪儿啦？阿噜先记着～" },
            PendingDraft(
                amountText = trimAmount(modelPending.amountYuan),
                detail = modelPending.detail,
                type = if (modelPending.type.equals("INCOME", true)) "INCOME" else "EXPENSE",
                rawInput = input,
                createdAt = requestMillis
            )
        )

        if (adds.isEmpty()) return AiTurn.Chat(parsed.reply.ifBlank { "阿噜在听，你说～" })

        val drafts = adds.map { bill ->
            val expression = BillTimeResolver.expressionForBill(input, bill.detail, adds.size, bill.timeExpression)
            val time = BillTimeResolver.resolve(expression, bill.occurredAt, requestMillis, zone)
            ConfirmItem(
                amountText = if (bill.amountYuan.isFinite() && bill.amountYuan > 0) trimAmount(bill.amountYuan) else "",
                type = if (bill.type.equals("INCOME", true)) BillType.INCOME else BillType.EXPENSE,
                categoryName = bill.category.ifBlank { "未分类" },
                isNewCategory = bill.isNewCategory,
                iconEmoji = bill.iconEmoji,
                iconSvg = bill.iconSvg,
                keywords = bill.keywords,
                detail = bill.detail,
                note = bill.note,
                checked = true,
                timestamp = time.timestamp,
                timeNeedsReview = time.needsReview,
                timeHint = time.hint
            )
        }
        return AiTurn.Drafts(parsed.reply.ifBlank { "草稿整理好了，确认一下就记入账本，阿噜～" }, drafts)
    }

    private fun buildUpdateItem(
        bill: BillEntity,
        draft: com.jiligulu.app.core.ai.AiBillDraft,
        newAmount: Double,
        categoryNames: Map<Long, String>,
        zone: ZoneId
    ): CommandItem {
        // 未提及的字段一律沿用原值——这是「不许自己编值」的落点。
        val detail = draft.detail.ifBlank { bill.detail }
        val type = when {
            draft.type.equals("INCOME", true) -> BillType.INCOME
            draft.type.equals("EXPENSE", true) -> BillType.EXPENSE
            else -> bill.type
        }
        val categoryName = draft.category.ifBlank { categoryNames[bill.categoryId].orEmpty() }
        val note = draft.note.ifBlank { bill.note }
        val timestamp = bill.timestamp
        return CommandItem(
            billId = bill.id,
            title = detail.ifBlank { categoryName },
            categoryName = CategoryLabels.displayName(categoryName),
            iconEmoji = draft.iconEmoji.ifBlank { "🧾" },
            isExpense = type == BillType.EXPENSE,
            before = summary(bill, categoryNames, zone),
            after = "%s · %s · %s 元 · %s".format(
                dayTime(timestamp, zone), CategoryLabels.displayName(categoryName),
                trimAmount(newAmount), if (type == BillType.EXPENSE) "支出" else "收入"
            ) + if (note.isNotBlank()) " · $note" else "",
            checked = true,
            newAmountText = trimAmount(newAmount),
            newType = type,
            newDetail = detail,
            newNote = note,
            newTimestamp = timestamp,
            newCategoryName = categoryName
        )
    }

    private fun buildDeleteItem(bill: BillEntity, categoryNames: Map<Long, String>, zone: ZoneId) = CommandItem(
        billId = bill.id,
        title = bill.detail.ifBlank { CategoryLabels.displayName(categoryNames[bill.categoryId].orEmpty()) },
        categoryName = CategoryLabels.displayName(categoryNames[bill.categoryId].orEmpty()),
        isExpense = bill.type == BillType.EXPENSE,
        before = summary(bill, categoryNames, zone),
        after = "",
        checked = true
    )

    private fun summary(bill: BillEntity, categoryNames: Map<Long, String>, zone: ZoneId): String {
        val category = CategoryLabels.displayName(categoryNames[bill.categoryId].orEmpty())
        val extra = if (bill.note.isNotBlank()) " · ${bill.note}" else ""
        return "${dayTime(bill.timestamp, zone)} · ${Formatters.fenToYuanText(bill.amountFen)} 元 · " +
            "${if (bill.type == BillType.EXPENSE) "支出" else "收入"}$extra"
    }

    private fun dayTime(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA))

    // ---------- 上下文 ----------

    /**
     * 组装三层上下文：24 小时内的对话 + 最近 3 天的账本。
     *
     * 刻意宽松：任何一步失败都不该让整轮对话挂掉——上下文是"锦上添花"，
     * 拿不到就退化成"只有这一句话"，等价于改动之前的行为。
     */
    private suspend fun buildContext(
        categories: List<CategoryEntity>,
        requestMillis: Long,
        zone: ZoneId
    ): ChatContext = runCatching {
        val messages = chatHistoryRepository.getAll()
        val bills = billRepository.recent(ChatContextBuilder.MAX_BILLS)
        ChatContextBuilder.build(messages, bills, categories, requestMillis, zone)
    }.getOrElse { ChatContext("", zone.id, emptyList(), emptyList()) }

    /** 带回模型的多轮消息：只取真正的对话，草稿卡摘要作为 assistant 侧信息带入。 */
    private suspend fun recentTurns(requestMillis: Long): List<ChatTurn> = runCatching {
        val cutoff = requestMillis - ChatContextBuilder.MESSAGE_WINDOW_HOURS * 3_600_000L
        chatHistoryRepository.getAll()
            .filter { it.createdAt >= cutoff }
            .mapNotNull { message ->
                when (message.kind) {
                    "USER" -> message.content.takeIf { it.isNotBlank() }
                        ?.let { ChatTurn("user", it) }

                    "ASSISTANT" -> message.content.takeIf {
                        it.isNotBlank() && message.status != "PENDING" && message.status != "INTERRUPTED"
                    }?.let { ChatTurn("assistant", it) }

                    else -> null
                }
            }
            .takeLast(ChatContextBuilder.MAX_MESSAGES)
    }.getOrElse { emptyList() }

    // ---------- 落库 ----------

    /**
     * Validate every selected draft, then commit all bills and the card status in one transaction.
     */
    suspend fun confirm(cardId: Long, items: List<ConfirmItem>, rawText: String, finalPayload: String? = null): Int =
        chatHistoryRepository.confirmDraftAtomically(cardId, finalPayload) {
            val selected = items.filter { it.checked }
            require(selected.isNotEmpty()) { "请至少选择一条账单" }
            val valid = selected.map { item ->
                item to requireNotNull(Formatters.yuanTextToFen(item.amountText)) { "请填写有效的金额" }
            }
            val existing = categoryRepository.getAll().associate { it.name.lowercase() to it.id }.toMutableMap()
            val confirmedAt = System.currentTimeMillis()
            valid.forEach { (item, fen) ->
                val categoryName = item.categoryName.ifBlank { "未分类" }.lowercase()
                val categoryId = existing[categoryName] ?: createCategoryFromDraft(item).also { existing[categoryName] = it }
                billRepository.addFromAi(
                    amountFen = fen,
                    type = item.type,
                    categoryId = categoryId,
                    detail = item.detail,
                    note = item.note,
                    rawText = rawText,
                    timestamp = item.timestamp ?: confirmedAt
                )
            }
            valid.size
        }

    /**
     * 提交一张改账 / 删账卡。
     *
     * 整件事在一个事务里：分类新建、每条账单的改动、卡片状态。任何一步出错全部回滚，
     * 卡片仍然是 EDITING，用户可以重试——不会出现「改了一半」的账本。
     * 返回真正生效的条数（已提交过的条目会被跳过，保证重复点击安全）。
     */
    suspend fun commitCommands(
        cardId: Long,
        payload: CommandCardPayload,
        appliedAt: Long = System.currentTimeMillis()
    ): Int = chatHistoryRepository.withTransaction {
        val stored = checkNotNull(chatHistoryRepository.getById(cardId)) { "这张卡片不存在" }
        val existing = CommandCardCodec.decode(stored.draftPayload) ?: payload
        val pending = CommandCardCodec.pendingCount(existing)
        if (pending <= 0) return@withTransaction 0
        if (stored.status != "EDITING") return@withTransaction 0

        val selected = existing.items.filter { it.checked }.take(pending)
        val kind = if (existing.kind.equals(CommandKind.DELETE.name, true)) CommandKind.DELETE else CommandKind.UPDATE
        val categoryIds = categoryRepository.getAll().associate { it.name.lowercase() to it.id }.toMutableMap()
        var applied = 0

        selected.forEach { item ->
            if (kind == CommandKind.DELETE) {
                if (billRepository.moveToTrash(item.billId, appliedAt)) applied++
            } else {
                val fen = Formatters.yuanTextToFen(item.newAmountText) ?: return@forEach
                val key = item.newCategoryName.ifBlank { item.categoryName }.lowercase()
                val categoryId = categoryIds[key] ?: createCategory(
                    name = item.newCategoryName.ifBlank { item.categoryName },
                    keywords = ""
                ).also { categoryIds[key] = it }
                if (billRepository.updateFromAi(
                    id = item.billId, amountFen = fen, detail = item.newDetail,
                    timestamp = item.newTimestamp.takeIf { it > 0 } ?: appliedAt,
                    categoryId = categoryId, note = item.newNote
                )) applied++
            }
        }

        val updated = existing.copy(alreadyApplied = existing.alreadyApplied + applied, confirmFailed = false)
        chatHistoryRepository.update(
            stored.copy(draftPayload = CommandCardCodec.encode(updated))
        )
        applied
    }

    /**
     * 用户在卡片上点了确认，但落库失败。
     *
     * 单独记一笔：崩溃测试发现「已经应用到数据库、但 UI 状态没写进去」时，
     * 光看卡片状态会以为什么都没发生，于是重复点击又执行一遍。
     */
    suspend fun markCommandConfirmationFailed(cardId: Long) {
        val stored = chatHistoryRepository.getById(cardId) ?: return
        val payload = CommandCardCodec.decode(stored.draftPayload) ?: return
        chatHistoryRepository.update(
            stored.copy(draftPayload = CommandCardCodec.encode(payload.copy(confirmFailed = true)))
        )
    }

    /** AI 首次提到的分类 → 自动新建：SVG 过白名单用 SVG，否则 emoji 兜底（PRD §5.3 方案 A+B） */
    private suspend fun createCategoryFromDraft(item: ConfirmItem): Long = createCategory(
        name = item.categoryName.ifBlank { "未分类" },
        iconType = if (item.isNewCategory && SvgIconValidator.isValid(item.iconSvg)) IconType.SVG else IconType.EMOJI,
        iconValue = item.iconEmoji.ifBlank { "🫧" },
        iconSvg = if (item.isNewCategory && SvgIconValidator.isValid(item.iconSvg)) item.iconSvg else "",
        keywords = item.keywords
    )

    private suspend fun createCategory(
        name: String,
        keywords: String = "",
        iconType: IconType = IconType.EMOJI,
        iconValue: String = "🫧",
        iconSvg: String = ""
    ): Long = categoryRepository.createCategory(
        name = name.ifBlank { "未分类" },
        iconType = iconType,
        iconValue = iconValue,
        iconSvg = iconSvg,
        keywords = keywords,
        createdBy = CreatedBy.AI
    )

    private fun trimAmount(value: Double): String =
        java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
