package com.jiligulu.app.ui.home


import com.jiligulu.app.ui.components.*
import androidx.compose.runtime.*
import com.jiligulu.app.core.util.Formatters
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.lazy.LazyListState
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiligulu.app.ui.billdetail.BillDetailSheet
import com.jiligulu.app.ui.components.LedgerBillRow
import com.jiligulu.app.ui.components.LedgerCard
import com.jiligulu.app.ui.components.PaperNote
import com.jiligulu.app.ui.theme.ExpenseCoral
import com.jiligulu.app.ui.theme.GuluTheme
import com.jiligulu.app.ui.theme.IncomeGreen

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenChat: () -> Unit,
    onAddBill: () -> Unit,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
    active: Boolean = true
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val day by vm.selectedDay.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.showToday() }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.showToday() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.jiligulu.app.JiliguluApp
    val notices by app.container.announcements.state.collectAsStateWithLifecycle()
    var selectedBillId by rememberSaveable { mutableStateOf<Long?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var refreshMessage by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = notices.loading,
        onRefresh = { scope.launch {
            app.container.announcements.refresh()
            refreshMessage = if (app.container.announcements.state.value.offline) "暂时连不上，已保留原来的信笺" else "信箱已刷新 💌"
        } }, modifier = Modifier.fillMaxSize()
    ) {
        HomeContent(state, onOpenChat, onAddBill, notices, app.container.announcements::open, day, vm::observeDay, vm::selectDay, active) { selectedBillId = it }
        refreshMessage?.let { message ->
            androidx.compose.material3.Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp)) { Text(message) }
        }
    }
    androidx.compose.runtime.LaunchedEffect(refreshMessage) { if (refreshMessage != null) { kotlinx.coroutines.delay(2500); refreshMessage = null } }
    selectedBillId?.let { BillDetailSheet(it) { selectedBillId = null } }
}

/** Pure rendering makes previews independent of the database and keeps navigation in the route. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeContent(
    state: HomeUiState,
    onOpenChat: () -> Unit,
    onAddBill: () -> Unit,
    announcements: com.jiligulu.app.data.announcement.AnnouncementState = com.jiligulu.app.data.announcement.AnnouncementState(loading = false),
    onOpenAnnouncement: (String) -> Unit = {},
    selectedDay: Long = Formatters.dayStart(System.currentTimeMillis()),
    daySource: (Long) -> kotlinx.coroutines.flow.Flow<DailyLedgerSnapshot> = { kotlinx.coroutines.flow.flowOf(DailyLedgerSnapshot(it, loaded = true)) },
    onSelectDay: (Long) -> Unit = {},
    active: Boolean = true,
    onBillClick: (Long) -> Unit
) {
    var typeFilter by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val outer = androidx.compose.foundation.lazy.rememberLazyListState()
    var activeInner by remember { mutableStateOf<LazyListState?>(null) }
    val gate = remember { StickyPullGate() }
    LaunchedEffect(active, selectedDay) { gate.reset() }
    fun pinned() = outer.firstVisibleItemIndex >= 3
    fun innerAtTop() = activeInner?.canScrollBackward != true
    val nested = remember(outer, gate) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y < 0 && outer.canScrollForward) return Offset(0f, -outer.dispatchRawDelta(-available.y))
            if (available.y > 0 && pinned() && innerAtTop() && !gate.allowExpand) { gate.blockedAtTop(); return Offset(0f, available.y) }
            return Offset.Zero
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (pinned() && innerAtTop() && !gate.allowExpand && (available.y > 0 || consumed.y > 0)) gate.blockedAtTop()
            return if (available.y > 0 && pinned() && !gate.allowExpand) Offset(0f, available.y) else Offset.Zero
        }
        override suspend fun onPreFling(available: Velocity): Velocity =
            if (available.y > 0 && pinned() && innerAtTop() && !gate.allowExpand) Velocity(0f, available.y) else Velocity.Zero
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
            if (available.y > 0 && pinned() && !gate.allowExpand) Velocity(0f, available.y) else Velocity.Zero
    } }
    val observeGesture = Modifier.pointerInput(gate) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gate.begin(pinned(), innerAtTop())
            var finalPosition = first.position
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.firstOrNull { it.id == first.id }?.let { finalPosition = it.position }
            } while (event.changes.any { it.pressed })
            val distance = finalPosition - first.position
            gate.finish(pinned(), innerAtTop(), distance.y > viewConfiguration.touchSlop && distance.y > abs(distance.x))
        }
    }
    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val viewport = maxHeight
    LazyColumn(
        state = outer,
        modifier = Modifier.fillMaxSize().testTag("home-outer").then(observeGesture).nestedScroll(nested),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp)
    ) {
        item(key = "announcements") { com.jiligulu.app.ui.announcement.AnnouncementBoard(announcements, onOpenAnnouncement) }
        item(key = "monthly_summary") { MonthlySummary(state) }
        item(key = "record_actions") {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onOpenChat,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.large,
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("对话记账")
                }
                OutlinedButton(
                    onClick = onAddBill,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.large,
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("记一笔")
                }
            }
        }
        item(key = "ledger_page") {
            Column(Modifier.fillMaxWidth().height(viewport).nestedScroll(nested)) {
                Row(Modifier.fillMaxWidth().testTag("home-ledger-heading").padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("每日账单", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    CompactChoice(listOf("全部收支", "只看支出", "只看收入"), typeFilter) { typeFilter = it }
                    CompactChoice(listOf("时间↓", "时间↑", "金额↓", "金额↑"), sort) { sort = it }
                }
                DayPager(selectedDay, Formatters.dayStart(System.currentTimeMillis()), onSelectDay,
                    modifier = Modifier.fillMaxWidth().weight(1f), tag = "home-day-pager") { pageDay ->
                    val flow = remember(pageDay, daySource) { daySource(pageDay) }
                    val snapshot by rememberPageData(flow, DailyLedgerSnapshot(pageDay))
                    val rows = remember(snapshot, typeFilter, sort) { filterHomeBills(snapshot.bills, pageDay, typeFilter, sort) }
                    val inner = androidx.compose.foundation.lazy.rememberLazyListState()
                    SideEffect { if (pageDay == selectedDay) activeInner = inner }
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background, RoundedCornerShape(18.dp))
                        .padding(horizontal = 4.dp).testTag("home-page-$pageDay")) {
                        DayBrowser(pageDay, onSelectDay)
                        DailyTotals(snapshot.bills, rows.size)
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            LazyColumn(Modifier.fillMaxSize().testTag(if (pageDay == selectedDay) "home-day-bills" else "home-neighbor-bills"), state = inner) {
                                if (rows.isEmpty()) item {
                                    Column(Modifier.fillParentMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center) {
                                        Text("♡", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.height(12.dp))
                                        Text(if (snapshot.loaded) "这一天还没有符合筛选的账单" else "阿噜正在翻开账本…", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                                        Spacer(Modifier.height(8.dp))
                                        Text("左右翻一页，生活慢慢记。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                } else itemsIndexed(rows, key = { _, bill -> bill.id }) { index, bill ->
                                    val shape = RoundedCornerShape(topStart = if (index == 0) 16.dp else 0.dp, topEnd = if (index == 0) 16.dp else 0.dp,
                                        bottomStart = if (index == rows.lastIndex) 16.dp else 0.dp, bottomEnd = if (index == rows.lastIndex) 16.dp else 0.dp)
                                    LedgerBillRow(icon = bill.icon, colorHue = bill.colorHue, title = bill.title, categoryName = bill.categoryName,
                                        subtitle = bill.subtitle, amountText = bill.amountText, isExpense = bill.isExpense,
                                        onClick = { onBillClick(bill.id) }, modifier = Modifier.clip(shape), showDivider = index < rows.lastIndex)
                                }
                            }
                            LedgerScrollBar(inner, Modifier.align(Alignment.CenterEnd))
                        }
                    }
                }
            }
        }
    }
    if (outer.canScrollForward) LedgerScrollBar(outer, Modifier.align(Alignment.CenterEnd))
    }
    }
}

@Composable
private fun DailyTotals(bills: List<BillUi>, visibleCount: Int) {
    androidx.compose.material3.Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("当日支出", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("¥${Formatters.fenToYuanText(bills.filter { it.isExpense }.sumOf { it.entity.amountFen })}",
                    style = MaterialTheme.typography.titleSmall, color = ExpenseCoral, maxLines = 1)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("当日收入", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("¥${Formatters.fenToYuanText(bills.filter { !it.isExpense }.sumOf { it.entity.amountFen })}",
                    style = MaterialTheme.typography.titleSmall, color = IncomeGreen, maxLines = 1)
            }
            androidx.compose.material3.Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f)) {
                Text("$visibleCount 笔", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun MonthlySummary(state: HomeUiState) {
    LedgerCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(state.monthLabel, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            PaperNote("认真生活\n慢慢记录 ♡")
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SummaryAmount("支出", state.expenseText, state.expenseCount, true, Modifier.weight(1f))
            Spacer(Modifier.width(1.dp).height(72.dp).background(MaterialTheme.colorScheme.outline))
            SummaryAmount("收入", state.incomeText, state.incomeCount, false, Modifier.weight(1f))
        }
        Text("本月结余 ¥${state.balanceText}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun SummaryAmount(label: String, amount: String, count: Int, expense: Boolean, modifier: Modifier) {
    val decimal = amount.substringBefore('.') + "." + amount.substringAfter('.', "").padEnd(2, '0')
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text("¥$decimal", style = MaterialTheme.typography.headlineSmall,
            color = if (expense) ExpenseCoral else IncomeGreen, softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()))
        Text("共 $count 笔", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(name = "奶油手账", widthDp = 360, heightDp = 560, showBackground = true)
@Composable
private fun HomePreview() {
    GuluTheme(darkTheme = false) {
        HomeContent(HomeUiState(monthLabel = "2026年9月"), {}, {}, onBillClick = {})
    }
}
