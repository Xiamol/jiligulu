package com.jiligulu.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.ai.AiParseResult
import com.jiligulu.app.core.ai.LocalBillParser
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.data.repository.AiRepository
import com.jiligulu.app.data.repository.CategoryRepository
import com.jiligulu.app.data.repository.ChatHistoryRepository
import com.jiligulu.app.data.repository.ConfirmItem
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
    ) : ChatItem
    enum class Status { EDITING, SAVING, CONFIRMED, CANCELLED }
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

    init { loadHistory() }

    fun loadHistory() {
        if (_ready.value || loadingHistory) return
        loadingHistory = true
        viewModelScope.launch {
            try {
                writes.withLock {
                    history.markPendingInterrupted()
                    _items.value = history.getAll().map { it.toUi() }
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
            var pending: ChatMessageEntity? = null
            try {
                writes.withLock {
                    val (user, message) = history.beginRequest(input, requestMillis)
                    append(user.toUi())
                    pending = message
                    append(pending!!.toUi())
                }
                val result = aiRepository.parse(input, requestMillis, zone)
                val parsed = result.getOrNull() ?: localParse(input)
                writes.withLock { finishRequest(pending!!, input, parsed, requestMillis, zone) }
            } catch (cancelled: CancellationException) {
                // Durable PENDING rows become INTERRUPTED on the next visit.
                throw cancelled
            } catch (_: Exception) {
                _error.value = "这次没有完成，请稍后重试；已有账单和历史仍会保留。"
                pending?.let { message ->
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

    private fun localParse(input: String): AiParseResult {
        val drafts = LocalBillParser.parse(input).map { draft ->
            draft.copy(category = CategoryEngine.suggest(draft.detail, categories.value)?.name ?: "未分类")
        }
        return AiParseResult(
            bills = drafts,
            reply = if (drafts.isEmpty()) "暂时连不上网络。请分开说每笔账，例如「昨天中午吃饭 9 元」，我会先用本地规则整理。"
                else "先用本地规则整理好了，请核对金额、名称和时间，再确认入账，阿噜～"
        )
    }

    private suspend fun finishRequest(
        pending: ChatMessageEntity,
        input: String,
        parsed: AiParseResult,
        requestMillis: Long,
        zone: ZoneId
    ) {
        if (parsed.bills.isEmpty()) {
            val reply = pending.copy(status = "", content = parsed.reply.ifBlank { "阿噜？还没找到这笔账的金额，再说具体一点吧～" })
            history.update(reply)
            replace(reply.toUi())
            return
        }
        val drafts = parsed.bills.map { bill ->
            val expression = BillTimeResolver.expressionForBill(input, bill.detail, parsed.bills.size, bill.timeExpression)
            val time = BillTimeResolver.resolve(expression, bill.occurredAt, requestMillis, zone)
            DraftUi(
                amountText = if (bill.amountYuan.isFinite() && bill.amountYuan > 0) trimAmount(bill.amountYuan) else "",
                type = if (bill.type.equals("INCOME", true)) BillType.INCOME else BillType.EXPENSE,
                categoryName = bill.category.ifBlank { "未分类" },
                isNewCategory = bill.isNewCategory,
                iconEmoji = bill.iconEmoji,
                iconSvg = bill.iconSvg,
                keywords = bill.keywords,
                detail = bill.detail,
                note = bill.note,
                timestamp = time.timestamp,
                timeNeedsReview = time.needsReview,
                timeHint = time.hint
            )
        }
        val card = pending.copy(kind = "DRAFT", content = "", rawInput = input, draftPayload = DraftHistoryCodec.encode(drafts), status = "EDITING")
        history.update(card)
        replace(card.toUi())
        appendReply(parsed.reply.ifBlank { "草稿整理好了，确认一下就记入账本，阿噜～" })
    }

    fun updateDraft(cardId: Long, index: Int, transform: (DraftUi) -> DraftUi) {
        val card = findCard(cardId)?.takeIf { it.status == ChatItem.Status.EDITING } ?: return
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
        val card = findCard(cardId)?.takeIf { it.status == ChatItem.Status.EDITING } ?: return
        val selected = card.drafts.filter { it.checked }
        if (selected.isEmpty() || selected.any { !it.isValid }) {
            _error.value = "请先核对所选账单的金额和时间。"
            return
        }
        // Synchronous guard closes the double-tap window before the coroutine starts.
        replace(card.copy(status = ChatItem.Status.SAVING))
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
                replace(finalCard.copy(status = ChatItem.Status.CONFIRMED, savedCount = saved))
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
        val card = findCard(cardId)?.takeIf { it.status == ChatItem.Status.EDITING } ?: return
        replace(card.copy(status = ChatItem.Status.CANCELLED))
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try { writes.withLock { history.dismissDraft(cardId) } }
                catch (_: Exception) { replace(card); _error.value = "取消没有保存成功，请重试。" }
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
                "CONFIRMED" -> ChatItem.Status.CONFIRMED
                "DISMISSED" -> ChatItem.Status.CANCELLED
                else -> ChatItem.Status.EDITING
            }, savedCount)
        } catch (_: Exception) {
            ChatItem.GuluMsg(id, "这张旧草稿暂时无法展示，原始记录仍已保留。原话：$rawInput")
        }
        else -> ChatItem.GuluMsg(id, content, status == "PENDING")
    }

    private fun DraftUi.toConfirmItem() = ConfirmItem(amountText, type, categoryName, isNewCategory, iconEmoji, iconSvg, keywords, detail, note, checked, timestamp)
    private fun findCard(id: Long) = _items.value.filterIsInstance<ChatItem.DraftCard>().find { it.id == id }
    private fun append(item: ChatItem) { _items.value = _items.value + item }
    private fun replace(item: ChatItem) { _items.value = _items.value.map { if (it.id == item.id) item else it } }
    private fun trimAmount(value: Double) = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JiliguluApp
                ChatViewModel(app.container.aiRepository, app.container.categoryRepository, PersonaEngine(QuipLibrary.get(app)), app.container.chatHistoryRepository)
            }
        }
    }
}
