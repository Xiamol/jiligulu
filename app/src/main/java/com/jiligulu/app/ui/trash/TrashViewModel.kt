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
import com.jiligulu.app.data.repository.BillRepository
import com.jiligulu.app.data.repository.CategoryRepository
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.domain.category.CategoryLabels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

data class TrashUiState(
    val isLoading: Boolean = true,
    val items: List<TrashItemUi> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val isWorking: Boolean = false,
    val retentionDays: Int = UserPrefs.DEFAULT_TRASH_RETENTION_DAYS,
    val message: String? = null,
    val error: String? = null
) {
    val hasSelection: Boolean get() = selected.isNotEmpty()
    val allSelected: Boolean get() = items.isNotEmpty() && selected.size == items.size
}

/**
 * 回收站：软删除账单的恢复与彻底清除。
 *
 * 所有批量操作都只碰 `deletedAt IS NOT NULL` 的记录（DAO 层已用 SQL 条件锁死），
 * 所以这里不存在「误把活账单删掉」的路径。
 */
class TrashViewModel(
    private val billRepository: BillRepository,
    categoryRepository: CategoryRepository,
    private val prefs: UserPrefs
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
    }

    fun toggle(id: Long) {
        if (_ui.value.isWorking) return
        _ui.update { state ->
            val next = if (id in state.selected) state.selected - id else state.selected + id
            state.copy(selected = next, message = null, error = null)
        }
    }

    fun toggleAll() {
        if (_ui.value.isWorking) return
        _ui.update { state ->
            val next = if (state.allSelected) emptySet() else state.items.map { it.id }.toSet()
            state.copy(selected = next, message = null, error = null)
        }
    }

    fun clearSelection() {
        if (_ui.value.isWorking) return
        _ui.update { it.copy(selected = emptySet(), message = null, error = null) }
    }

    /** 恢复所选。 */
    fun restoreSelected() = runBatch(
        emptyMessage = "先勾选要恢复的账单呀"
    ) { ids ->
        val count = billRepository.restore(ids)
        "已恢复 $count 笔账单 ♡"
    }

    /** 彻底删除所选（不可恢复，仅限已在回收站里的）。 */
    fun purgeSelected() = runBatch(
        emptyMessage = "先勾选要彻底删掉的账单呀"
    ) { ids ->
        ids.forEach { billRepository.purge(it) }
        "已彻底删除 ${ids.size} 笔账单"
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

    private fun runBatch(emptyMessage: String, action: suspend (List<Long>) -> String) {
        val state = _ui.value
        if (state.isWorking) return
        val ids = state.selected.toList()
        if (ids.isEmpty()) {
            _ui.update { it.copy(error = emptyMessage) }
            return
        }
        _ui.update { it.copy(isWorking = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val message = action(ids)
                _ui.update { it.copy(isWorking = false, selected = emptySet(), message = message) }
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
                    app.container.userPrefs
                )
            }
        }
    }
}
