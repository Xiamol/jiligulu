package com.jiligulu.app.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.local.entity.ChatMessageEntity
import com.jiligulu.app.data.repository.BillRepository
import com.jiligulu.app.data.repository.CategoryRepository
import com.jiligulu.app.data.repository.ChatHistoryRepository
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.domain.category.CategoryLabels
import com.jiligulu.app.ui.chat.DraftHistoryCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 回收站的两个页签：已删账单（可恢复）与草稿管理（可批量删掉）。 */
enum class TrashTab { BILLS, DRAFTS }

/** 回收站里的一张账单卡。 */
data class TrashItemUi(
    val id: Long,
    val icon: String,
    val title: String,
    val subtitle: String,
    val amountText: String,
    val isExpense: Boolean,
    val deletedAt: Long
)

/** 草稿页签里的一张草稿卡。 */
data class TrashDraftUi(
    val id: Long,
    val title: String,
    val summary: String,
    val rawInput: String,
    val createdAt: Long
)

data class TrashUiState(
    val isLoading: Boolean = true,
    val tab: TrashTab = TrashTab.BILLS,
    val items: List<TrashItemUi> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val draftItems: List<TrashDraftUi> = emptyList(),
    val selectedDrafts: Set<Long> = emptySet(),
    val isWorking: Boolean = false,
    val retentionDays: Int = UserPrefs.DEFAULT_TRASH_RETENTION_DAYS,
    val message: String? = null,
    val error: String? = null
) {
    /** 当前页签下已勾选的 id —— 两个页签各管各的选择，切换时互不影响。 */
    val activeSelection: Set<Long> get() = if (tab == TrashTab.BILLS) selected else selectedDrafts

    val hasSelection: Boolean get() = activeSelection.isNotEmpty()

    val allSelected: Boolean get() = when (tab) {
        TrashTab.BILLS -> items.isNotEmpty() && selected.size == items.size
        TrashTab.DRAFTS -> draftItems.isNotEmpty() && selectedDrafts.size == draftItems.size
    }

    /** 当前页签的条目数（页签角标用）。 */
    val activeCount: Int get() = if (tab == TrashTab.BILLS) items.size else draftItems.size
}

/**
 * 回收站：软删除账单的恢复/彻底清除，以及草稿的查看与批量删除。
 *
 * 账单侧的所有批量操作都只碰 `deletedAt IS NOT NULL` 的记录（DAO 层用 SQL 条件锁死），
 * 所以这里不存在「误把活账单删掉」的路径。
 *
 * 草稿侧列出的是**活跃草稿**（EDITING / DISMISSED）——即「我攒了哪些还没入账的草稿」。
 * 删掉它们只是打 `DELETED` 标记（聊天流里那条卡会灰化），不是物理删除；
 * 同时追加一条 assistant 消息，让聊天流如实反映「这些草稿没了」（历史冻结：只追加、不回改）。
 */
class TrashViewModel(
    private val billRepository: BillRepository,
    categoryRepository: CategoryRepository,
    private val prefs: UserPrefs,
    private val history: ChatHistoryRepository
) : ViewModel() {
    private val _ui = MutableStateFlow(TrashUiState())
    val uiState: StateFlow<TrashUiState> = _ui.asStateFlow()

    private val categories: StateFlow<List<CategoryEntity>> = categoryRepository.categories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            try {
                combine(billRepository.observeTrash(), categories, prefs.trashRetentionDays) { bills, cats, days ->
                    Triple(bills, cats, days)
                }.collect { (bills, cats, days) ->
                    val catMap = cats.associateBy { it.id }
                    val items = bills.map { it.toUi(catMap[it.categoryId]) }
                    _ui.update { state ->
                        state.copy(
                            isLoading = false,
                            items = items,
                            // 只保留仍然存在的选中项，避免列表变化后残留幽灵 id
                            selected = state.selected.intersect(items.map { it.id }.toSet()),
                            retentionDays = days
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _ui.update { it.copy(isLoading = false, error = "回收站读取失败，请重试。") }
            }
        }

        viewModelScope.launch {
            try {
                history.observeActiveDrafts().collect { drafts ->
                    val items = drafts.map { it.toDraftUi() }
                    _ui.update { state ->
                        state.copy(
                            isLoading = false,
                            draftItems = items,
                            selectedDrafts = state.selectedDrafts.intersect(items.map { it.id }.toSet())
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _ui.update { it.copy(isLoading = false, error = "草稿读取失败，请重试。") }
            }
        }
    }

    fun selectTab(tab: TrashTab) {
        if (_ui.value.isWorking || _ui.value.tab == tab) return
        _ui.update { it.copy(tab = tab, message = null, error = null) }
    }

    fun toggle(id: Long) {
        if (_ui.value.isWorking) return
        _ui.update { state ->
            if (state.tab == TrashTab.DRAFTS) {
                val next = if (id in state.selectedDrafts) state.selectedDrafts - id else state.selectedDrafts + id
                state.copy(selectedDrafts = next, message = null, error = null)
            } else {
                val next = if (id in state.selected) state.selected - id else state.selected + id
                state.copy(selected = next, message = null, error = null)
            }
        }
    }

    fun toggleAll() {
        if (_ui.value.isWorking) return
        _ui.update { state ->
            if (state.tab == TrashTab.DRAFTS) {
                val next = if (state.allSelected) emptySet() else state.draftItems.map { it.id }.toSet()
                state.copy(selectedDrafts = next, message = null, error = null)
            } else {
                val next = if (state.allSelected) emptySet() else state.items.map { it.id }.toSet()
                state.copy(selected = next, message = null, error = null)
            }
        }
    }

    fun clearSelection() {
        if (_ui.value.isWorking) return
        _ui.update {
            if (it.tab == TrashTab.DRAFTS) {
                it.copy(selectedDrafts = emptySet(), message = null, error = null)
            } else {
                it.copy(selected = emptySet(), message = null, error = null)
            }
        }
    }

    /** 恢复所选账单。 */
    fun restoreSelected() = runBatch(
        emptyMessage = "先勾选要恢复的账单呀"
    ) { ids ->
        val count = billRepository.restore(ids)
        "已恢复 $count 笔账单 ♡"
    }

    /** 彻底删除所选账单（不可恢复，仅限已在回收站里的）。 */
    fun purgeSelected() = runBatch(
        emptyMessage = "先勾选要彻底删掉的账单呀"
    ) { ids ->
        ids.forEach { billRepository.purge(it) }
        "已彻底删除 ${ids.size} 笔账单"
    }

    /** 删掉所选草稿（打 DELETED 标记），并追加一条消息让聊天流如实反映。 */
    fun deleteSelectedDrafts() = runBatch(
        emptyMessage = "先勾选要删掉的草稿呀"
    ) { ids ->
        val deleted = ids.count { history.deleteDraft(it) > 0 }
        if (deleted > 0) {
            history.insert(
                ChatMessageEntity(
                    kind = "ASSISTANT",
                    content = "在回收站清掉了 $deleted 张草稿，账本没受影响 ♡",
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        "已删掉 $deleted 张草稿"
    }

    fun setRetentionDays(days: Int) {
        if (_ui.value.isWorking) return
        viewModelScope.launch {
            try {
                prefs.setTrashRetentionDays(days)
                _ui.update { it.copy(message = "已经按「${retentionLabel(days)}」自动清理啦") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _ui.update { it.copy(error = "保留期没保存成功，请重试。") }
            }
        }
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    /**
     * 批量操作：勾选项取自**当前页签**（[TrashUiState.activeSelection]），
     * 不用 `selected` 单字段——否则在草稿页点「恢复」会去恢复一笔根本没勾的账单。
     */
    private fun runBatch(emptyMessage: String, action: suspend (List<Long>) -> String) {
        val state = _ui.value
        if (state.isWorking) return
        val ids = state.activeSelection.toList()
        if (ids.isEmpty()) {
            _ui.update { it.copy(error = emptyMessage) }
            return
        }
        _ui.update { it.copy(isWorking = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val message = action(ids)
                _ui.update { s ->
                    if (s.tab == TrashTab.DRAFTS) {
                        s.copy(isWorking = false, selectedDrafts = emptySet(), message = message)
                    } else {
                        s.copy(isWorking = false, selected = emptySet(), message = message)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _ui.update { it.copy(isWorking = false, error = "操作没有成功，请重试。") }
            }
        }
    }

    private fun BillEntity.toUi(category: CategoryEntity?) = TrashItemUi(
        id = id,
        icon = category?.iconValue?.ifBlank { "🧾" } ?: "🧾",
        title = detail.ifBlank { CategoryLabels.displayName(category?.name.orEmpty()) },
        subtitle = CategoryLabels.displayName(category?.name.orEmpty()) + " · " +
            Formatters.timeLabel(timestamp) +
            (if (note.isNotBlank()) " · $note" else ""),
        amountText = (if (type == BillType.EXPENSE) "-" else "+") + Formatters.fenToYuanText(amountFen),
        isExpense = type == BillType.EXPENSE,
        deletedAt = deletedAt ?: 0L
    )

    private fun ChatMessageEntity.toDraftUi(): TrashDraftUi {
        // 载荷解码失败不该让整页崩：拿不到细则时退化成「一张读不出内容的草稿」。
        val drafts = runCatching { DraftHistoryCodec.decode(draftPayload) }.getOrNull().orEmpty()
        val totalFen = drafts.sumOf { Formatters.yuanTextToFen(it.amountText) ?: 0L }
        val first = drafts.firstOrNull()
        return TrashDraftUi(
            id = id,
            title = first?.detail?.ifBlank { first.categoryName } ?: "未命名的草稿",
            summary = if (drafts.isEmpty()) {
                "读不出内容了"
            } else {
                "${drafts.size} 笔 · ¥${Formatters.fenToYuanText(totalFen)}"
            },
            rawInput = rawInput,
            createdAt = createdAt
        )
    }

    companion object {
        /** 保留期选项：0 表示永不自动清除。 */
        val RETENTION_OPTIONS = listOf(7, 14, 30, 90, UserPrefs.TRASH_RETENTION_FOREVER)

        fun retentionLabel(days: Int): String =
            if (days <= 0) "永不自动清除" else "$days 天"

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JiliguluApp
                TrashViewModel(
                    app.container.billRepository,
                    app.container.categoryRepository,
                    app.container.userPrefs,
                    app.container.chatHistoryRepository
                )
            }
        }
    }
}
