package com.jiligulu.app.ui.stats

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.BudgetPeriod
import com.jiligulu.app.data.repository.BillRepository
import com.jiligulu.app.data.repository.BudgetRepository
import com.jiligulu.app.data.repository.CategoryRepository
import com.jiligulu.app.domain.budget.BudgetStatus
import com.jiligulu.app.domain.category.CategoryLabels
import com.jiligulu.app.domain.color.GoldenAnglePalette
import com.jiligulu.app.ui.stats.charts.DayBar
import com.jiligulu.app.ui.stats.charts.DonutSlice
import com.jiligulu.app.ui.theme.BudgetRemainGreen
import com.jiligulu.app.ui.theme.DangerRed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 明细排序（PRD：时间/金额升降序） */
enum class DetailSort { TIME_DESC, TIME_ASC, AMOUNT_DESC, AMOUNT_ASC }

/** 选中日的明细行 */
data class DayDetailUi(
    val id: Long,
    val icon: String,
    val colorHue: Float,
    val categoryName: String,
    val detail: String,
    val timeLabel: String,
    val amountText: String,
    val isExpense: Boolean
)

/** 今日瓜分卡片状态 */
data class DayDonutUi(
    val dayStartMillis: Long = 0L,
    val type: BillType = BillType.EXPENSE,
    val dayLabel: String = "",
    val slices: List<DonutSlice> = emptyList(),       // 已按 PRD 合并「其他」(≤7 片)
    val totalText: String = "0",
    val selectedCategoryId: Long? = null,
    val selectedLabel: String = "",
    val selectedAmountText: String = ""
)

/** 余粮环卡片状态 */
data class BudgetUi(
    val usedPercentText: String = "0%",
    val visible: Boolean = false,                      // 未设预算 → 隐藏（PRD）
    val spentText: String = "",
    val totalText: String = "",
    val remainText: String = "",
    val usedSlices: List<DonutSlice> = emptyList(),    // 已用 / 剩余 两段
    val overspendPercentText: String = "",             // "超支 32%"，未超支为空
    val periodLabel: String = ""
)

/** 明细流内部用的四元组（combine 最多 5 参，拆成两段避免超限） */
private data class Quad(
    val bills: List<BillEntity>,
    val day: Long,
    val catId: Long?,
    val sort: DetailSort
)

/** The aggregate is not a real category, even if a user has named one 「其余」. */
internal fun mergedCategoryLabel(categoryNames: Set<String>): String {
    if ("其余" !in categoryNames) return "其余"
    val base = "其余（合并）"
    if (base !in categoryNames) return base
    var suffix = 2
    while ("$base $suffix" in categoryNames) suffix++
    return "$base $suffix"
}

/** 明细排序变更不触发数据库查询（内存排序） */
class StatsViewModel(
    private val billRepository: BillRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository
) : ViewModel() {

    // ---------- 用户交互状态 ----------
    private val _monthOffset = MutableStateFlow(0)
    val monthOffset: StateFlow<Int> = _monthOffset

    private val _flowType = MutableStateFlow(BillType.EXPENSE)
    val flowType: StateFlow<BillType> = _flowType

    /** 选中日期（当天 0 点），默认今天；换月后重置为该月 1 号 */
    private val _selectedDay = MutableStateFlow(Formatters.dayStart(System.currentTimeMillis()))

    // Anchor a category filter to its day so an automatic month rollover cannot hide the new day's bills.
    private val _selectedCategory = MutableStateFlow<Pair<Long, Long>?>(null)

    private val _sort = MutableStateFlow(DetailSort.TIME_DESC)
    val sort: StateFlow<DetailSort> = _sort

    // ---------- 数据流（核心性能点） ----------

    /** The range refreshes when the calendar rolls over, including a resumed screen. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val displayedMonth: StateFlow<Pair<Long, Long>> = _monthOffset
        .flatMapLatest { offset -> billRepository.observeMonthRange(offset) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Formatters.currentMonthRange())

    val selectedDay: StateFlow<Long> = combine(_selectedDay, displayedMonth) { requestedDay, range ->
        if (requestedDay in range.first until range.second) requestedDay
        else Formatters.dayStart(System.currentTimeMillis()).takeIf { it in range.first until range.second }
            ?: range.first
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), _selectedDay.value)

    val selectedCategoryId: StateFlow<Long?> = combine(_selectedCategory, selectedDay) { selection, day ->
        selection?.takeIf { it.first == day }?.second
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val monthBills: StateFlow<List<BillEntity>> = displayedMonth
        .flatMapLatest { (start, end) -> billRepository.observeBetween(start, end) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val monthLabel: StateFlow<String> = displayedMonth
        .map { range -> Formatters.monthLabel(range.first) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /** 收支长河：月账单 × 收支类型 → 每日柱；selectedDay 不参与，换日不重算 */
    val cashFlowBars: StateFlow<List<DayBar>> =
        combine(monthBills, _flowType, displayedMonth) { bills, type, range ->
            val calendar = Calendar.getInstance().apply { timeInMillis = range.first }
            val days = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
            val todayStart = Formatters.dayStart(System.currentTimeMillis())
            val sums = LongArray(days + 1)
            bills.forEach { b ->
                if (b.type == type && b.timestamp in range.first until range.second) {
                    val day = dayOfMonthOf(b.timestamp)
                    if (day in 1..days) sums[day] += b.amountFen
                }
            }
            (1..days).map { day ->
                val dayStart = (calendar.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, day) }.timeInMillis
                DayBar(
                    day = day,
                    dayStartMillis = dayStart,
                    amountFen = sums[day],
                    isToday = dayStart == todayStart
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 今日瓜分：选中日的分类切片（≤7 片，超出合并「其他」） */
    val dayDonut: StateFlow<DayDonutUi> =
        combine(monthBills, selectedDay, categoryRepository.categories, _flowType, selectedCategoryId) { bills, day, cats, type, selectedId ->
            distribution(bills, day, cats, type, selectedId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DayDonutUi())

    fun observeDay(day: Long, type: BillType): kotlinx.coroutines.flow.Flow<DayDonutUi> =
        combine(billRepository.observeBetween(day, com.jiligulu.app.ui.components.shiftLocalDay(day, 1)), categoryRepository.categories) { bills, cats ->
            distribution(bills, day, cats, type, null)
        }

    private fun distribution(bills: List<BillEntity>, day: Long, cats: List<com.jiligulu.app.data.local.entity.CategoryEntity>,
        type: BillType, selectedId: Long?): DayDonutUi {
            val dayBills = bills.filter {
                Formatters.dayStart(it.timestamp) == day && it.type == type
            }
            val catMap = cats.associateBy { it.id }
            val byCat = dayBills.groupBy { it.categoryId }
                .map { (catId, list) -> catId to list.sumOf { it.amountFen } }
                .sortedByDescending { it.second }

            // ≤7 片，其余合并「其他」（PRD §5.4）
            val merged = if (byCat.size > 7) {
                byCat.take(6) + listOf(OTHER_KEY to byCat.drop(6).sumOf { it.second })
            } else byCat

            val slices = merged.map { (catId, fen) ->
                val cat = catMap[catId]
                DonutSlice(
                    key = catId,
                    label = if (catId == OTHER_KEY) mergedCategoryLabel(cats.map { CategoryLabels.displayName(it.name) }.toSet())
                        else CategoryLabels.displayName(cat?.name ?: "未分类"),
                    valueFen = fen,
                    color = if (catId == OTHER_KEY) OTHER_COLOR
                    else GoldenAnglePalette.colorForHue(cat?.colorHue ?: 0f)
                )
            }
            val total = dayBills.sumOf { it.amountFen }
            val selectedSlice = slices.firstOrNull { it.key == selectedId }
            return DayDonutUi(
                dayStartMillis = day, type = type,
                dayLabel = Formatters.dayLabel(day),
                slices = slices,
                totalText = Formatters.fenToYuanText(total),
                selectedCategoryId = selectedId,
                selectedLabel = selectedSlice?.label ?: if (type == BillType.EXPENSE) "当日总支出" else "当日总收入",
                selectedAmountText = Formatters.fenToYuanText(selectedSlice?.valueFen ?: total)
            )
    }

    /** 选中日明细列表：分类过滤 + 排序，全内存操作 */
    val dayDetails: StateFlow<List<DayDetailUi>> =
        combine(combine(monthBills, _flowType) { bills, type -> bills.filter { it.type == type } }, selectedDay, selectedCategoryId, _sort) { bills, day, catId, sort ->
            Quad(bills, day, catId, sort)
        }.combine(categoryRepository.categories) { q, cats ->
            val catMap = cats.associateBy { it.id }
            val daily = q.bills.filter { Formatters.dayStart(it.timestamp) == q.day }
            val leading = daily.groupBy { it.categoryId }.entries.sortedByDescending { entry -> entry.value.sumOf { it.amountFen } }.take(6).map { it.key }.toSet()
            daily.asSequence()
                .filter { q.catId == null || if (q.catId == OTHER_KEY) it.categoryId !in leading else it.categoryId == q.catId }
                .sortedWith(
                    when (q.sort) {
                        DetailSort.TIME_DESC -> compareByDescending { it.timestamp }
                        DetailSort.TIME_ASC -> compareBy { it.timestamp }
                        DetailSort.AMOUNT_DESC -> compareByDescending { it.amountFen }
                        DetailSort.AMOUNT_ASC -> compareBy { it.amountFen }
                    }
                )
                .map { b ->
                    val isExpense = b.type == BillType.EXPENSE
                    val cat = catMap[b.categoryId]
                    DayDetailUi(
                        id = b.id,
                        icon = cat?.iconValue.orEmpty(),
                        colorHue = cat?.colorHue ?: 0f,
                        categoryName = CategoryLabels.displayName(cat?.name ?: "未分类"),
                        detail = b.detail,
                        timeLabel = Formatters.timeLabel(b.timestamp) + if (b.note.isNotBlank()) " · ${b.note}" else "",
                        amountText = (if (isExpense) "-" else "+") + Formatters.fenToYuanText(b.amountFen),
                        isExpense = isExpense
                    )
                }
                .toList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 余粮环：BudgetRepository 内部只查当前周期账单，范围最小化 */
    val budgetUi: StateFlow<BudgetUi> = budgetRepository.observeStatus()
        .map { status -> status?.toUi() ?: BudgetUi() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BudgetUi())

    // ---------- 交互动作 ----------

    fun prevMonth() {
        _monthOffset.value -= 1
        _selectedDay.value = Formatters.dayOfMonth(_monthOffset.value, 1)
        _selectedCategory.value = null
    }

    fun nextMonth() {
        if (_monthOffset.value < 0) {
            _monthOffset.value += 1
            _selectedDay.value = if (_monthOffset.value == 0) {
                Formatters.dayStart(System.currentTimeMillis())
            } else {
                Formatters.dayOfMonth(_monthOffset.value, 1)
            }
            _selectedCategory.value = null
        }
    }

    fun setFlowType(type: BillType) {
        _selectedCategory.value = null
        _flowType.value = type
    }

    fun shiftDay(delta: Int) {
        val next = com.jiligulu.app.ui.components.shiftLocalDay(selectedDay.value, delta.toLong())
        selectCalendarDate(next)
    }

    /** 点柱或日期 chip 选中日：只改内存状态，不触发数据库查询（同月换日月图不重绘） */
    fun selectDay(dayStartMillis: Long) {
        _selectedDay.value = dayStartMillis
        _selectedCategory.value = null
    }

    /** Calendar navigation only changes the statistics filter, never a bill's timestamp. */
    fun selectCalendarDate(dayStartMillis: Long) {
        val zone = ZoneId.systemDefault()
        val requestedMonth = YearMonth.from(Instant.ofEpochMilli(dayStartMillis).atZone(zone))
        val offset = ChronoUnit.MONTHS.between(YearMonth.now(zone), requestedMonth).toInt()
        if (offset > 0) return
        _monthOffset.value = offset
        selectDay(Formatters.dayStart(dayStartMillis))
    }

    fun toggleCategory(categoryId: Long?) {
        _selectedCategory.value =
            if (categoryId == null || selectedCategoryId.value == categoryId) null
            else selectedDay.value to categoryId
    }

    fun setSort(sort: DetailSort) {
        _sort.value = sort
    }

    fun saveBudget(amountFen: Long, period: BudgetPeriod, anchorDay: Int) {
        viewModelScope.launch { budgetRepository.setBudget(amountFen, period, anchorDay) }
    }

    fun clearBudget() {
        viewModelScope.launch { budgetRepository.clearBudget() }
    }

    // ---------- 内部 ----------

    private fun dayOfMonthOf(timestamp: Long): Int {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(java.util.Calendar.DAY_OF_MONTH)
    }

    private fun BudgetStatus.toUi(): BudgetUi {
        val overspend = overspendRatio > 0f
        val slices = if (overspend) {
            // 超支态：整环红色（PRD：绿色消失）
            listOf(
                DonutSlice(key = "overspend", label = "超支", valueFen = spentFen, color = OVERSPEND_COLOR)
            )
        } else {
            listOf(
                DonutSlice(key = "spent", label = "已用", valueFen = spentFen.coerceAtLeast(0L), color = TRACK_COLOR),
                DonutSlice(key = "remain", label = "剩余", valueFen = remainFen.coerceAtLeast(0L), color = REMAIN_COLOR)
            )
        }
        val periodLabel = when (budget.periodType) {
            BudgetPeriod.DAILY -> "每日预算"
            BudgetPeriod.WEEKLY -> "每周预算"
            BudgetPeriod.MONTHLY -> "每月预算"
        }
        return BudgetUi(
            visible = true,
            usedPercentText = String.format(java.util.Locale.ROOT, "%.1f%%", spentFen.toDouble() / budget.amountFen.coerceAtLeast(1) * 100),
            spentText = Formatters.fenToYuanText(spentFen),
            totalText = Formatters.fenToYuanText(budget.amountFen),
            remainText = Formatters.fenToYuanText(remainFen),
            usedSlices = slices,
            overspendPercentText = if (overspend) "超支 ${(overspendRatio * 100).toInt()}%" else "",
            periodLabel = periodLabel
        )
    }

    companion object {
        private const val OTHER_KEY = -1L
        private val OTHER_COLOR = Color(0xFFB4B2A9)
        private val REMAIN_COLOR = BudgetRemainGreen
        private val TRACK_COLOR = Color(0xFFDDD9CC)
        private val OVERSPEND_COLOR = DangerRed

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as JiliguluApp
                StatsViewModel(
                    app.container.billRepository,
                    app.container.categoryRepository,
                    app.container.budgetRepository
                )
            }
        }
    }
}
