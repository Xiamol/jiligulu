package com.jiligulu.app.ui.billdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.repository.BillRepository
import com.jiligulu.app.data.repository.CategoryRepository
import com.jiligulu.app.domain.category.CategoryLabels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal

data class BillDetailState(
    val isLoading: Boolean = true,
    val bill: BillEntity? = null,
    val categoryName: String = "未分类",
    val icon: String = "🧾",
    val isSaving: Boolean = false,
    val isComplete: Boolean = false,
    val error: String? = null
)

/** A sheet-scoped ViewModel; every write targets the selected row and is guarded against double taps. */
class BillDetailViewModel(
    private val billId: Long,
    private val repository: BillRepository,
    categories: CategoryRepository
) : ViewModel() {
    private val _state = MutableStateFlow(BillDetailState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                combine(repository.observeById(billId), categories.categories) { bill, allCategories ->
                    bill to allCategories.firstOrNull { it.id == bill?.categoryId }
                }.collect { (bill, category) ->
                    _state.update { it.copy(isLoading = false, bill = bill,
                        categoryName = CategoryLabels.displayName(category?.name ?: "未分类"),
                        icon = category?.iconValue?.ifBlank { "🧾" } ?: "🧾") }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(isLoading = false, error = "账单读取失败，请关闭后重试。") }
            }
        }
    }

    fun save(amountText: String, detail: String, timestamp: Long) {
        if (_state.value.isSaving || _state.value.isComplete || _state.value.bill == null) return
        val amountFen = parseBillAmount(amountText)
        if (amountFen == null || detail.trim().isEmpty()) {
            _state.update { it.copy(error = if (amountFen == null) "请输入大于 0 的金额，最多两位小数。" else "请填写账单名称。") }
            return
        }
        write { repository.updateDetails(billId, amountFen, detail, timestamp) }
    }

    fun delete() {
        if (_state.value.isSaving || _state.value.isComplete || _state.value.bill == null) return
        write { repository.deleteById(billId) }
    }

    private fun write(action: suspend () -> Unit) {
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                action()
                _state.update { it.copy(isSaving = false, isComplete = true) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(isSaving = false, error = "未能保存更改，请重试。") }
            }
        }
    }
}

internal fun parseBillAmount(text: String): Long? {
    val value = text.trim()
    if (!Regex("\\d+(?:\\.\\d{1,2})?").matches(value)) return null
    return runCatching { BigDecimal(value).movePointRight(2).longValueExact() }
        .getOrNull()?.takeIf { it > 0 }
}
