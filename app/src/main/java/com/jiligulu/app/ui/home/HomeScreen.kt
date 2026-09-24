package com.jiligulu.app.ui.home

import com.jiligulu.app.ui.components.*
import androidx.compose.runtime.*
import com.jiligulu.app.core.util.Formatters
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
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
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val day by vm.selectedDay.collectAsStateWithLifecycle()
    val daily by vm.dailyLedger.collectAsStateWithLifecycle()
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
        HomeContent(state, onOpenChat, onAddBill, notices, app.container.announcements::open, day, daily, vm::selectDay) { selectedBillId = it }
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
    daily: DailyLedgerSnapshot = DailyLedgerSnapshot(selectedDay, loaded = true),
    onSelectDay: (Long) -> Unit = {},
    onBillClick: (Long) -> Unit
) {
    var typeFilter by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val outer = androidx.compose.foundation.lazy.rememberLazyListState()
    val inner = androidx.compose.foundation.lazy.rememberLazyListState()
    val latest by rememberUpdatedState(daily)
    var shown by remember { mutableStateOf(daily) }
    var shownType by remember { mutableIntStateOf(typeFilter) }
    var shownSort by remember { mutableIntStateOf(sort) }
    var initialized by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    val opacity = remember { Animatable(1f) }
    val slide = remember { Animatable(0f) }
    LaunchedEffect(daily) {
        if (!changing && daily.loaded && daily.day == shown.day && daily.day == selectedDay && shownType == typeFilter && shownSort == sort) {
            shown = daily
        }
    }
    LaunchedEffect(selectedDay, typeFilter, sort) {
        if (!initialized) { initialized = true; return@LaunchedEffect }
        changing = true
        val direction = if (selectedDay < shown.day) -1 else 1
        opacity.animateTo(0f, tween(120))
        // Keep the old rows while resetting scroll so a shorter day cannot clamp the viewport.
        coroutineScope {
            launch { outer.animateScrollToItem(3) }
            launch {
                // An empty day has no inner LazyColumn: never wait for a layout that does not exist.
                if (filterHomeBills(shown.bills, shown.day, shownType, shownSort).isNotEmpty()) inner.animateScrollToItem(0)
            }
        }
        shown = snapshotFlow { latest }.first { it.loaded && it.day == selectedDay }
        shownType = typeFilter; shownSort = sort
        slide.snapTo(direction * 24f)
        coroutineScope {
            launch { opacity.animateTo(1f, tween(240)) }
            launch { slide.animateTo(0f, tween(280)) }
        }
        if (latest.loaded && latest.day == selectedDay) shown = latest
        changing = false
    }
    val filtered = remember(shown, shownType, shownSort) { filterHomeBills(shown.bills, shown.day, shownType, shownSort) }
    val collapseHeader = remember(outer) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            return if (available.y < 0 && outer.canScrollForward) Offset(0f, -outer.dispatchRawDelta(-available.y)) else Offset.Zero
        }
    } }
    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val viewport = maxHeight
    LazyColumn(
        state = outer,
        modifier = Modifier.fillMaxSize().testTag("home-outer").daySwipe(selectedDay,
            { onSelectDay(shiftLocalDay(selectedDay, -1)) }, { onSelectDay(shiftLocalDay(selectedDay, 1)) }),
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
            Column(Modifier.fillMaxWidth().height(viewport).nestedScroll(collapseHeader)) {
                Column(Modifier.fillMaxWidth().testTag("home-ledger-heading").padding(top = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("每日账单", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        CompactChoice(listOf("全部收支", "只看支出", "只看收入"), typeFilter) { typeFilter = it }
                        CompactChoice(listOf("时间↓", "时间↑", "金额↓", "金额↑"), sort) { sort = it }
                    }
                    DayBrowser(selectedDay, onSelectDay)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("支出 ¥${Formatters.fenToYuanText(shown.bills.filter { it.isExpense }.sumOf { it.entity.amountFen })}", color = ExpenseCoral, style = MaterialTheme.typography.labelMedium)
                        Text("收入 ¥${Formatters.fenToYuanText(shown.bills.filter { !it.isExpense }.sumOf { it.entity.amountFen })}", color = IncomeGreen, style = MaterialTheme.typography.labelMedium)
                        Text("${filtered.size} 笔", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f).testTag(if (changing) "home-transitioning" else "home-ready").clip(MaterialTheme.shapes.large)
                    .graphicsLayer { alpha = opacity.value; translationX = slide.value * density }) {
                    if (filtered.isEmpty()) {
                        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            Text("♡", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(12.dp))
                            Text(if (shown.loaded) "这一天还没有符合筛选的账单" else "阿噜正在翻开账本…", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(8.dp))
                            Text("左右滑动换一天，生活慢慢记就好。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    } else LazyColumn(Modifier.fillMaxSize().testTag("home-day-bills"), state = inner, userScrollEnabled = !changing) {
                        itemsIndexed(filtered, key = { _, bill -> bill.id }) { index, bill ->
                            val shape = RoundedCornerShape(topStart = if (index == 0) 16.dp else 0.dp, topEnd = if (index == 0) 16.dp else 0.dp,
                                bottomStart = if (index == filtered.lastIndex) 16.dp else 0.dp, bottomEnd = if (index == filtered.lastIndex) 16.dp else 0.dp)
                            LedgerBillRow(icon = bill.icon, colorHue = bill.colorHue, title = bill.title, categoryName = bill.categoryName,
                                subtitle = bill.subtitle, amountText = bill.amountText, isExpense = bill.isExpense,
                                onClick = { if (!changing) onBillClick(bill.id) }, modifier = Modifier.clip(shape), showDivider = index < filtered.lastIndex)
                        }
                    }
                }
            }
        }
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
