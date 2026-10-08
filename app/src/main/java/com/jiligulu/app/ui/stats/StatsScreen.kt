package com.jiligulu.app.ui.stats


import com.jiligulu.app.ui.components.edgeSpring
import com.jiligulu.app.ui.components.EdgeSpringState
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import com.jiligulu.app.ui.components.forwardMainPageSwipe
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import com.jiligulu.app.ui.components.GuluDialog
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
import androidx.compose.ui.platform.LocalContext
import com.jiligulu.app.core.audio.UiSound
import com.jiligulu.app.ui.components.uiTap
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
import com.jiligulu.app.data.prefs.StatsBarMode
import com.jiligulu.app.data.prefs.StatsDisplayPrefs
import com.jiligulu.app.data.prefs.CalendarProgressMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.jiligulu.app.ui.components.LedgerCard
import com.jiligulu.app.ui.components.LedgerBillRow
import com.jiligulu.app.ui.billdetail.BillDetailSheet
import com.jiligulu.app.ui.stats.charts.CashFlowBarChart
import com.jiligulu.app.ui.stats.charts.rememberCashFlowViewport
import com.jiligulu.app.ui.stats.charts.DonutChart
import com.jiligulu.app.ui.stats.charts.SpendingLineChart
import com.jiligulu.app.ui.stats.charts.SpendingLinePoint
import com.jiligulu.app.ui.theme.ExpenseCoral
import com.jiligulu.app.ui.theme.ActionPurple
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
    active: Boolean = true,
    onPageDrag: ((Float) -> Unit)? = null,
    onPageDragEnd: ((Float) -> Unit)? = null
) {
    val context = LocalContext.current
    val displayPrefs = remember(context.applicationContext) { StatsDisplayPrefs(context) }
    val savedBarMode by displayPrefs.barMode.collectAsStateWithLifecycle(initialValue = null)
    var pendingBarMode by remember { mutableStateOf<StatsBarMode?>(null) }
    var savingMode by remember { mutableStateOf(false) }
    var modeError by remember { mutableStateOf(false) }
    val barMode = pendingBarMode ?: savedBarMode ?: StatsBarMode.MONTH_COMPRESSED
    val savedProgressMode by displayPrefs.calendarProgressMode.collectAsStateWithLifecycle(initialValue = CalendarProgressMode.MONTH)
    var pendingProgressMode by remember { mutableStateOf<CalendarProgressMode?>(null) }
    val progressMode = pendingProgressMode ?: savedProgressMode
    val preferenceScope = rememberCoroutineScope()
    LaunchedEffect(savedBarMode) { if (pendingBarMode == savedBarMode) pendingBarMode = null }
    LaunchedEffect(savedProgressMode) { if (pendingProgressMode == savedProgressMode) pendingProgressMode = null }
    LaunchedEffect(barMode) { barMode.compactDays?.let(vm::setCompactDays) }
    var showTrend by rememberSaveable { mutableStateOf(false) }
    var showActual by rememberSaveable { mutableStateOf(true) }
    var showAverage by rememberSaveable { mutableStateOf(true) }
    val flowType by vm.flowType.collectAsStateWithLifecycle()
    val bars = if (showTrend)
        vm.cashFlowBars.collectAsStateWithLifecycle().value else emptyList()
    val selectedDay by vm.selectedDay.collectAsStateWithLifecycle()
    val dayDonut by vm.dayDonut.collectAsStateWithLifecycle()
    val dayDetails by vm.dayDetails.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val budget by vm.budgetUi.collectAsStateWithLifecycle()
    val averages = if (showTrend && flowType == BillType.EXPENSE)
        vm.monthlyExpenseAverages.collectAsStateWithLifecycle().value else emptyList()
    val today by vm.today.collectAsStateWithLifecycle()
    val chartAnchor by vm.chartAnchor.collectAsStateWithLifecycle()
    val chartViewport = rememberCashFlowViewport(chartAnchor)
    val chartFollowsToday by vm.chartFollowsToday.collectAsStateWithLifecycle()
    val compactVisibleWindow by vm.compactVisibleWindow.collectAsStateWithLifecycle()
    val chartMonth by vm.chartMonth.collectAsStateWithLifecycle()

    val compactBars = if (!showTrend && barMode.compactDays != null)
        vm.prefetchedCashFlowBars.collectAsStateWithLifecycle().value else emptyList()
    val monthBars = if (!showTrend && barMode == StatsBarMode.MONTH_COMPRESSED)
        vm.pagedMonthCashFlowBars.collectAsStateWithLifecycle().value else emptyList()
    val visibleBars = if (showTrend) bars else if (barMode == StatsBarMode.MONTH_COMPRESSED) monthBars else compactBars
    val averageMap = remember(averages) { averages.associateBy { it.date } }
    val linePoints = remember(bars, averageMap, today, flowType) {
        val zone = ZoneId.systemDefault()
        bars.map { bar ->
            val date = Instant.ofEpochMilli(bar.dayStartMillis).atZone(zone).toLocalDate()
            SpendingLinePoint(bar.dayStartMillis, bar.amountFen.takeIf { !date.isAfter(today) },
                averageMap[date]?.averageFen.takeIf { flowType == BillType.EXPENSE })
        }
    }

    var showBudgetDialog by rememberSaveable { mutableStateOf(false) }
    var selectedBillId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showDateFilter by rememberSaveable { mutableStateOf(false) }

    var showCategoryDetails by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        chartViewport.clearMonthBoundary()
        if (active) vm.showToday()
        vm.toggleCategory(null)
        showCategoryDetails = false
    }
    LaunchedEffect(selectedDay, flowType) { showCategoryDetails = false }
    val selectCategory: (Any?) -> Unit = { key ->
        UiSound.select(context)
        val id = key as? Long
        if (id != null && id == dayDonut.selectedCategoryId) showCategoryDetails = true
        else { showCategoryDetails = false; vm.toggleCategory(id) }
    }
    val scroll = rememberLazyListState()
    var sceneOrigin by remember { mutableStateOf(Offset.Zero) }
    var cashFlowBounds by remember { mutableStateOf<Rect?>(null) }
    var distributionBounds by remember { mutableStateOf<Rect?>(null) }
    val mainSwipe = Modifier.forwardMainPageSwipe(enabled = { active },
        onDrag = onPageDrag, onDragEnd = onPageDragEnd, allowRight = true,
        startAllowed = { local ->
            val point = local + sceneOrigin
            cashFlowBounds?.contains(point) != true && distributionBounds?.contains(point) != true
        })
    Box(Modifier.fillMaxSize().onGloballyPositioned { sceneOrigin = it.positionInWindow() }.then(mainSwipe)) {
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
                Surface(Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                    val date = Instant.ofEpochMilli(selectedDay).atZone(ZoneId.systemDefault()).toLocalDate()
                    val range = compactVisibleWindow.first to compactVisibleWindow.last
                    val acrossYears = range.first.year != range.second.year
                    fun shortDate(value: java.time.LocalDate) = (if (acrossYears) "${value.year}." else "") + "${value.monthValue}.${value.dayOfMonth}"
                    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center) {
                        androidx.compose.material3.IconButton(onClick = uiTap(com.jiligulu.app.core.audio.UiCue.NAVIGATE) {
                            chartViewport.clearMonthBoundary()
                            if (showTrend || barMode.compactDays == null) vm.shiftMonth(-1) else vm.shiftCompactWindow(-requireNotNull(barMode.compactDays))
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一统计周期", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().clickable { chartViewport.clearMonthBoundary(); UiSound.navigate(context); showDateFilter = true }
                            .semantics { contentDescription = "选择统计日期" }, contentAlignment = Alignment.Center) {
                            Text(if (showTrend) "${date.year}.${date.monthValue}"
                                else if (barMode == StatsBarMode.MONTH_COMPRESSED) "${chartMonth.year}.${chartMonth.monthValue}"
                                else "${shortDate(range.first)}${if (acrossYears) "\n" else " – "}${shortDate(range.second)}",
                                color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center, maxLines = if (acrossYears) 2 else 1)
                        }
                        androidx.compose.material3.IconButton(onClick = uiTap(com.jiligulu.app.core.audio.UiCue.NAVIGATE) {
                            chartViewport.clearMonthBoundary()
                            if (showTrend || barMode.compactDays == null) vm.shiftMonth(1) else vm.shiftCompactWindow(requireNotNull(barMode.compactDays))
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一统计周期", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Row(Modifier.height(44.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(BillType.EXPENSE to "支出", BillType.INCOME to "收入").forEach { (type, label) ->
                        Surface(onClick = uiTap(com.jiligulu.app.core.audio.UiCue.SELECT) { chartViewport.clearMonthBoundary(); vm.setFlowType(type) }, shape = RoundedCornerShape(24.dp),
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
            DisposableEffect(Unit) { onDispose { cashFlowBounds = null } }
            ChartCard(title = "每日收支", stackedAction = true, modifier = Modifier.onGloballyPositioned { cashFlowBounds = it.boundsInWindow() }, action = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        StatsBarMode.entries.forEach { mode ->
                            Surface(Modifier.clickable(enabled = savedBarMode != null && !savingMode) {
                                chartViewport.clearMonthBoundary(); showTrend = false
                                if (mode != barMode) {
                                    UiSound.select(context); pendingBarMode = mode
                                    preferenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                        savingMode = true; modeError = false
                                        try { withContext(NonCancellable) { displayPrefs.setBarMode(mode) } }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { pendingBarMode = null; modeError = true }
                                        finally { savingMode = false }
                                    }
                                }
                            }.semantics { role = Role.RadioButton; selected = barMode == mode }.testTag("stats-period-${mode.key}"),
                                shape = RoundedCornerShape(20.dp), color = if (barMode == mode) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
                                Text(mode.label, Modifier.padding(horizontal = 6.dp, vertical = 7.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Row(Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f), RoundedCornerShape(20.dp)).padding(2.dp)) {
                        listOf(false to "柱图", true to "折线").forEach { (trend, label) ->
                            Surface(onClick = uiTap(com.jiligulu.app.core.audio.UiCue.SELECT) { showTrend = trend }, shape = RoundedCornerShape(20.dp),
                                color = if (showTrend == trend) MaterialTheme.colorScheme.surface else Color.Transparent) {
                                Text(label, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall,
                                    color = if (showTrend == trend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }) {
                if (showTrend) {
                    val actualColor = if (flowType == BillType.EXPENSE) ExpenseCoral else IncomeGreen
                    val averageColor = ActionPurple
                    Box(Modifier.fillMaxWidth().height(202.dp)) {
                        SpendingLineChart(bars, linePoints, selectedDay, showActual,
                            showAverage && flowType == BillType.EXPENSE, actualColor, averageColor,
                            onSelectDay = { UiSound.select(context); vm.selectDay(it) }, modifier = Modifier.fillMaxSize())
                        Row(Modifier.align(Alignment.TopEnd).padding(end = 2.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LineVisibilityChoice("实际", showActual, actualColor) { showActual = it }
                            if (flowType == BillType.EXPENSE) LineVisibilityChoice("日均", showAverage, averageColor) { showAverage = it }
                        }
                    }
                } else CashFlowBarChart(
                    bars = visibleBars,
                    compressedMonth = barMode == StatsBarMode.MONTH_COMPRESSED,
                    anchor = chartAnchor,
                    viewport = chartViewport,
                    followToday = chartFollowsToday,
                    todayMillis = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    onCompactViewport = vm::reportCompactViewport,
                    onCompactMonthCross = vm::crossCompactMonth,
                    onMonthViewport = vm::reportMonthViewport,
                    selectedDayMillis = selectedDay,
                    onSelectDay = { UiSound.select(context); vm.selectDay(it) },
                    color = if (flowType == BillType.EXPENSE) ExpenseCoral else IncomeGreen,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(202.dp)
                )
                if (modeError) Text("没能保存，再点一次试试。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }

        // ---------- 当日分类 ----------
        item(key = "day_categories") {
            DisposableEffect(Unit) { onDispose { distributionBounds = null } }
            ChartCard(title = if (flowType == BillType.EXPENSE) "支出分布" else "收入分布",
                modifier = Modifier.onGloballyPositioned { distributionBounds = it.boundsInWindow() }) {
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
                    androidx.compose.material3.IconButton(onClick = uiTap(com.jiligulu.app.core.audio.UiCue.NAVIGATE) { showBudgetDialog = true }) {
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

        item(key = "calendar_progress") {
            val progress = remember(today, progressMode) { calendarProgress(today, progressMode) }
            LedgerCard(Modifier.testTag("stats-calendar-progress").clickable {
                chartViewport.clearMonthBoundary(); UiSound.toggle(context)
                val next = if (progressMode == CalendarProgressMode.MONTH) CalendarProgressMode.YEAR else CalendarProgressMode.MONTH
                pendingProgressMode = next
                preferenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try { withContext(NonCancellable) { displayPrefs.setCalendarProgressMode(next) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { pendingProgressMode = null }
                }
            }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (progressMode == CalendarProgressMode.MONTH) "当月已过" else "当年已过", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(progress.percentage, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                }
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp),
                    color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primaryContainer)
                Text(if (progressMode == CalendarProgressMode.MONTH) "点一下看当年" else "点一下看当月", Modifier.padding(top = 5.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    }
                    Text("${dayDonut.dayLabel} · ${dayDetails.size} 笔 · ¥${dayDonut.selectedAmountText}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    CompactChoice(listOf("时间↓", "时间↑", "金额↓", "金额↑"), sort.ordinal) { vm.setSort(DetailSort.entries[it]) }
                    val detailsScroll = rememberLazyListState()
                    val detailsEdge = remember { EdgeSpringState() }
                    Box(Modifier.fillMaxWidth()) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).edgeSpring(
                            { detailsScroll.canScrollBackward }, { detailsScroll.canScrollForward }, state = detailsEdge), state = detailsScroll) {
                            itemsIndexed(dayDetails, key = { _, bill -> bill.id }) { index, d ->
                                LedgerBillRow(icon = d.icon, colorHue = d.colorHue, categoryName = d.categoryName,
                                    title = d.detail.ifBlank { d.categoryName }, subtitle = d.timeLabel,
                                    amountText = d.amountText, isExpense = d.isExpense,
                                    onClick = { selectedBillId = d.id }, showDivider = index < dayDetails.lastIndex)
                            }
                        }
                        Box(Modifier.matchParentSize()) {
                            LedgerScrollBar(detailsScroll, Modifier.align(Alignment.CenterEnd), forceVisible = detailsEdge.visible)
                        }
                    }
                }
            }
        }
    }
    if (showBudgetDialog) {
        BudgetDialog(
            initialBudget = budget,
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
        StatsCalendarDialog(selectedDay, initialMonth = if (showTrend || barMode.compactDays == null) chartMonth else YearMonth.from(compactVisibleWindow.last),
            onDismiss = { showDateFilter = false },
            onSelect = { date, monthOnly -> vm.selectCalendarDate(date, startAtSelected = monthOnly); showDateFilter = false })
    }

}

@Composable
private fun LineVisibilityChoice(label: String, checked: Boolean, color: Color, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = { UiSound.toggle(context); onChange(it) }).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(if (checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, contentDescription = null,
            tint = if (checked) color else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (checked) color else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChartCard(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    stackedAction: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    LedgerCard(modifier) {
        if (stackedAction) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            action?.invoke()
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
        "医疗" -> Icons.Outlined.MedicalServices
        "娱乐" -> Icons.Outlined.SportsEsports
        "生活费" -> Icons.Outlined.Savings
        "转账" -> Icons.Outlined.SwapHoriz
        com.jiligulu.app.domain.category.CategoryDefaults.VACUUM_NAME -> Icons.Outlined.Inbox
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
    initialBudget: BudgetUi,
    onDismiss: () -> Unit,
    onDisable: (() -> Unit)? = null,
    onSave: (amountFen: Long, period: BudgetPeriod, anchorDay: Int) -> Unit
) {
    val context = LocalContext.current
    var amountText by rememberSaveable { mutableStateOf(initialBudget.totalText.takeIf { initialBudget.visible }.orEmpty()) }
    var period by rememberSaveable { mutableStateOf(initialBudget.period) }
    var anchorDayText by rememberSaveable { mutableStateOf(initialBudget.anchorDay.toString()) }

    val amountFen = Formatters.yuanTextToFen(amountText)
    val anchorDay = anchorDayText.toIntOrNull()?.takeIf { it in 1..28 }
    val amountInvalid = amountText.isNotBlank() && amountFen == null
    val canSave = amountFen != null && (period != BudgetPeriod.MONTHLY || anchorDay != null)

    GuluDialog("小预算", onDismiss, confirmLabel = "设好啦", dismissLabel = "取消", compact = true,
        compactWidth = 260.dp, dense = true, confirmEnabled = canSave,
        onConfirm = { amountFen?.let { if (canSave) onSave(it, period, anchorDay ?: 1) } }) {
        Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("预算", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(44.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("¥", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp))
                    BasicTextField(amountText, onValueChange = { value ->
                        if (value.matches(Regex("-?\\d{0,12}(\\.\\d{0,2})?"))) amountText = value
                    }, modifier = Modifier.weight(1f).testTag("budget-amount"), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        decorationBox = { field -> Box {
                            if (amountText.isBlank()) Text("0", style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f))
                            field()
                        } })
                }
                HorizontalDivider(Modifier.padding(top = 5.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (amountInvalid) Text("预算需要大于 0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(BudgetPeriod.MONTHLY to "每月", BudgetPeriod.WEEKLY to "每 7 天", BudgetPeriod.DAILY to "每天").forEach { (value, label) ->
                Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.medium,
                    color = if (period == value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
                    Box(Modifier.height(34.dp).clickable { UiSound.select(context); period = value }, contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelMedium,
                            color = if (period == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (period == BudgetPeriod.MONTHLY) Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("从", modifier = Modifier.width(44.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.width(38.dp)) {
                BasicTextField(anchorDayText, onValueChange = { value ->
                    if (value.length <= 2 && value.all(Char::isDigit)) anchorDayText = value
                }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("budget-anchor-day"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center))
                HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            Text(" 号开始", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text("1–28", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onDisable != null) TextButton(onClick = uiTap(com.jiligulu.app.core.audio.UiCue.TOGGLE, onDisable), modifier = Modifier.height(30.dp)) {
            Text("停用预算", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
