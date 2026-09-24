package com.jiligulu.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.repository.BillRepository
import com.jiligulu.app.data.repository.CategoryRepository
import com.jiligulu.app.domain.category.CategoryLabels
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BillUi(
    val id: Long,
    val icon: String,
    val colorHue: Float,
    val colorIndex: Int,
    val title: String,
    val subtitle: String,
    val amountText: String,
    val isExpense: Boolean,
    val entity: BillEntity,
    val categoryName: String = "未分类"
)

data class DayGroupUi(
    val dayLabel: String,
    val dayExpenseText: String,
    val bills: List<BillUi>
)

data class HomeUiState(
    val isLoaded: Boolean = false,
    val monthLabel: String = "",
    val expenseText: String = "0",
    val incomeText: String = "0",
    val balanceText: String = "0",
    val expenseCount: Int = 0,
    val incomeCount: Int = 0,
    val days: List<DayGroupUi> = emptyList()
)

data class DailyLedgerSnapshot(val day: Long, val bills: List<BillUi> = emptyList(), val loaded: Boolean = false)

internal fun filterHomeBills(bills: List<BillUi>, day: Long, type: Int, sort: Int): List<BillUi> =
    bills.filter { Formatters.dayStart(it.entity.timestamp) == day && (type == 0 || it.isExpense == (type == 1)) }
        .sortedWith(when (sort) {
            1 -> compareBy { it.entity.timestamp }
            2 -> compareByDescending { it.entity.amountFen }
            3 -> compareBy { it.entity.amountFen }
            else -> compareByDescending { it.entity.timestamp }
        })

class HomeViewModel(
    private val billRepository: BillRepository,
    categoryRepository: CategoryRepository
) : ViewModel() {

    private val _selectedDay = MutableStateFlow(Formatters.dayStart(System.currentTimeMillis()))
    val selectedDay: StateFlow<Long> = _selectedDay
    fun selectDay(day: Long) { _selectedDay.value = Formatters.dayStart(day).coerceAtMost(Formatters.dayStart(System.currentTimeMillis())) }
    fun showToday() = selectDay(System.currentTimeMillis())
    @OptIn(ExperimentalCoroutinesApi::class)
    val dailyLedger: StateFlow<DailyLedgerSnapshot> = _selectedDay.flatMapLatest { day ->
        billRepository.observeBetween(day, com.jiligulu.app.ui.components.shiftLocalDay(day, 1))
            .combine(categoryRepository.categories) { bills, categories ->
                val map = categories.associateBy { it.id }
                DailyLedgerSnapshot(day, bills.map { it.toUi(map[it.categoryId]) }, true)
            }.onStart { emit(DailyLedgerSnapshot(day)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DailyLedgerSnapshot(_selectedDay.value))
    val dailyBills: StateFlow<List<BillUi>> = dailyLedger.map { it.bills }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<HomeUiState> =
        combine(
            billRepository.observeCurrentMonth(),
            categoryRepository.categories
        ) { bills, categories ->
            val catMap = categories.associateBy { it.id }
            var expense = 0L
            var income = 0L
            var expenseCount = 0
            var incomeCount = 0
            bills.forEach {
                if (it.type == BillType.EXPENSE) {
                    expense += it.amountFen
                    expenseCount++
                } else {
                    income += it.amountFen
                    incomeCount++
                }
            }
            val days = bills.groupBy { Formatters.dayStart(it.timestamp) }
                .toSortedMap(compareByDescending { it })
                .map { (dayStart, dayBills) ->
                    val dayExpense = dayBills.filter { it.type == BillType.EXPENSE }.sumOf { it.amountFen }
                    DayGroupUi(
                        dayLabel = Formatters.dayLabel(dayStart),
                        dayExpenseText = Formatters.fenToYuanText(dayExpense),
                        bills = dayBills.map { it.toUi(catMap[it.categoryId]) }
                    )
                }
            HomeUiState(
                isLoaded = true,
                monthLabel = Formatters.monthLabel(System.currentTimeMillis()),
                expenseText = Formatters.fenToYuanText(expense),
                incomeText = Formatters.fenToYuanText(income),
                balanceText = Formatters.fenToYuanText(income - expense),
                expenseCount = expenseCount,
                incomeCount = incomeCount,
                days = days
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    /** 删除 = 移入回收站（软删除），保留期内可在回收站恢复。 */
    fun deleteBill(bill: BillEntity) {
        viewModelScope.launch { billRepository.moveToTrash(bill.id) }
    }

    private fun BillEntity.toUi(category: com.jiligulu.app.data.local.entity.CategoryEntity?): BillUi {
        val isExpense = type == BillType.EXPENSE
        val sign = if (isExpense) "-" else "+"
        return BillUi(
            id = id,
            icon = category?.iconValue.orEmpty(),
            categoryName = CategoryLabels.displayName(category?.name.orEmpty()),
            colorHue = category?.colorHue ?: 0f,
            colorIndex = category?.colorIndex ?: 0,
            title = detail.ifBlank { CategoryLabels.displayName(category?.name.orEmpty()) },
            subtitle = CategoryLabels.displayName(category?.name.orEmpty()) + " · " + Formatters.timeLabel(timestamp) +
                (if (note.isNotBlank()) " · $note" else ""),
            amountText = sign + Formatters.fenToYuanText(amountFen),
            isExpense = isExpense,
            entity = this
        )
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JiliguluApp
                HomeViewModel(app.container.billRepository, app.container.categoryRepository)
            }
        }
    }
}
