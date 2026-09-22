package com.jiligulu.app.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.data.repository.BillRepository
import com.jiligulu.app.data.repository.CategoryAdminRepository
import com.jiligulu.app.data.repository.CategoryDeletionResult
import com.jiligulu.app.data.repository.CategoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AddBillSaveState(
    val isSaving: Boolean = false,
    val error: String? = null
)

class AddBillViewModel(
    private val billRepository: BillRepository,
    categoryRepository: CategoryRepository,
    private val categoryAdminRepository: CategoryAdminRepository
) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.categories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _saveState = MutableStateFlow(AddBillSaveState())
    val saveState = _saveState.asStateFlow()

    private val savedEvents = Channel<Unit>(Channel.BUFFERED)
    val saved = savedEvents.receiveAsFlow()

    /**
     * 该分类下的活账单条数。
     *
     * 删除弹窗要如实告诉用户「会挪走几笔」——先查数再问，而不是让用户自己数。
     * 只数活账单（回收站里的不计入、也不改归属）。
     */
    suspend fun liveBillCount(categoryId: Long): Int = billRepository.countLiveByCategory(categoryId)

    /**
     * 删除分类：活账单整体转挂「待定」+ 删分类行，一个事务。
     *
     * 返回 sealed 而不是布尔：[CategoryDeletionResult.Refused]（如收纳箱不可删）与
     * [CategoryDeletionResult.Deleted]（附带挪走条数，用于文案）要给用户不同反馈。
     */
    suspend fun deleteCategory(categoryId: Long): CategoryDeletionResult =
        categoryAdminRepository.deleteCategoryAndReassign(categoryId)

    fun save(
        amountFen: Long,
        type: BillType,
        categoryId: Long,
        detail: String,
        note: String,
        timestamp: Long? = null
    ) {
        val previous = _saveState.value
        if (previous.isSaving) return
        if (amountFen <= 0 || categoryId <= 0) {
            _saveState.value = AddBillSaveState(error = "请填写有效金额并选择分类。")
            return
        }
        // Claim the save synchronously, before launching: fast repeated taps cannot insert twice.
        if (!_saveState.compareAndSet(previous, AddBillSaveState(isSaving = true))) return
        viewModelScope.launch {
            try {
                billRepository.addManual(
                    amountFen = amountFen,
                    type = type,
                    categoryId = categoryId,
                    detail = detail,
                    note = note,
                    timestamp = timestamp ?: System.currentTimeMillis()
                )
                // Stay locked until navigation completes; the event survives a screen recreation.
                savedEvents.send(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _saveState.value = AddBillSaveState(error = "保存失败，账单尚未保存，请重试。")
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JiliguluApp
                AddBillViewModel(
                    app.container.billRepository,
                    app.container.categoryRepository,
                    app.container.categoryAdminRepository
                )
            }
        }
    }
}
