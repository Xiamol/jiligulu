package com.jiligulu.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jiligulu.app.BuildConfig
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.ai.AiParseResult
import com.jiligulu.app.core.ai.DeepSeekEmptyResponseException
import com.jiligulu.app.core.ai.DeepSeekHttpException
import com.jiligulu.app.core.ai.LocalBillParser
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.data.repository.AiRepository
import com.jiligulu.app.data.repository.AiTurn
import com.jiligulu.app.data.repository.CategoryRepository
import com.jiligulu.app.data.repository.ChatHistoryRepository
import com.jiligulu.app.data.repository.ConfirmItem
import com.jiligulu.app.domain.chat.PromptRenderer
import com.jiligulu.app.domain.category.CategoryEngine
import com.jiligulu.app.domain.persona.PersonaEngine
import com.jiligulu.app.domain.persona.QuipLibrary
import com.jiligulu.app.domain.time.BillTimeResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZoneId

sealed interface ChatItem {
    val id: Long
    data class UserMsg(override val id: Long, val text: String) : ChatItem
    data class GuluMsg(override val id: Long, val text: String, val loading: Boolean = false) : ChatItem
    data class DraftCard(
        override val id: Long,
        val rawInput: String,
        val drafts: List<DraftUi>,
        val status: Status = Status.EDITING,
        val savedCount: Int = 0
    ) : ChatItem {
        enum class Status { EDITING, SAVING, CONFIRMED, CANCELLED }
    }

    /**
     * 改账 / 删账确认卡。
     *
     * 和草稿卡分开是因为两者可编辑的自由度完全不同：草稿卡什么都能改，
     * 指令卡是「模型已经算好的一个变更」，用户只能勾选要不要执行。
     */
    data class CommandCard(
        override val id: Long,
        val kind: CommandKind,
        val params: List<CommandItem>,
        val status: Status = Status.EDITING,
        val appliedCount: Int = 0
    ) : ChatItem {
        /** 卡上还有没有没提交的条目。 */
        val pendingCount: Int get() = params.count { it.checked } - appliedCount
        enum class Status { EDITING, SAVING, DONE, CANCELLED }
    }
}

class ChatViewModel(
    private val aiRepository: AiRepository,
    categoryRepository: CategoryRepository,
    private val personaEngine: PersonaEngine,
    private val history: ChatHistoryRepository
) : ViewModel() {
    private val writes = Mutex()
    private var loadingHistory = false
    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.categories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当前挂起中的「待补充」账。不为空时输入栏上方会出现一条提示气泡。 */
    private val _pending = MutableStateFlow<PendingDraft?>(null)
    val pending: StateFlow<PendingDraft?> = _pending

    init { loadHistory() }

    fun loadHistory() {
        if (_ready.value || loadingHistory) return
        loadingHistory = true
        viewModelScope.launch {
            try {
                writes.withLock {
                    history.markPendingInterrupted()
                    _items.value = history.getAll().map { it.toUi() }
                    _pending.value = PromptRenderer.pendingOf(history.latestPending())
                    if (_items.value.isEmpty()) {
                        val name = aiRepository.nicknameWithSuffix()
                        appendReply("阿噜！${if (name.isBlank()) "" else "${name}，"}今天花了什么？补记也可以说「昨天中午吃饭 9 元」～")
                    }
                }
                _error.value = null
                _ready.value = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _error.value = "历史对话暂时没有读出来，请点击重试。"
            } finally {
                loadingHistory = false
            }
        }
    }

    fun send(text: String) {
        val input = text.trim()
        if (input.isBlank() || !_ready.value || _sending.value) return
        _sending.value = true
        _error.value = null
        val requestMillis = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        viewModelScope.launch {
            var pendingMsg: ChatMessageEntity? = null
            try {
                val carried = _pending.value
                writes.withLock {
                    val (user, message) = history.beginRequest(input, requestMillis)
                    append(user.toUi())
                    pendingMsg = message
                    append(pendingMsg!!.toUi())
                }
                val result = aiRepository.parse(input, requestMillis, zone)
                // 模型没连上时降级到本地规则；本地规则不会改账删账，也不会扯上下文。
                val parsed = result.getOrNull() ?: localParse(input, result.exceptionOrNull())
                val turn = if (result.isSuccess) {
                    aiRepository.toTurn(parsed, input, requestMillis, zone, carried)
                } else {
                    localTurn(parsed, input, requestMillis, zone)
                }
                writes.withLock { finishRequest(pendingMsg!!, input, turn, parsed.reply, requestMillis) }
            } catch (cancelled: CancellationException) {
                // Durable PENDING rows become INTERRUPTED on the next visit.
                throw cancelled
            } catch (_: Exception) {
                _error.value = "这次没有完成，请稍后重试；已有账单和历史仍会保留。"
                pendingMsg?.let { message ->
                    try {
                        writes.withLock {
                            val current = history.getById(message.id)
                            if (current?.status == "PENDING") {
                                val failed = current.copy(status = "INTERRUPTED", content = "这次整理中断了，可以重新发送。")
                                history.update(failed)
                                replace(failed.toUi())
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Recover next visit. */ }
                }
            } finally {
                _sending.value = false
            }
        }
    }

    /**
     * 降级到本地规则时的结论。
     *
     * 本地规则只能新增，不能改账删账——与其猜，不如老老实实记账。
     * 这里只把 AI 草稿翻译成草稿卡，不做任何动作分派：没有模型就没有理解，
     * 硬把用户的话当成改账指令是最危险的猜法。
     */
    private fun localTurn(parsed: AiParseResult, input: String, requestMillis: Long, zone: ZoneId): AiTurn {
        if (parsed.bills.isEmpty()) {
            // 离线时「5」这种话说不完整，本地也得挂起，不能提示「请分开说每笔账」。
            return if (PendingInputDetector.looksIncomplete(input)) {
                AiTurn.Pending(
                    reply = parsed.reply,
                    draft = PendingDraft(
                        amountText = PendingInputDetector.amountOf(input).orEmpty(),
                        rawInput = input,
                        createdAt = requestMillis
                    )
                )
            } else AiTurn.Chat(parsed.reply)
        }
        val drafts = parsed.bills.map { bill ->
            val expression = BillTimeResolver.expressionForBill(input, bill.detail, parsed.bills.size, bill.timeExpression)
            val time = BillTimeResolver.resolve(expression, bill.occurredAt, requestMillis, zone)
            ConfirmItem(
                amountText = if (bill.amountYuan.isFinite() && bill.amountYuan > 0)
                    java.math.BigDecimal.valueOf(bill.amountYuan).stripTrailingZeros().toPlainString() else "",
                type = if (bill.type.equals("INCOME", true)) BillType.INCOME else BillType.EXPENSE,
                categoryName = bill.category.ifBlank { "未分类" },
                isNewCategory = bill.isNewCategory,
                iconEmoji = bill.iconEmoji, iconSvg = bill.iconSvg, keywords = bill.keywords,
                detail = bill.detail, note = bill.note, checked = true,
                timestamp = time.timestamp, timeNeedsReview = time.needsReview, timeHint = time.hint
            )
        }
        return AiTurn.Drafts(parsed.reply, drafts)
    }

    private fun localParse(input: String, cause: Throwable? = null): AiParseResult {
        val drafts = LocalBillParser.parse(input).map { draft ->
            draft.copy(category = CategoryEngine.suggest(draft.detail, categories.value)?.name ?: "未分类")
        }
        return AiParseResult(bills = drafts, reply = fallbackReply(drafts.isEmpty(), cause))
    }

    // 降级文案的实现见 companion：纯函数，单测可直接断言「每种失败都有专属说法」。

    /**
     * 把一轮结论落成聊天流里的东西。
     *
     * 挂起账的清除放在这里统一做：只要这轮有了结论（草稿/指令/闲聊），
     * 上一轮的挂起就该退场——补全了、或者用户改聊别的了。
     */
    private suspend fun finishRequest(
        pendingMsg: ChatMessageEntity,
        input: String,
        turn: AiTurn,
        reply: String,
        requestMillis: Long
    ) {
        when (turn) {
            is AiTurn.Pending -> {
                // 追问本身就是模型给的 reply，直接原样显示，同时把挂起态落库。
                val text = turn.reply.ifBlank { "这笔多少钱是花在哪儿啦？阿噜先记着～" }
                val message = pendingMsg.copy(status = "", content = text)
                history.update(message)
                replace(message.toUi())
                history.suspendPending(
                    PromptRenderer.encodePending(turn.draft), requestMillis
                )
                _pending.value = turn.draft
                return
            }

            is AiTurn.Chat -> {
                val message = pendingMsg.copy(status = "", content = turn.reply.ifBlank { "阿噜在听，你说～" })
                history.update(message)
                replace(message.toUi())
                clearPending()
                return
            }

            is AiTurn.Commands -> {
                val payload = CommandCardCodec.encode(turn.kind, turn.items)
                val card = pendingMsg.copy(
                    kind = "COMMAND", content = "", rawInput = input,
                    draftPayload = payload, status = "EDITING"
                )
                history.update(card)
                replace(card.toUi())
                appendReply(turn.reply.ifBlank {
                    when (turn.kind) {
                        CommandKind.DELETE -> "这些账阿噜先收进回收站，确认一下～"
                        CommandKind.RESTORE -> "这些账阿噜从回收站捞回来，确认一下～"
                        else -> "这些要改的账，确认一下～"
                    }
                })
                clearPending()
                return
            }

            is AiTurn.Choices -> {
                // R4/R5：可点选项（(a)帮我恢复 /(b)自己去、页面跳转）。
                // T04 会把这里换成带按钮的 ActionCard 并接 onNavigate；
                // 在此之前先以文本回复落地，保证「指路」信息不丢，也不引入半成品 UI。
                val message = pendingMsg.copy(status = "", content = turn.reply.ifBlank { "阿噜给你指条路～" })
                history.update(message)
                replace(message.toUi())
                clearPending()
                return
            }

            is AiTurn.Drafts -> {
                if (turn.drafts.isEmpty()) {
                    val message = pendingMsg.copy(status = "", content = reply.ifBlank { "阿噜？还没找到这笔账的金额，再说具体一点吧～" })
                    history.update(message)
                    replace(message.toUi())
                    clearPending()
                    return
                }
                val card = pendingMsg.copy(
                    kind = "DRAFT", content = "", rawInput = input,
                    draftPayload = DraftHistoryCodec.encode(turn.drafts.map { it.toDraftUi() }),
                    status = "EDITING"
                )
                history.update(card)
                replace(card.toUi())
                appendReply(turn.reply.ifBlank { "草稿整理好了，确认一下就记入账本，阿噜～" })
                clearPending()
            }
        }
    }

    /** 挂起账离场。它已经完成使命（被补全 / 被放弃 / 被指令或闲聊取代）。 */
    private suspend fun clearPending() {
        if (_pending.value == null) return
        runCatching { history.clearPending() }
        _pending.value = null
    }

    fun updateDraft(cardId: Long, index: Int, transform: (DraftUi) -> DraftUi) {
        val card = findCard(cardId)?.takeIf { it.status == ChatItem.DraftCard.Status.EDITING } ?: return
        val updated = card.copy(drafts = card.drafts.mapIndexed { i, draft -> if (i == index) transform(draft) else draft })
        replace(updated)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try {
                    writes.withLock { history.updateDraft(cardId, DraftHistoryCodec.encode(updated.drafts)) }
                } catch (_: Exception) { _error.value = "这次草稿修改还未保存，确认入账前会再尝试保存。" }
            }
        }
    }

    fun confirmCard(cardId: Long) {
        val card = findCard(cardId)?.takeIf { it.status == ChatItem.DraftCard.Status.EDITING } ?: return
        val selected = card.drafts.filter { it.checked }
        if (selected.isEmpty() || selected.any { !it.isValid }) {
            _error.value = "请先核对所选账单的金额和时间。"
            return
        }
        // Synchronous guard closes the double-tap window before the coroutine starts.
        replace(card.copy(status = ChatItem.DraftCard.Status.SAVING))
        val confirmedAt = System.currentTimeMillis()
        val finalCard = card.copy(drafts = card.drafts.map {
            if (it.checked && it.timestamp == null) it.copy(timestamp = confirmedAt, timeHint = "按确认入账的时间记录") else it
        })
        _error.value = null
        viewModelScope.launch {
            try {
                val saved = writes.withLock {
                    aiRepository.confirm(cardId, finalCard.drafts.map { it.toConfirmItem() }, card.rawInput, DraftHistoryCodec.encode(finalCard.drafts))
                }
                replace(finalCard.copy(status = ChatItem.DraftCard.Status.CONFIRMED, savedCount = saved))
                writes.withLock {
                    appendReply("记好了，$saved 笔账已放进账本 ♡")
                    val name = aiRepository.nicknameWithSuffix()
                    personaEngine.nextSaveQuip(System.currentTimeMillis(), name)?.let { appendReply(it) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                // Reply failure must never re-enable a draft already committed to the ledger.
                val stored = try { history.getById(cardId) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                if (stored?.status == "CONFIRMED") replace(stored.toUi()) else {
                    replace(card)
                    try { history.updateDraft(cardId, DraftHistoryCodec.encode(card.drafts)) }
                    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Remains recoverable. */ }
                }
                _error.value = if (stored?.status == "CONFIRMED") "账单已保存，回应稍后再补。" else "暂时没能保存，请重试；不会重复记账。"
            }
        }
    }

    fun cancelCard(cardId: Long) {
        val card = findCard(cardId)?.takeIf { it.status == ChatItem.DraftCard.Status.EDITING } ?: return
        replace(card.copy(status = ChatItem.DraftCard.Status.CANCELLED))
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try { writes.withLock { history.dismissDraft(cardId) } }
                catch (_: Exception) { replace(card); _error.value = "取消没有保存成功，请重试。" }
            }
        }
    }

    // ---------- 改账 / 删账确认卡 ----------

    fun toggleCommandItem(cardId: Long, billId: Long) {
        val card = findCommandCard(cardId)?.takeIf { it.status == ChatItem.CommandCard.Status.EDITING } ?: return
        val updated = card.copy(params = card.params.map { if (it.billId == billId) it.copy(checked = !it.checked) else it })
        replace(updated)
        persistCommand(cardId, updated)
    }

    fun toggleCommandAll(cardId: Long) {
        val card = findCommandCard(cardId)?.takeIf { it.status == ChatItem.CommandCard.Status.EDITING } ?: return
        val selectAll = card.params.any { !it.checked }
        val updated = card.copy(params = card.params.map { it.copy(checked = selectAll) })
        replace(updated)
        persistCommand(cardId, updated)
    }

    fun confirmCommandCard(cardId: Long) {
        val card = findCommandCard(cardId)?.takeIf { it.status == ChatItem.CommandCard.Status.EDITING } ?: return
        if (card.pendingCount <= 0) return
        replace(card.copy(status = ChatItem.CommandCard.Status.SAVING))
        _error.value = null
        viewModelScope.launch {
            try {
                val applied = writes.withLock {
                    aiRepository.commitCommands(
                        cardId,
                        CommandCardPayload(
                            kind = card.kind.name,
                            items = card.params,
                            alreadyApplied = card.appliedCount
                        )
                    )
                }
                val stored = history.getById(cardId)
                val done = stored?.toUi()
                if (done is ChatItem.CommandCard) replace(done)
                else replace(card.copy(status = ChatItem.CommandCard.Status.DONE, appliedCount = card.appliedCount + applied))
                // 点确认时一条都没勾上，说明用户其实想反悔——直接当取消处理，别留一张悬着的卡。
                if (applied == 0 && card.params.none { it.checked }) {
                    replace(card.copy(status = ChatItem.CommandCard.Status.CANCELLED))
                    history.dismissDraft(cardId)
                    return@launch
                }
                writes.withLock {
                    appendReply(commandResultLine(card.kind, applied))
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                // 「已经改到数据库、但卡片状态没写进去」是最坏的情况：UI 以为什么都没发生，
                // 用户再点一次就会重复执行。所以失败时先在卡片上留一个记号。
                val stored = try { history.getById(cardId) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                if (stored?.status == "DISMISSED") {
                    replace(stored.toUi())
                } else {
                    runCatching { aiRepository.markCommandConfirmationFailed(cardId) }
                    history.getById(cardId)?.toUi()?.let { replace(it) }
                }
                _error.value = "这次没能改成功，请再试一次；不会改到一半。"
            }
        }
    }

    fun cancelCommandCard(cardId: Long) {
        val card = findCommandCard(cardId)?.takeIf { it.status == ChatItem.CommandCard.Status.EDITING } ?: return
        replace(card.copy(status = ChatItem.CommandCard.Status.CANCELLED))
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try { writes.withLock { history.dismissDraft(cardId) } }
                catch (_: Exception) { replace(card); _error.value = "取消没有保存成功，请重试。" }
            }
        }
    }

    /** 勾选状态要跟着卡片一起落库，否则退出再进来勾选全丢。 */
    private fun persistCommand(cardId: Long, card: ChatItem.CommandCard) {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try {
                    writes.withLock {
                        history.updateCard(
                            cardId,
                            "COMMAND",
                            CommandCardCodec.encode(
                                CommandCardPayload(
                                    kind = card.kind.name,
                                    items = card.params,
                                    alreadyApplied = card.appliedCount,
                                    confirmFailed = false
                                )
                            )
                        )
                    }
                } catch (_: Exception) { _error.value = "勾选状态没保存上，提交前请再核对一次。" }
            }
        }
    }

    private suspend fun appendReply(text: String) {
        val message = ChatMessageEntity(kind = "ASSISTANT", content = text)
        append(message.copy(id = history.insert(message)).toUi())
    }

    private fun ChatMessageEntity.toUi(): ChatItem = when (kind) {
        "USER" -> ChatItem.UserMsg(id, content)

        "DRAFT" -> try {
            ChatItem.DraftCard(id, rawInput, DraftHistoryCodec.decode(draftPayload), when (status) {
                "CONFIRMED" -> ChatItem.DraftCard.Status.CONFIRMED
                "DISMISSED" -> ChatItem.DraftCard.Status.CANCELLED
                else -> ChatItem.DraftCard.Status.EDITING
            }, savedCount)
        } catch (_: Exception) {
            ChatItem.GuluMsg(id, "这张旧草稿暂时无法展示，原始记录仍已保留。原话：$rawInput")
        }

        "COMMAND" -> {
            val payload = CommandCardCodec.decode(draftPayload)
            if (payload == null || payload.items.isEmpty()) {
                ChatItem.GuluMsg(id, "这张旧变更卡暂时无法展示。原话：$rawInput")
            } else {
                val kind = CommandKind.entries.firstOrNull { it.name.equals(payload.kind, true) }
                    ?: CommandKind.UPDATE
                val done = CommandCardCodec.pendingCount(payload) <= 0
                ChatItem.CommandCard(
                    id = id, kind = kind, params = payload.items,
                    status = if (done) ChatItem.CommandCard.Status.DONE else ChatItem.CommandCard.Status.EDITING,
                    appliedCount = payload.alreadyApplied
                )
            }
        }

        // 挂起记录不单独成条：它的提示已经由本轮追问气泡承担了。
        "PENDING_DRAFT" -> ChatItem.GuluMsg(id, "", loading = false)

        else -> ChatItem.GuluMsg(id, content, status == "PENDING")
    }

    private fun ConfirmItem.toDraftUi() = DraftUi(
        amountText = amountText, type = type, categoryName = categoryName,
        isNewCategory = isNewCategory, iconEmoji = iconEmoji, iconSvg = iconSvg,
        keywords = keywords, detail = detail, note = note, checked = checked,
        timestamp = timestamp, timeNeedsReview = timeNeedsReview,
        timeHint = timeHint.ifBlank { "未提及时间，确认入账时记录此刻" }
    )

    private fun DraftUi.toConfirmItem() = ConfirmItem(
        amountText, type, categoryName, isNewCategory, iconEmoji, iconSvg, keywords,
        detail, note, checked, timestamp, timeNeedsReview, timeHint
    )

    private fun findCard(id: Long) = _items.value.filterIsInstance<ChatItem.DraftCard>().find { it.id == id }
    private fun findCommandCard(id: Long) = _items.value.filterIsInstance<ChatItem.CommandCard>().find { it.id == id }
    private fun append(item: ChatItem) { _items.value = _items.value + item }
    private fun replace(item: ChatItem) { _items.value = _items.value.map { if (it.id == item.id) item else it } }
    private fun trimAmount(value: Double) = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    companion object {
        /**
         * 降级路径的措辞必须能指路。
         *
         * 历史教训：原先除「没配 Key」外一律说「连不上网」，于是余额不足（402）、
         * 内容被服务端拒（400）、审核拦截（空响应）全被报成网络问题，用户往完全错的方向排查。
         *
         * 现在每类原因各有说法，尤其这四种必须分开（用户能据此做不同的事）：
         * - 400：服务端拒了这条请求（内容不合适）→ 换个说法
         * - 空响应：内容审核拦截（finish_reason=content_filter）→ 换个话题
         * - [IOException]：真·网络问题 → 重发一次
         * - 其他 HTTP 码 → 如实报码，别推给网络
         *
         * 纯函数放 companion：单测直接断言「每种失败都有专属文案」。
         */
        internal fun fallbackReply(nothingParsed: Boolean, cause: Throwable?): String {
            val base = fallbackReplyText(nothingParsed, cause)
            // 调试期把真实异常贴出来：用户截图即可定位，不用再连手机抓日志。
            // 只在 debug 包生效，release 不会暴露内部细节。
            if (!BuildConfig.DEBUG || cause == null) return base
            val detail = "${cause.javaClass.simpleName}: ${cause.message?.take(160)}"
            return "$base\n（调试：$detail）"
        }

        private fun fallbackReplyText(nothingParsed: Boolean, cause: Throwable?): String {
            val keyProblem = cause?.message?.contains("API Key") == true
            val httpStatus = (cause as? DeepSeekHttpException)?.status
            return when {
                keyProblem && nothingParsed ->
                    "还没有配置 API Key，先到「设置 → AI 服务」里填一个，阿噜～"
                keyProblem ->
                    "先用本地规则整理好了，请核对后再确认入账（配置 API Key 后可让叽里咕噜帮你拆得更细）～"
                httpStatus == 402 ->
                    "叽里咕噜的账户余额不够啦，先去 DeepSeek 平台充值，再回来找我记账～"
                httpStatus == 401 ->
                    "这个 API Key 好像失效了，到「设置 → AI 服务」里换一个新的吧。"
                httpStatus == 429 ->
                    "请求太频繁了，缓一会儿再跟我说～"
                httpStatus == 400 ->
                    "这句话阿噜接不住，换个说法嘛～ 记账、唠嗑、问功能，阿噜都在行 ♡"
                httpStatus != null && httpStatus >= 500 ->
                    "DeepSeek 那边暂时有点忙，稍后再试一次。"
                httpStatus != null ->
                    "阿噜这边出了点小状况（DeepSeek 返回 $httpStatus），稍后再试试～"
                cause is DeepSeekEmptyResponseException ->
                    "这个阿噜变不出来呀，换个话题嘛 ♡ 记账、唠嗑、问功能都行～"
                cause is IOException ->
                    "网络好像不太稳，阿噜没接上话，你再说一遍试试～"
                nothingParsed ->
                    "阿噜这句没接住。记账的话一句一笔最稳（如「昨天中午吃饭 9 元」），再试一次也行～"
                else ->
                    "先用本地规则整理好了，请核对金额、名称和时间，再确认入账，阿噜～"
            }
        }

        /**
         * 一次指令提交后追加给用户的结论行（R6：状态变化只能靠**追加消息**表达，永不回改历史）。
         *
         * 纯函数、放 companion：每种动作都必须有自己的那句（尤其 RESTORE 不能落进「改好了」），
         * 单测直接断言文案归属，不必构造整个 ViewModel。
         */
        internal fun commandResultLine(kind: CommandKind, applied: Int): String = when {
            applied <= 0 -> "这次没有改动任何账单。"
            kind == CommandKind.DELETE -> "收好了，$applied 笔账进了回收站，反悔了随时捞回来 ♡"
            kind == CommandKind.RESTORE -> "捞回来了，$applied 笔账回到账本啦 ♡"
            else -> "改好了，$applied 笔账已更新 ♡"
        }

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JiliguluApp
                ChatViewModel(app.container.aiRepository, app.container.categoryRepository, PersonaEngine(QuipLibrary.get(app)), app.container.chatHistoryRepository)
            }
        }
    }
}
