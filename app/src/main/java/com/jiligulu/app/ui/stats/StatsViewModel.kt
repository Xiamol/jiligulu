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
import com.jiligulu.app.domain.forecast.DailySpending
import com.jiligulu.app.domain.forecast.MonthlySpendingAverage
import com.jiligulu.app.domain.forecast.MonthlyAverageDay
import com.jiligulu.app.ui.stats.charts.DayBar
import com.jiligulu.app.ui.stats.charts.DonutSlice
import com.jiligulu.app.ui.theme.BudgetRemainGreen
import com.jiligulu.app.ui.theme.DangerRed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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
    val periodLabel: String = "",
    val period: BudgetPeriod = BudgetPeriod.MONTHLY,
    val anchorDay: Int = 1
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

    /** Null follows today's calendar; an explicit selection remains stable across range queries. */
    private val _selectedDay = MutableStateFlow<Long?>(null)
    private val _compactWindowStart = MutableStateFlow<LocalDate?>(null)

    // Anchor a category filter to its day so an automatic month rollover cannot hide the new day's bills.
    private val _selectedCategory = MutableStateFlow<Pair<Long, Long>?>(null)

    private val _sort = MutableStateFlow(DetailSort.TIME_DESC)
    val sort: StateFlow<DetailSort> = _sort

    // ---------- 数据流（核心性能点） ----------

    /** Static statistics wake at the next local midnight, not once every rendered frame or minute. */
    val today: StateFlow<LocalDate> = flow {
        val zone = ZoneId.systemDefault()
        while (true) {
            val now = Instant.now()
            val day = now.atZone(zone).toLocalDate()
            emit(day)
            val next = day.plusDays(1).atStartOfDay(zone).toInstant()
            delay((Duration.between(now, next).toMillis() + 100L).coerceAtLeast(100L))
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LocalDate.now())

    /** The range refreshes when the calendar rolls over, including a resumed screen. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val displayedMonth: StateFlow<Pair<Long, Long>> = _selectedDay
        .flatMapLatest { requested ->
            if (requested == null) billRepository.observeMonthRange()
            else {
                val zone = ZoneId.systemDefault()
                flowOf(monthStatsWindow(Instant.ofEpochMilli(requested).atZone(zone).toLocalDate()).millis(zone))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Formatters.currentMonthRange())

    val selectedDay: StateFlow<Long> = combine(_selectedDay, displayedMonth, today) { requestedDay, range, now ->
        requestedDay ?: now.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli().takeIf { it in range.first until range.second }
            ?: range.first
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Formatters.dayStart(System.currentTimeMillis()))

    internal val compactWindow: StateFlow<StatsDateWindow> = combine(_compactWindowStart, selectedDay, today) { first, day, now ->
        if (first != null) StatsDateWindow(first, first.plusDays(9))
        else compactStatsWindow(Instant.ofEpochMilli(day).atZone(ZoneId.systemDefault()).toLocalDate(), now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LocalDate.now().let { compactStatsWindow(it, it) })

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
        combine(monthBills, _flowType, displayedMonth, today) { bills, type, range, now ->
            val zone = ZoneId.systemDefault()
            val first = Instant.ofEpochMilli(range.first).atZone(zone).toLocalDate()
            statsDayBars(bills, type, monthStatsWindow(first), now, zone)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** A bounded ten-day query, including both months when the visible window crosses a boundary. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val compactBills = compactWindow.flatMapLatest { window ->
        val (start, end) = window.millis(ZoneId.systemDefault())
        billRepository.observeBetween(start, end)
    }

    val compactCashFlowBars: StateFlow<List<DayBar>> = combine(compactBills, compactWindow, _flowType, today) { bills, window, type, now ->
        statsDayBars(bills, type, window, now, ZoneId.systemDefault())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val selectedDayBills = selectedDay.flatMapLatest { day ->
        billRepository.observeBetween(day, com.jiligulu.app.ui.components.shiftLocalDay(day, 1))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val monthlyExpenseAverages: StateFlow<List<MonthlyAverageDay>> = combine(monthBills, displayedMonth, today) { bills, range, now ->
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(range.first).atZone(zone).toLocalDate()
        val end = Instant.ofEpochMilli(range.second).atZone(zone).toLocalDate()
        val expenses = bills.asSequence().filter { it.type == BillType.EXPENSE }
            .map { DailySpending(Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate(), it.amountFen) }.toList()
        val days = (0 until ChronoUnit.DAYS.between(start, end).toInt()).map { start.plusDays(it.toLong()) }
        MonthlySpendingAverage.forDates(expenses, days, now)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 今日瓜分：选中日的分类切片（≤7 片，超出合并「其他」） */
    val dayDonut: StateFlow<DayDonutUi> =
        combine(selectedDayBills, selectedDay, categoryRepository.categories, _flowType, selectedCategoryId) { bills, day, cats, type, selectedId ->
            distribution(bills, day, cats, type, selectedId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DayDonutUi())

    fun observeDay(day: Long, type: BillType): kotlinx.coroutines.flow.Flow<DayDonutUi> =
        combine(billRepository.observeBetween(day, com.jiligulu.app.ui.components.shiftLocalDay(day, 1)), categoryRepository.categories) { bills, cats ->
            distribution(bills, day, cats, type, null)
        }

    private fun distribution(bills: List<BillEntity>, day: Long, cats: List<com.jiligulu.app.data.local.entity.CategoryEntity>,
        type: BillType, selectedId: Long?): DayDonutUi {
            val dayEnd = com.jiligulu.app.ui.components.shiftLocalDay(day, 1)
            val dayBills = bills.filter {
                it.timestamp >= day && it.timestamp < dayEnd && it.type == type
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
                    else when (CategoryLabels.displayName(cat?.name.orEmpty())) {
                        "吃饭", "餐饮" -> Color(0xFFEF8D87)
                        "交通" -> Color(0xFF69B89A)
                        "零食" -> Color(0xFFEAB567)
                        "饮品" -> Color(0xFFDB92B8)
                        else -> GoldenAnglePalette.colorForHue(cat?.colorHue ?: 0f)
                    }
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
        combine(combine(selectedDayBills, _flowType) { bills, type -> bills.filter { it.type == type } }, selectedDay, selectedCategoryId, _sort) { bills, day, catId, sort ->
            Quad(bills, day, catId, sort)
        }.combine(categoryRepository.categories) { q, cats ->
            val catMap = cats.associateBy { it.id }
            val dayEnd = com.jiligulu.app.ui.components.shiftLocalDay(q.day, 1)
            val daily = q.bills.filter { it.timestamp >= q.day && it.timestamp < dayEnd }
            val leading = if (q.catId == OTHER_KEY) daily.groupBy { it.categoryId }.entries
                .sortedByDescending { entry -> entry.value.sumOf { it.amountFen } }.take(6).map { it.key }.toSet()
                else emptySet()
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
        shiftMonth(-1)
    }

    fun nextMonth() {
        shiftMonth(1)
    }

    fun shiftMonth(delta: Int) {
        val zone = ZoneId.systemDefault()
        val current = Instant.ofEpochMilli(selectedDay.value).atZone(zone).toLocalDate()
        selectCalendarDate(adjacentStatsMonth(current, delta.toLong()).atStartOfDay(zone).toInstant().toEpochMilli())
    }

    fun shiftCompactWindow(days: Int) {
        if (days == 0) return
        val zone = ZoneId.systemDefault()
        val next = currentCompactWindow().shifted(days.toLong())
        val oldSelected = Instant.ofEpochMilli(selectedDay.value).atZone(zone).toLocalDate()
        val target = oldSelected.plusDays(days.toLong()).coerceIn(next.first, next.last)
        _compactWindowStart.value = next.first
        selectDay(target.atStartOfDay(zone).toInstant().toEpochMilli())
    }

    fun showToday() {
        _compactWindowStart.value = null
        _monthOffset.value = 0
        _selectedDay.value = null
        _selectedCategory.value = null
    }

    fun setFlowType(type: BillType) {
        _selectedCategory.value = null
        _flowType.value = type
    }

    fun shiftDay(delta: Int) {
        val next = com.jiligulu.app.ui.components.shiftLocalDay(selectedDay.value, delta.toLong())
        val date = Instant.ofEpochMilli(next).atZone(ZoneId.systemDefault()).toLocalDate()
        if (date < currentCompactWindow().first || date > currentCompactWindow().last)
            _compactWindowStart.value = compactStatsWindow(date, today.value).first
        selectDay(next)
    }

    /** Point selection preserves its window; only the small daily detail query changes within a month. */
    fun selectDay(dayStartMillis: Long) {
        // Freeze the current window before moving its selection so tapping a bar does not recenter it.
        if (_compactWindowStart.value == null) _compactWindowStart.value = currentCompactWindow().first
        val zone = ZoneId.systemDefault()
        val requestedMonth = YearMonth.from(Instant.ofEpochMilli(dayStartMillis).atZone(zone))
        _monthOffset.value = ChronoUnit.MONTHS.between(YearMonth.now(zone), requestedMonth).toInt()
        _selectedDay.value = Formatters.dayStart(dayStartMillis)
        _selectedCategory.value = null
    }

    private fun currentCompactWindow(): StatsDateWindow = _compactWindowStart.value?.let {
        StatsDateWindow(it, it.plusDays(9))
    } ?: compactStatsWindow(Instant.ofEpochMilli(selectedDay.value).atZone(ZoneId.systemDefault()).toLocalDate(), today.value)

    /** Calendar navigation only changes the statistics filter, never a bill's timestamp. */
    fun selectCalendarDate(dayStartMillis: Long) {
        val zone = ZoneId.systemDefault()
        _compactWindowStart.value = compactStatsWindow(Instant.ofEpochMilli(dayStartMillis).atZone(zone).toLocalDate(), today.value).first
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
            periodLabel = periodLabel,
            period = budget.periodType,
            anchorDay = budget.anchorDay
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
