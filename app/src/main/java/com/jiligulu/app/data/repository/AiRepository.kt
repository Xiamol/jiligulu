package com.jiligulu.app.data.repository

import android.content.Context
import com.jiligulu.app.core.ai.AiConfig
import com.jiligulu.app.core.ai.AiAppAction
import com.jiligulu.app.core.ai.AiOption
import com.jiligulu.app.core.ai.AiParseResult
import com.jiligulu.app.core.ai.ChatTurn
import com.jiligulu.app.core.ai.DeepSeekClient
import com.jiligulu.app.core.ai.SvgIconValidator
import com.jiligulu.app.core.ai.NavTargets
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.data.local.entity.CreatedBy
import com.jiligulu.app.data.local.entity.IconType
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.data.reminder.WaterReminderScheduler
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
import com.jiligulu.app.ui.chat.AppActionCodec
import com.jiligulu.app.ui.chat.AppActionPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    /** 改账/删账/恢复指令，等用户在卡片上点确认。 */
    data class Commands(val reply: String, val kind: CommandKind, val items: List<CommandItem>) : AiTurn

    /** 话没说完：挂起等下一句。 */
    data class Pending(val reply: String, val draft: PendingDraft) : AiTurn

    /**
     * R4/R5：可点选项（指路 / 带路）。用户没指定具体账单、只问「怎么恢复」，
     * 或问「回收站在哪」时出现；选项的跳转/追问渲染归 T04，这里只携带归一化后的选项。
     */
    data class Choices(val reply: String, val options: List<AiOption>) : AiTurn
    data class AppAction(val payload: AppActionPayload) : AiTurn
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
    private val chatHistoryRepository: ChatHistoryRepository,
    /** R4：恢复时解析内置「待定」兜底分类（CategoryAdminRepository.vacuumId）。 */
    private val categoryAdminRepository: CategoryAdminRepository,
    /** Isolated platform side effect, after the durable settings commit; replaceable in failure tests. */
    private val synchronizeWaterReminder: suspend (AiAppAction) -> Unit = { _ ->
        if (!userPrefs.waterEnabled.first()) WaterReminderScheduler.cancel(context)
        else WaterReminderScheduler.restore(context)
    },
    private val clientFactory: (String) -> DeepSeekClient = { DeepSeekClient(it) }
) {
    /** R9：逐字不变的固定 system 段；版本随 App 走，解析契约全在这一个资源里。 */
    private val systemPromptTemplate: String by lazy {
        context.assets.open(AiConfig.SYSTEM_PROMPT_ASSET_PATH).bufferedReader().use { it.readText() }
    }

    /** R9：动态上下文模板（称呼/分类/账本/候选/时间/输入）。 */
    private val contextPromptTemplate: String by lazy {
        context.assets.open(AiConfig.CONTEXT_PROMPT_ASSET_PATH).bufferedReader().use { it.readText() }
    }

    /**
     * 设置页自定义的 Key 优先；没填则回落到构建期注入的内置 Key。
     * 两者都为空说明这个包没有内置 Key（比如源码自行构建时未在 local.properties 配置），
     * 此时抛出明确提示而不是拿空 Bearer 去撞 401。
     */
    suspend fun effectiveApiKey(): String {
        val key = userPrefs.apiKeyOverride.first().ifBlank { AiConfig.DEFAULT_API_KEY }
        // R5：App 没有「我的」页，AI 服务在设置页——报错也要指路指对，跟功能地图口径一致。
        require(key.isNotBlank()) { "还没有配置 API Key，请到「设置 → AI 服务」里填写" }
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
        val chatContext = buildContext(categories, requestMillis, zone)
        val candidates = PromptRenderer.candidatesFrom(
            billRepository.recent(BillRepository.CANDIDATE_SCAN_LIMIT), requestMillis, zone
        )
        // R4：回收站候选（情况六 restore 的 target_id 来源）。是否注入由 ChatIntent.needsTrash 决定。
        val trashCandidates = billRepository.trashCandidates(BillRepository.TRASH_CANDIDATE_LIMIT)
        val water = userPrefs.waterReminderState.first()
        fun clock(minutes: Int) = "%02d:%02d".format(Locale.ROOT, minutes / 60, minutes % 60)
        val appSettings = "【当前提醒设置】\n喝水提醒：${if (water.enabled) "开启" else "关闭"}；间隔：${water.intervalMinutes} 分钟；" +
            "免打扰：${if (water.quietStartMinutes == water.quietEndMinutes) "关闭" else "${clock(water.quietStartMinutes)}—${clock(water.quietEndMinutes)}"}。"
        // R9：system 段逐字不变（缓存地基），动态内容全部走 context 段。
        val renderer = PromptRenderer(
            systemTemplate = systemPromptTemplate,
            contextTemplate = contextPromptTemplate,
            categories = categories,
            context = chatContext,
            nickname = userPrefs.nickname.first().ifBlank { "主人" },
            suffix = userPrefs.nameSuffix.first(),
            candidates = candidates,
            pending = pending,
            zone = zone,
            trashCandidates = trashCandidates,
            appSettings = appSettings
        )
        val system = renderer.renderSystem()
        val contextBlock = renderer.renderContext(input)
        return clientFactory(effectiveApiKey())
            .parseBill(system, contextBlock, history = recentTurns(requestMillis))
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
        parsed.appAction?.let { action ->
            if (!action.isValid || parsed.bills.isNotEmpty()) {
                return AiTurn.Chat("这次操作还不够明确，阿噜先不动设置。请分开说要改什么～")
            }
            return when (action.kind) {
                AiAppAction.WATER_SETTINGS -> {
                    val normalized = freezeQuietSettings(action)
                    AiTurn.AppAction(AppActionPayload(action = normalized, summary = waterActionSummary(normalized)))
                }
                AiAppAction.EMPTY_TRASH -> {
                    val trash = billRepository.trash()
                    val ids = trash.map { it.id }
                    if (ids.isEmpty()) AiTurn.Chat("回收站里的账单已经空啦，草稿还好好留着 ♡")
                    else AiTurn.AppAction(AppActionPayload(action = action, summary = buildString {
                        append("彻底删除回收站现有的 ${ids.size} 笔账单")
                        trash.take(8).forEach { append("\n${dayTime(it.timestamp, zone)} · ${it.detail.ifBlank { "账单" }} · ${Formatters.fenToYuanText(it.amountFen)} 元") }
                        if (ids.size > 8) append("\n还有 ${ids.size - 8} 笔；需要逐笔核对可先去回收站查看。")
                    }, trashIds = ids, trashDeletedAt = trash.associate { it.id to checkNotNull(it.deletedAt) }))
                }
                else -> AiTurn.Chat("阿噜还不会执行这个操作，可以到设置里看看～")
            }
        }
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
                val timeExpression = updateTimeExpression(input)
                val hasOtherChange = draft.detail.isNotBlank() || draft.category.isNotBlank() || draft.note.isNotBlank() || timeExpression.isNotBlank()
                val explicitlyZero = Regex("(?:金额|改成|改为|调到|改到)\\s*[¥￥]?\\s*(?:0(?:\\.0+)?|零)(?:\\s*[元块]|\\s*$)").containsMatchIn(input)
                val newAmount = when {
                    draft.amountYuan.isFinite() && draft.amountYuan > 0 -> draft.amountYuan
                    draft.amountYuan == 0.0 && hasOtherChange && !explicitlyZero -> bill.amountFen / 100.0
                    else -> null
                }
                if (newAmount == null) {
                    // 「改成 0」——这更像删账，让用户自己说清楚，别偷偷把账单改成 0。
                    return AiTurn.Chat(parsed.reply.ifBlank { "把金额改成 0 是想删掉它吗？说一声「删掉」阿噜就帮你收起来～" })
                }
                val resolved = BillTimeResolver.resolve(timeExpression, requestMillis = requestMillis, zone = zone)
                if (resolved.needsReview) return AiTurn.Chat("这次要改的日期还不够具体，请告诉阿噜哪一天、几点～")
                buildUpdateItem(bill, draft, newAmount, categoryNames, zone, resolved.timestamp ?: bill.timestamp)
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

        // R4 恢复：target_id 必须命中回收站候选，否则整条丢弃（与改/删同一道硬校验，防幻觉）。
        val restores = parsed.bills.filter { it.isRestore }
        if (restores.isNotEmpty()) {
            val trashById = billRepository.trashCandidates(BillRepository.TRASH_CANDIDATE_LIMIT).associateBy { it.id }
            val items = restores.mapNotNull { draft ->
                trashById[draft.targetId]?.let { buildRestoreItem(it, categoryNames, zone) }
            }
            if (items.isNotEmpty()) return AiTurn.Commands(parsed.reply.ifBlank { "这些账阿噜从回收站捞回来，确认一下～" }, CommandKind.RESTORE, items)
            return AiTurn.Chat(parsed.reply.ifBlank { "阿噜在回收站没找到你说的那笔，说个大概名目或哪天删的？" })
        }

        // R4/R5：没给任何账单动作时，可点选项（多选卡 / 单目标跳转）优先于闲聊。
        if (parsed.bills.isEmpty()) {
            val choices = normalizeOptions(parsed.options).ifEmpty {
                parsed.navigate?.takeIf { it.isNotBlank() }?.let { listOf(AiOption(label = "", action = it)) }
                    ?: emptyList()
            }
            if (choices.isNotEmpty()) return AiTurn.Choices(
                if (choices.any { it.action.removePrefix("navigate:") == NavTargets.CHECK_UPDATE }) "点下面的卡片开始检查更新，结果会显示在设置页～"
                else parsed.reply.ifBlank { "阿噜给你指条路～" }, choices
            )
        }

        val adds = parsed.bills.filter { it.isAdd }
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
        zone: ZoneId,
        timestamp: Long
    ): CommandItem {
        // 未提及的字段一律沿用原值——这是「不许自己编值」的落点。
        val detail = draft.detail.ifBlank { bill.detail }
        // The update DAO does not change bill type. Never advertise a type change it cannot apply.
        val type = bill.type
        val categoryName = draft.category.ifBlank { categoryNames[bill.categoryId].orEmpty() }
        val note = draft.note.ifBlank { bill.note }
        return CommandItem(
            billId = bill.id,
            title = detail.ifBlank { categoryName },
            categoryName = CategoryLabels.displayName(categoryName),
            iconEmoji = draft.iconEmoji,
            isExpense = type == BillType.EXPENSE,
            before = summary(bill, categoryNames, zone),
            after = "%s · %s · %s 元 · %s".format(
                dayTime(timestamp, zone), detail.ifBlank { CategoryLabels.displayName(categoryName) },
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

    /** R4 恢复指令条目：before 标出「回收站中」，after 是捞回后的原貌（金额/分类不动）。 */
    private fun buildRestoreItem(bill: BillEntity, categoryNames: Map<Long, String>, zone: ZoneId) = CommandItem(
        billId = bill.id,
        title = bill.detail.ifBlank { CategoryLabels.displayName(categoryNames[bill.categoryId].orEmpty()) },
        categoryName = CategoryLabels.displayName(categoryNames[bill.categoryId].orEmpty()),
        iconEmoji = "♻️",
        isExpense = bill.type == BillType.EXPENSE,
        before = "回收站中 · " + summary(bill, categoryNames, zone),
        after = summary(bill, categoryNames, zone),
        checked = true
    )

    /**
     * R4/R5：把模型输出的 options 归一成 UI 能直接用的选项。
     *
     * - "navigate:xxx" 形式在这里剥掉前缀，UI 拿到的 action 就是跳转目标本身
     * - label / action 为空的条目直接丢掉：点无可点，留着只会渲染出坏按钮
     * - 表外取值（模型编造的 action）原样保留，忽略策略在 UI 侧——那里才知道自己认哪些值
     */
    private fun normalizeOptions(options: List<AiOption>): List<AiOption> = options.mapNotNull { option ->
        val action = option.action
        when {
            option.label.isBlank() || action.isBlank() -> null
            action.startsWith("navigate:") -> option.copy(action = action.removePrefix("navigate:"))
            else -> option
        }
    }

    private fun summary(bill: BillEntity, categoryNames: Map<Long, String>, zone: ZoneId): String {
        val category = CategoryLabels.displayName(categoryNames[bill.categoryId].orEmpty())
        val extra = if (bill.note.isNotBlank()) " · ${bill.note}" else ""
        return "${dayTime(bill.timestamp, zone)} · ${bill.detail.ifBlank { category }} · ${Formatters.fenToYuanText(bill.amountFen)} 元 · " +
            "${if (bill.type == BillType.EXPENSE) "支出" else "收入"}$extra"
    }

    private fun dayTime(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA))

    /** Date words identifying an existing bill are not a request to change its time. */
    private fun updateTimeExpression(input: String): String {
        val target = Regex("(?:改成|改为|改到|调到|更正为|实际是|应该是)\\s*(.+)")
            .find(input)?.groupValues?.get(1).orEmpty()
        return target.takeIf { BillTimeResolver.hasTimeExpression(it) }.orEmpty()
    }

    // ---------- 上下文 ----------

    /**
     * 组装动态上下文：当前时间 + 最近 3 天的账本（对话历史改由 [recentTurns] 走 messages 数组）。
     *
     * 刻意宽松：任何一步失败都不该让整轮对话挂掉——上下文是"锦上添花"，
     * 拿不到就退化成"只有这一句话"，等价于改动之前的行为。
     */
    private suspend fun buildContext(
        categories: List<CategoryEntity>,
        requestMillis: Long,
        zone: ZoneId
    ): ChatContext = try {
        val bills = billRepository.recent(ChatContextBuilder.MAX_BILLS)
        ChatContextBuilder.build(bills, categories, requestMillis, zone)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { ChatContextBuilder.build(emptyList(), categories, requestMillis, zone) }

    /**
     * 带回模型的多轮消息：只取真正的对话（USER / ASSISTANT 原文），草稿卡/指令卡一律不进上下文。
     *
     * R9 之后历史改走 messages 数组，本轮输入则改由 `renderContext` 的 `用户这轮说：` 承载；
     * 因此这里必须把「本轮刚落库的那条 USER」剔掉，否则模型会把同一句话看到两遍（见 [chatTurnsFor]）。
     */
    private suspend fun recentTurns(requestMillis: Long): List<ChatTurn> = try {
        chatTurnsFor(chatHistoryRepository.getAll(), requestMillis)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { emptyList() }

    companion object {
        /**
         * 把持久化消息映射成带回模型的多轮对话（仅 USER/ASSISTANT 原文）。
         *
         * 为什么要丢掉末尾那条 user：本轮 `send()` 会先落一条 USER、随后落一条 ASSISTANT(PENDING)，
         * 而 PENDING 的 assistant 会在下面被滤掉——于是本轮那条 USER 就成了列表末尾「没有回复的 user」。
         * 本轮输入已经由动态上下文里的 `用户这轮说：{input}` 承担，若历史里再出现一次，
         * 模型会以为用户连说了两轮同样的话，因此只裁掉末尾这一条。
         *
         * 刻意「只裁末尾一条」而不是 `dropLastWhile`：更早的 user 是真实历史——哪怕它的 assistant
         * 曾被中断过，也该留给模型承接上下文（用户明确要求「历史对话是刚需」）。
         *
         * 选「末尾未配对的 user」而不是「按 id 排除」，是因为前者不依赖调用方把 id 传进来，
         * 也能在异常恢复后自洽；它同样不改动 [ChatContextBuilder.MAX_MESSAGES] 的窗口语义，
         * 更不引入任何按轮数的裁切。
         */
        internal fun chatTurnsFor(messages: List<ChatMessageEntity>, requestMillis: Long): List<ChatTurn> {
            val cutoff = requestMillis - ChatContextBuilder.MESSAGE_WINDOW_HOURS * 3_600_000L
            val turns = messages
                .filter { it.createdAt >= cutoff }
                .mapNotNull { message ->
                    when (message.kind) {
                        "USER" -> message.content.takeIf { it.isNotBlank() }?.let { ChatTurn("user", it) }

                        "ASSISTANT" -> message.content.takeIf {
                            it.isNotBlank() && message.status != "PENDING" && message.status != "INTERRUPTED"
                        }?.let { ChatTurn("assistant", it) }

                        else -> null
                    }
                }
                .takeLast(ChatContextBuilder.MAX_MESSAGES)
            return if (turns.lastOrNull()?.role == "user") turns.dropLast(1) else turns
        }
    }

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
                val categoryId = existing[categoryName] ?: (
                    if (item.isNewCategory) createCategoryFromDraft(item) else categoryAdminRepository.vacuumId()
                ).also { existing[categoryName] = it }
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
        check(stored.kind == "COMMAND") { "这条消息不是账单变更卡" }
        val existing = checkNotNull(CommandCardCodec.decode(stored.draftPayload)) { "变更卡内容无法读取" }
        val pending = CommandCardCodec.pendingCount(existing)
        if (pending <= 0) return@withTransaction 0
        if (stored.status != "EDITING") return@withTransaction 0
        // Legacy payloads only recorded a count, not applied IDs. Replaying a guessed prefix is unsafe.
        if (existing.alreadyApplied > 0) {
            chatHistoryRepository.update(stored.copy(status = "CONFIRMED"))
            return@withTransaction 0
        }

        val selected = existing.items.filter { it.checked }.take(pending)
        // 三种动作都要认全：早先是 DELETE / 其余归 UPDATE 的二分法，
        // R4 加了 RESTORE 后若仍这么写，「恢复」会被当成「改账」执行（灾难）。
        val kind = requireNotNull(CommandKind.entries.firstOrNull { it.name.equals(existing.kind, true) }) { "不支持的账单操作" }
        val categoryIds = categoryRepository.getAll().associate { it.name.lowercase() to it.id }.toMutableMap()
        var applied = 0

        selected.forEach { item ->
            when (kind) {
                CommandKind.DELETE -> {
                    if (billRepository.moveToTrash(item.billId, appliedAt)) applied++
                }

                CommandKind.RESTORE -> {
                    // 恢复：捞回活账本；原分类若已被删除（孤儿分类），DAO 兜底改挂内置「待定」。
                    val fallbackId = categoryAdminRepository.vacuumId()
                    if (billRepository.restoreToLive(item.billId, fallbackId)) applied++
                }

                CommandKind.UPDATE -> {
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
        }

        val updated = existing.copy(alreadyApplied = existing.alreadyApplied + applied, confirmFailed = false)
        chatHistoryRepository.update(
            stored.copy(draftPayload = CommandCardCodec.encode(updated), status = "CONFIRMED")
        )
        applied
    }

    private suspend fun freezeQuietSettings(action: AiAppAction): AiAppAction {
        if (action.quietEnabled == false || (action.quietEnabled == null && action.quietStartMinutes == null && action.quietEndMinutes == null)) return action
        val settings = userPrefs.waterReminderState.first()
        val start = settings.quietStartMinutes
        val end = settings.quietEndMinutes
        return action.copy(
            quietStartMinutes = action.quietStartMinutes ?: if (action.quietEnabled == true && start == end) UserPrefs.DEFAULT_QUIET_START else start,
            quietEndMinutes = action.quietEndMinutes ?: if (action.quietEnabled == true && start == end) UserPrefs.DEFAULT_QUIET_END else end
        )
    }

    private suspend fun waterActionSummary(action: AiAppAction): String = buildList {
        action.enabled?.let { add("喝水提醒：${if (it) "开启" else "关闭"}") }
        action.intervalMinutes?.let { add("提醒间隔：${userPrefs.waterIntervalMinutes.first()} → $it 分钟") }
        if (action.quietEnabled != null || action.quietStartMinutes != null || action.quietEndMinutes != null) {
            val currentStart = userPrefs.quietStartMinutes.first()
            val currentEnd = userPrefs.quietEndMinutes.first()
            val start = action.quietStartMinutes ?: if (action.quietEnabled == true && currentStart == currentEnd) UserPrefs.DEFAULT_QUIET_START else currentStart
            val end = action.quietEndMinutes ?: if (action.quietEnabled == true && currentStart == currentEnd) UserPrefs.DEFAULT_QUIET_END else currentEnd
            fun clock(minutes: Int) = "%02d:%02d".format(Locale.ROOT, minutes / 60, minutes % 60)
            add(if (action.quietEnabled == false || start == end) "免打扰：关闭" else "免打扰：${clock(start)}—${clock(end)}")
        }
    }.joinToString("\n")

    /** Persisted cards are the source of truth. Nothing is executed while parsing an AI reply. */
    suspend fun commitAppAction(cardId: Long): String = chatHistoryRepository.withConversationLock {
        val stored = checkNotNull(chatHistoryRepository.getById(cardId)) { "这张卡片不存在" }
        check(stored.kind == "APP_ACTION")
        val payload = checkNotNull(AppActionCodec.decode(stored.draftPayload)) { "操作参数无法读取" }
        if (stored.status !in setOf("EDITING", "APPLYING")) return@withConversationLock "这张卡片已经处理过啦。"
        if (payload.action.kind == AiAppAction.EMPTY_TRASH) {
            require(payload.trashIds.isNotEmpty() && payload.trashIds.all { it in payload.trashDeletedAt }) {
                "这张旧清空卡缺少账单快照，请重新让阿噜生成清空卡。"
            }
            val count = chatHistoryRepository.withTransaction {
                val current = checkNotNull(chatHistoryRepository.getById(cardId))
                if (current.status != "EDITING") return@withTransaction 0
                val ids = payload.trashIds.toSet()
                val targets = billRepository.trash().filter { it.id in ids && it.deletedAt == payload.trashDeletedAt[it.id] }
                targets.forEach { billRepository.purge(it.id) }
                chatHistoryRepository.update(current.copy(status = "CONFIRMED", draftPayload = AppActionCodec.encode(payload.copy(appliedCount = targets.size))))
                targets.size
            }
            return@withConversationLock "回收站里 $count 笔账单已彻底删除，草稿保留着 ♡"
        }
        // This small local commit must finish even when its screen leaves. APPLYING survives a process
        // death or a Room write failure: it can be retried, but can never pretend to cancel saved settings.
        withContext(NonCancellable) {
            val applying = stored.copy(status = "APPLYING")
            chatHistoryRepository.update(applying)
            val action = payload.action
            userPrefs.setWaterSettings(action.enabled, action.intervalMinutes, action.quietEnabled, action.quietStartMinutes, action.quietEndMinutes)
            chatHistoryRepository.update(applying.copy(status = "CONFIRMED"))
            val scheduled = try {
                withTimeout(5_000) { synchronizeWaterReminder(action) }
                true
            } catch (_: Exception) {
                // Preferences are already committed. Startup restores the persisted reminder state.
                false
            }
            if (scheduled) "喝水提醒已按卡片调整好啦，阿噜 ♡"
            else "设置已经保存；提醒调度暂时没接上，下次打开软件会再尝试恢复。"
        }
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
        iconValue = item.iconEmoji,
        iconSvg = if (item.isNewCategory && SvgIconValidator.isValid(item.iconSvg)) item.iconSvg else "",
        keywords = item.keywords
    )

    private suspend fun createCategory(
        name: String,
        keywords: String = "",
        iconType: IconType = IconType.EMOJI,
        iconValue: String = "",
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
