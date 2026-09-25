package com.jiligulu.app.ui.stats


import com.jiligulu.app.ui.components.edgeSpring
import com.jiligulu.app.ui.components.EdgeSpringState
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.jiligulu.app.ui.components.LedgerScrollBar
import androidx.compose.foundation.lazy.rememberLazyListState
import com.jiligulu.app.ui.components.CompactChoice
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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

/** 紧凑统计：共享日期与收支筛选，分类二次点击打开明细。 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    vm: StatsViewModel = viewModel(factory = StatsViewModel.Factory),
    active: Boolean = true
) {
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

    var showCategoryDetails by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        vm.toggleCategory(null)
        showCategoryDetails = false
    }
    LaunchedEffect(selectedDay, flowType) { showCategoryDetails = false }
    val selectCategory: (Any?) -> Unit = { key ->
        val id = key as? Long
        if (id != null && id == dayDonut.selectedCategoryId) showCategoryDetails = true
        else { showCategoryDetails = false; vm.toggleCategory(id) }
    }
    var visibleRange by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    val scroll = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        state = scroll,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .edgeSpring({ scroll.canScrollBackward }, { scroll.canScrollForward }, interceptPre = false),
        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "filters") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(Modifier.weight(1f).height(44.dp).clickable { showDateFilter = true },
                    shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                    val date = Instant.ofEpochMilli(selectedDay).atZone(ZoneId.systemDefault()).toLocalDate()
                    val range = visibleRange
                    fun shortDate(value: Long) = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate().let { "${it.monthValue}月${it.dayOfMonth}日" }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center) {
                        Text(if (range == null) "${date.monthValue}月${date.dayOfMonth}日" else "${shortDate(range.first)} - ${shortDate(range.second)}",
                            color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(5.dp))
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "选择统计日期", Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(Modifier.height(44.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(BillType.EXPENSE to "支出", BillType.INCOME to "收入").forEach { (type, label) ->
                        Surface(onClick = { vm.setFlowType(type) }, shape = RoundedCornerShape(24.dp),
                            color = if (flowType == type) MaterialTheme.colorScheme.primary.copy(alpha = .78f) else Color.Transparent) {
                            Text(label, Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (flowType == type) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // ---------- 收支长河 ----------
        item(key = "cash_flow") {
            ChartCard(title = "每日收支", action = {
                Text("单位：元", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }) {
                CashFlowBarChart(
                    bars = bars,
                    onVisibleRange = { first, last -> visibleRange = first to last },
                    selectedDayMillis = selectedDay,
                    onSelectDay = { vm.selectDay(it) },
                    color = if (flowType == BillType.EXPENSE) ExpenseCoral else IncomeGreen,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(202.dp)
                )
            }
        }

        // ---------- 当日分类 ----------
        item(key = "day_categories") {
            ChartCard(title = if (flowType == BillType.EXPENSE) "支出分布" else "收入分布") {
                val pageData = dayDonut
                BoxWithConstraints(Modifier.fillMaxWidth().testTag("statistics-day-swipe").pointerInput(selectedDay) {
                    var drag = 0f
                    detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragCancel = { drag = 0f },
                        onDragEnd = { if (drag > 48.dp.toPx()) vm.shiftDay(-1) else if (drag < -48.dp.toPx()) vm.shiftDay(1) },
                        onHorizontalDrag = { change, delta -> drag += delta; change.consume() })
                }) {
                    val ringSize = (maxWidth * .45f).coerceIn(112.dp, 166.dp)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DonutChart(slices = pageData.slices, animateOnDataChange = true,
                            replayKey = selectedDay to flowType, selectedKey = pageData.selectedCategoryId,
                            selectedBoost = 1f, dimUnselected = true, toggleSelection = false,
                            onSelect = selectCategory,
                            modifier = Modifier.size(ringSize).testTag("daily-donut")) {
                            Column(Modifier.width(ringSize * .61f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("¥${pageData.selectedAmountText.ifBlank { "0" }}", maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(if (pageData.slices.isEmpty()) "暂无记录" else pageData.selectedLabel,
                                    style = MaterialTheme.typography.labelSmall, maxLines = 2,
                                    textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        val legendState = rememberLazyListState()
                        val edge = remember(selectedDay, flowType) { EdgeSpringState() }
                        LaunchedEffect(selectedDay, flowType) { legendState.scrollToItem(0) }
                        Box(Modifier.weight(1f).height(190.dp)) {
                            LazyColumn(state = legendState, modifier = Modifier.fillMaxSize().edgeSpring(
                                { legendState.canScrollBackward }, { legendState.canScrollForward }, handOffOnRepeat = true, state = edge)) {
                                if (pageData.slices.isEmpty()) item {
                                    Text("这一天先留个\n小空位 ♡", Modifier.padding(top = 60.dp),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                itemsIndexed(pageData.slices, key = { _, slice -> slice.key }) { _, slice ->
                                    val selected = slice.key == pageData.selectedCategoryId
                                    Row(Modifier.fillMaxWidth().background(
                                        if (selected) slice.color.copy(alpha = .13f) else Color.Transparent,
                                        RoundedCornerShape(12.dp)).clickable { selectCategory(slice.key) }.padding(horizontal = 6.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        CategoryGlyph(slice.label, slice.color)
                                        Column(Modifier.weight(1f)) {
                                            Text(slice.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelMedium)
                                            Text("¥${Formatters.fenToYuanText(slice.valueFen)}", maxLines = 1,
                                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(String.format(java.util.Locale.ROOT, "%.0f%%", slice.valueFen.toDouble() /
                                            pageData.slices.sumOf { it.valueFen }.coerceAtLeast(1) * 100),
                                            style = MaterialTheme.typography.labelMedium, color = slice.color)
                                    }
                                }
                            }
                            LedgerScrollBar(legendState, Modifier.align(Alignment.CenterEnd), forceVisible = edge.visible)
                        }
                    }
                }
                Text(if (dayDonut.selectedCategoryId == null) "轻点分类看占比 · 左右滑动换一天" else "再点一次选中分类，看看小账单 ♡",
                    Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 预算周期独立于日期筛选，用紧凑进度卡代替第二个圆环。
        item(key = "budget") {
            LedgerCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("预算已用", style = MaterialTheme.typography.titleMedium)
                        Text(if (budget.visible) budget.usedPercentText else "还没设预算",
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (budget.overspendPercentText.isNotBlank()) ExpenseCoral else MaterialTheme.colorScheme.primary)
                    }
                    androidx.compose.material3.IconButton(onClick = { showBudgetDialog = true }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "设置或调整预算",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
                val fraction = (budget.usedPercentText.removeSuffix("%").toFloatOrNull() ?: 0f) / 100f
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(10.dp).padding(vertical = 1.dp),
                    color = if (budget.overspendPercentText.isNotBlank()) ExpenseCoral else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                Spacer(Modifier.height(10.dp))
                if (budget.visible) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${budget.periodLabel} ¥${budget.totalText}", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("已用 ¥${budget.spentText}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (budget.overspendPercentText.isNotBlank()) {
                        Text("超出 ¥${budget.remainText.removePrefix("-")}", Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall, color = ExpenseCoral)
                    }
                } else Text("留一点余量，日子慢慢过 ♡", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

    }

    LedgerScrollBar(scroll, Modifier.align(Alignment.CenterEnd))
    }

    if (showCategoryDetails && dayDonut.selectedCategoryId != null) {
        Dialog(onDismissRequest = { showCategoryDetails = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth(.88f).widthIn(max = 380.dp), shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${dayDonut.selectedLabel}的小账单", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { showCategoryDetails = false }) { Text("关闭") }
                    }
                    Text("${dayDonut.dayLabel} · ${dayDetails.size} 笔 · ¥${dayDonut.selectedAmountText}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    CompactChoice(listOf("时间↓", "时间↑", "金额↓", "金额↑"), sort.ordinal) { vm.setSort(DetailSort.entries[it]) }
                    val detailsScroll = rememberLazyListState()
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).edgeSpring({ detailsScroll.canScrollBackward }, { detailsScroll.canScrollForward }), state = detailsScroll) {
                        itemsIndexed(dayDetails, key = { _, bill -> bill.id }) { index, d ->
                            LedgerBillRow(icon = d.icon, colorHue = d.colorHue, categoryName = d.categoryName,
                                title = d.detail.ifBlank { d.categoryName }, subtitle = d.timeLabel,
                                amountText = d.amountText, isExpense = d.isExpense,
                                onClick = { selectedBillId = d.id }, showDivider = index < dayDetails.lastIndex)
                        }
                    }
                }
            }
        }
    }
    if (showBudgetDialog) {
        BudgetDialog(
            onDismiss = { showBudgetDialog = false },
            onDisable = if (budget.visible) ({ vm.clearBudget(); showBudgetDialog = false }) else null,
            onSave = { amountFen, period, anchorDay ->
                vm.saveBudget(amountFen, period, anchorDay)
                showBudgetDialog = false
            }
        )
    }
    selectedBillId?.let { id -> BillDetailSheet(id, onDismiss = { selectedBillId = null }) }
    if (showDateFilter) {
        com.jiligulu.app.ui.components.CompactCalendarDialog(selectedDay,
            onDismiss = { showDateFilter = false },
            onSelect = { vm.selectCalendarDate(it); showDateFilter = false })
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
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun CategoryGlyph(label: String, color: Color) {
    val glyph = when (label) {
        "吃饭", "餐饮" -> Icons.Outlined.Restaurant
        "交通" -> Icons.Outlined.DirectionsBus
        "饮品" -> Icons.Outlined.LocalCafe
        "零食" -> Icons.Outlined.Cookie
        "住房" -> Icons.Outlined.Home
        "数码" -> Icons.Outlined.Devices
        "宠物" -> Icons.Outlined.Pets
        "购物", "日用品" -> Icons.Outlined.ShoppingBag
        "学习" -> Icons.Outlined.School
        "工资", "生活服务" -> Icons.Outlined.WorkOutline
        "红包", "人情" -> Icons.Outlined.CardGiftcard
        else -> Icons.Outlined.Category
    }
    Icon(glyph, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
}

/** 预算金额必须为正；超支后的剩余金额仍可显示为负数。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BudgetDialog(
    onDismiss: () -> Unit,
    onDisable: (() -> Unit)? = null,
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
                if (onDisable != null) TextButton(onClick = onDisable) { Text("停用预算") }
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
