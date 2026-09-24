package com.jiligulu.app.ui.home

import com.jiligulu.app.ui.components.*
import androidx.compose.runtime.*
import com.jiligulu.app.core.util.Formatters
import kotlinx.coroutines.launch
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
    val daily by vm.dailyBills.collectAsStateWithLifecycle()
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
@Composable
private fun HomeContent(
    state: HomeUiState,
    onOpenChat: () -> Unit,
    onAddBill: () -> Unit,
    announcements: com.jiligulu.app.data.announcement.AnnouncementState = com.jiligulu.app.data.announcement.AnnouncementState(loading = false),
    onOpenAnnouncement: (String) -> Unit = {},
    selectedDay: Long = Formatters.dayStart(System.currentTimeMillis()),
    dailyBills: List<BillUi> = emptyList(),
    onSelectDay: (Long) -> Unit = {},
    onBillClick: (Long) -> Unit
) {
    var typeFilter by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val filtered = remember(dailyBills, selectedDay, typeFilter, sort) {
        filterHomeBills(dailyBills, selectedDay, typeFilter, sort)
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(selectedDay, typeFilter, sort) { if (listState.firstVisibleItemIndex >= 3) listState.scrollToItem(3) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().daySwipe(selectedDay, { onSelectDay(shiftLocalDay(selectedDay, -1)) }, { onSelectDay(shiftLocalDay(selectedDay, 1)) }),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp)
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
        item(key = "ledger_heading") {
            Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("每日账单", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    CompactChoice(listOf("全部收支", "只看支出", "只看收入"), typeFilter) { typeFilter = it }
                    CompactChoice(listOf("时间↓", "时间↑", "金额↓", "金额↑"), sort) { sort = it }
                }
                DayBrowser(selectedDay, onSelectDay)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("支出 ¥${Formatters.fenToYuanText(dailyBills.filter { it.isExpense && Formatters.dayStart(it.entity.timestamp) == selectedDay }.sumOf { it.entity.amountFen })}", color = ExpenseCoral, style = MaterialTheme.typography.labelMedium)
                    Text("收入 ¥${Formatters.fenToYuanText(dailyBills.filter { !it.isExpense && Formatters.dayStart(it.entity.timestamp) == selectedDay }.sumOf { it.entity.amountFen })}", color = IncomeGreen, style = MaterialTheme.typography.labelMedium)
                    Text("${filtered.size} 笔", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (filtered.isEmpty()) {
            item(key = "empty_day") {
                LedgerCard { Text("这一天还没有符合筛选的账单 ♡", style = MaterialTheme.typography.bodyMedium)
                    Text("左右滑动换一天，也可以点日期直接跳转。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        } else itemsIndexed(filtered, key = { _, bill -> bill.id }) { index, bill ->
            val shape = RoundedCornerShape(
                topStart = if (index == 0) 16.dp else 0.dp, topEnd = if (index == 0) 16.dp else 0.dp,
                bottomStart = if (index == filtered.lastIndex) 16.dp else 0.dp, bottomEnd = if (index == filtered.lastIndex) 16.dp else 0.dp)
            LedgerBillRow(icon = bill.icon, colorHue = bill.colorHue, title = bill.title, categoryName = bill.categoryName,
                subtitle = bill.subtitle, amountText = bill.amountText, isExpense = bill.isExpense,
                onClick = { onBillClick(bill.id) }, modifier = Modifier.animateItem().clip(shape), showDivider = index < filtered.lastIndex)
        }
        item(key = "ledger_end") {
            Text("每一笔，都算数。♡",
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
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
