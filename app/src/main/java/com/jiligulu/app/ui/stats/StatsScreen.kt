package com.jiligulu.app.ui.stats

import com.jiligulu.app.ui.components.DayBrowser
import com.jiligulu.app.ui.components.daySwipe
import com.jiligulu.app.ui.components.CompactChoice
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.BudgetPeriod
import com.jiligulu.app.ui.components.LedgerCard
import com.jiligulu.app.ui.components.LedgerBillRow
import com.jiligulu.app.ui.billdetail.BillDetailSheet
import com.jiligulu.app.ui.stats.charts.CashFlowBarChart
import com.jiligulu.app.ui.stats.charts.DonutChart
import com.jiligulu.app.ui.theme.ExpenseCoral
import com.jiligulu.app.ui.theme.IncomeGreen
import com.jiligulu.app.ui.components.billDatePickerYearRange
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

/** 统计内容只负责账本展示，常驻桌宠由主框架统一承载。 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    vm: StatsViewModel = viewModel(factory = StatsViewModel.Factory)
) {
    val monthLabel by vm.monthLabel.collectAsStateWithLifecycle()
    val monthOffset by vm.monthOffset.collectAsStateWithLifecycle()
    val flowType by vm.flowType.collectAsStateWithLifecycle()
    val bars by vm.cashFlowBars.collectAsStateWithLifecycle()
    val selectedDay by vm.selectedDay.collectAsStateWithLifecycle()
    val dayDonut by vm.dayDonut.collectAsStateWithLifecycle()
    val dayDetails by vm.dayDetails.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val budget by vm.budgetUi.collectAsStateWithLifecycle()

    var showBudgetDialog by rememberSaveable { mutableStateOf(false) }
    var selectedBillId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showDateFilter by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("收支统计", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "每一笔生活，都有迹可循。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item(key = "month") {
            LedgerCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { vm.prevMonth() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "查看上个月")
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(monthLabel, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (monthOffset == 0) "本月账本" else "往月账本",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { vm.nextMonth() }, enabled = monthOffset < 0) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "查看下个月")
                    }
                }
            }
        }

        // ---------- 收支长河 ----------
        item(key = "cash_flow") {
            ChartCard(title = "每日收支", subtitle = "一日一格，记下生活的小脚印 ♡", action = {
                TextButton(onClick = { showDateFilter = true }) { Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("选日期") }
            }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = flowType == BillType.EXPENSE,
                        onClick = { vm.setFlowType(BillType.EXPENSE) },
                        label = { Text("支出", style = MaterialTheme.typography.labelMedium) }
                    )
                    FilterChip(
                        selected = flowType == BillType.INCOME,
                        onClick = { vm.setFlowType(BillType.INCOME) },
                        label = { Text("收入", style = MaterialTheme.typography.labelMedium) }
                    )
                }
                Spacer(Modifier.height(12.dp))
                CashFlowBarChart(
                    bars = bars,
                    selectedDayMillis = selectedDay,
                    onSelectDay = { vm.selectDay(it) },
                    color = if (flowType == BillType.EXPENSE) ExpenseCoral else IncomeGreen,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "左右滑一滑，轻点柱子看看当天的小账单。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---------- 当日分类 ----------
        item(key = "day_categories") {
            ChartCard(title = if (flowType == BillType.EXPENSE) "支出分布" else "收入分布", subtitle = "左右滑动换一天 · 点分类展开账单") {
                DayBrowser(selectedDay, vm::selectCalendarDate,
                    latest = YearMonth.now().atEndOfMonth().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
                Column(Modifier.fillMaxWidth().animateContentSize(tween(260)).daySwipe(selectedDay, { vm.shiftDay(-1) }, { vm.shiftDay(1) })) {
                    AdaptiveChartDetails(
                        chart = {
                            DonutChart(
                                slices = dayDonut.slices,
                                selectedKey = dayDonut.selectedCategoryId,
                                onSelect = { key -> vm.toggleCategory(key as? Long) },
                                modifier = Modifier.size(176.dp).testTag("daily-donut")
                            ) {
                                RingLabel(if (dayDonut.slices.isEmpty()) "暂无" + if (flowType == BillType.EXPENSE) "支出" else "收入" else dayDonut.selectedLabel,
                                    "¥${dayDonut.selectedAmountText.ifBlank { "0" }}")
                            }
                        },
                        details = {
                            Column(Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                            if (dayDonut.slices.isEmpty()) {
                                Text("这一天先留个小空位 ♡", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), textAlign = TextAlign.Center)
                            }
                            dayDonut.slices.forEach { slice ->
                                CategoryDrawer(
                                    color = slice.color,
                                    label = "${slice.label} · ${String.format(java.util.Locale.ROOT, "%.1f", slice.valueFen.toDouble() / dayDonut.slices.sumOf { it.valueFen }.coerceAtLeast(1) * 100)}%",
                                    amountText = "¥${Formatters.fenToYuanText(slice.valueFen)}",
                                    selected = slice.key == dayDonut.selectedCategoryId,
                                    onClick = { vm.toggleCategory(slice.key as? Long) },
                                    details = if (slice.key == dayDonut.selectedCategoryId) dayDetails else emptyList(),
                                    sort = sort, onSort = vm::setSort, onBill = { selectedBillId = it }
                                )
                            }
                            }
                        }
                    )
                }
            }
        }

        // 预算始终对应当前周期，与上方所选账本月份独立。
        item(key = "budget") {
            ChartCard(title = "预算余量", subtitle = "余粮环 · 当前预算周期") {
                if (!budget.visible) {
                    Text(
                        "给日常开销留一个舒服的边界，叽里咕噜帮你记着。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { showBudgetDialog = true }) {
                        Text("设置预算")
                    }
                } else {
                    AdaptiveChartDetails(
                        chart = {
                            DonutChart(
                                slices = budget.usedSlices,
                                onSelect = null,
                                modifier = Modifier.size(176.dp)
                            ) {
                                RingLabel(
                                    label = if (budget.overspendPercentText.isNotBlank()) "已超支" else "剩余预算",
                                    amountText = "¥${budget.remainText}",
                                    amountColor = if (budget.overspendPercentText.isNotBlank()) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        details = {
                            Text(
                                budget.periodLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text("预算  ¥${budget.totalText}", style = MaterialTheme.typography.bodyLarge)
                            Text("已用  ¥${budget.spentText} · ${budget.usedPercentText}", style = MaterialTheme.typography.bodyLarge)
                            if (budget.overspendPercentText.isNotBlank()) {
                                Text(
                                    budget.overspendPercentText,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    )
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showBudgetDialog = true }) { Text("调整预算") }
                        TextButton(onClick = { vm.clearBudget() }) {
                            Text("停用预算", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

    }

    if (showBudgetDialog) {
        BudgetDialog(
            onDismiss = { showBudgetDialog = false },
            onSave = { amountFen, period, anchorDay ->
                vm.saveBudget(amountFen, period, anchorDay)
                showBudgetDialog = false
            }
        )
    }
    selectedBillId?.let { id -> BillDetailSheet(id, onDismiss = { selectedBillId = null }) }
    if (showDateFilter) {
        val zone = ZoneId.systemDefault()
        val initialDate = Instant.ofEpochMilli(selectedDay).atZone(zone).toLocalDate()
        val latestMonth = YearMonth.now(zone)
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = billDatePickerYearRange(initialDate.year).first..latestMonth.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    YearMonth.from(Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC)) <= latestMonth
            }
        )
        DatePickerDialog(onDismissRequest = { showDateFilter = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { utcDay ->
                        val localDate = Instant.ofEpochMilli(utcDay).atZone(ZoneOffset.UTC).toLocalDate()
                        vm.selectCalendarDate(localDate.atStartOfDay(zone).toInstant().toEpochMilli())
                    }
                    showDateFilter = false
                }, enabled = pickerState.selectedDateMillis != null) { Text("查看这一天") }
            },
            dismissButton = { TextButton(onClick = { showDateFilter = false }) { Text("取消") } }) {
            DatePicker(state = pickerState, title = {
                Text("选择统计日期", modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                    style = MaterialTheme.typography.labelLarge)
            })
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    LedgerCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            action?.invoke()
        }
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(16.dp))
        content()
    }
}

/** 小屏和大字体时纵向排布，避免固定大小的圆环挤掉图例文字。 */
@Composable
private fun AdaptiveChartDetails(
    chart: @Composable () -> Unit,
    details: @Composable ColumnScope.() -> Unit
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 360.dp || fontScale > 1.15f) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                chart()
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    content = details
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                chart()
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    content = details
                )
            }
        }
    }
}

@Composable
private fun RingLabel(
    label: String,
    amountText: String,
    amountColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(
        modifier = Modifier.width(112.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            amountText,
            style = MaterialTheme.typography.titleMedium,
            color = amountColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CategoryDrawer(color: Color, label: String, amountText: String, selected: Boolean, onClick: () -> Unit,
    details: List<DayDetailUi>, sort: DetailSort, onSort: (DetailSort) -> Unit, onBill: (Long) -> Unit) {
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) {
        if (selected) { kotlinx.coroutines.delay(250); bringIntoView.bringIntoView() }
    }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView)) {
        LegendRow(color, label + if (selected) " ▴" else " ▾", amountText, selected, onClick)
        AnimatedVisibility(selected) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .22f), MaterialTheme.shapes.medium).padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${details.size} 笔小账单", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    CompactChoice(listOf("时间↓", "时间↑", "金额↓", "金额↑"), sort.ordinal) { onSort(DetailSort.entries[it]) }
                }
                if (details.isEmpty()) Text("这一天该分类没有账单", style = MaterialTheme.typography.bodySmall)
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    itemsIndexed(details, key = { _, bill -> bill.id }) { index, d ->
                        LedgerBillRow(icon = d.icon, colorHue = d.colorHue, categoryName = d.categoryName,
                            title = d.detail.ifBlank { d.categoryName }, subtitle = d.timeLabel,
                            amountText = d.amountText, isExpense = d.isExpense,
                            onClick = { onBill(d.id) }, showDivider = index < details.lastIndex)
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendRow(
    color: Color,
    label: String,
    amountText: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                MaterialTheme.shapes.small
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                amountText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
    )
}

/** 预算金额必须为正；超支后的剩余金额仍可显示为负数。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BudgetDialog(
    onDismiss: () -> Unit,
    onSave: (amountFen: Long, period: BudgetPeriod, anchorDay: Int) -> Unit
) {
    var amountText by rememberSaveable { mutableStateOf("") }
    var period by rememberSaveable { mutableStateOf(BudgetPeriod.MONTHLY) }
    var anchorDayText by rememberSaveable { mutableStateOf("1") }

    val amountFen = Formatters.yuanTextToFen(amountText)
    val anchorDay = anchorDayText.toIntOrNull()?.takeIf { it in 1..28 }
    val amountInvalid = amountText.isNotBlank() && amountFen == null
    val canSave = amountFen != null && (period != BudgetPeriod.MONTHLY || anchorDay != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置预算") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { value ->
                        // 保留负号以明确报错，避免把粘贴的负数静默改成正数；同时限制金额位数防溢出。
                        if (value.matches(Regex("-?\\d{0,12}(\\.\\d{0,2})?"))) amountText = value
                    },
                    label = { Text("金额（元）") },
                    prefix = { Text("¥") },
                    singleLine = true,
                    isError = amountInvalid,
                    supportingText = {
                        Text(if (amountInvalid) "请输入大于 0 的预算金额" else "预算须大于 0，最多保留两位小数")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Text("预算周期", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(period == BudgetPeriod.DAILY, { period = BudgetPeriod.DAILY },
                        label = { Text("每天", style = MaterialTheme.typography.labelMedium) })
                    FilterChip(period == BudgetPeriod.WEEKLY, { period = BudgetPeriod.WEEKLY },
                        label = { Text("每 7 天", style = MaterialTheme.typography.labelMedium) })
                    FilterChip(period == BudgetPeriod.MONTHLY, { period = BudgetPeriod.MONTHLY },
                        label = { Text("每月", style = MaterialTheme.typography.labelMedium) })
                }
                if (period == BudgetPeriod.MONTHLY) {
                    OutlinedTextField(
                        value = anchorDayText,
                        onValueChange = { value ->
                            if (value.length <= 2 && value.all { it.isDigit() }) anchorDayText = value
                        },
                        label = { Text("每月开始日") },
                        suffix = { Text("号") },
                        supportingText = { Text("可填写 1–28 号") },
                        isError = anchorDay == null,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { amountFen?.let { onSave(it, period, anchorDay ?: 1) } },
                enabled = canSave
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
