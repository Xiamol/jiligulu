package com.jiligulu.app.ui.home

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
import com.jiligulu.app.ui.theme.ExpenseGreen
import com.jiligulu.app.ui.theme.GuluTheme
import com.jiligulu.app.ui.theme.IncomeRed

@Composable
fun HomeScreen(
    onOpenChat: () -> Unit,
    onAddBill: () -> Unit,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var selectedBillId by rememberSaveable { mutableStateOf<Long?>(null) }
    HomeContent(state, onOpenChat, onAddBill) { selectedBillId = it }
    selectedBillId?.let { BillDetailSheet(it) { selectedBillId = null } }
}

/** Pure rendering makes previews independent of the database and keeps navigation in the route. */
@Composable
private fun HomeContent(
    state: HomeUiState,
    onOpenChat: () -> Unit,
    onAddBill: () -> Unit,
    onBillClick: (Long) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp)
    ) {
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
            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("最近账单", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("本月 ${state.expenseCount + state.incomeCount} 笔",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.days.isEmpty()) {
            item(key = "empty_ledger") {
                LedgerCard {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.AutoMirrored.Outlined.ReceiptLong, null, modifier = Modifier.size(30.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(10.dp))
                        Text("从一笔小事开始", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("早餐、咖啡，或今天的一份小开心。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onOpenChat) { Text("试着说：早餐花了 12 元") }
                    }
                }
            }
        } else {
            state.days.forEach { day ->
                item(key = "day_${day.dayLabel}") {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(day.dayLabel, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("支出 ¥${day.dayExpenseText}", style = MaterialTheme.typography.labelMedium,
                            color = ExpenseGreen)
                    }
                }
                itemsIndexed(day.bills, key = { _, bill -> bill.id }) { index, bill ->
                    val shape = RoundedCornerShape(
                        topStart = if (index == 0) 16.dp else 0.dp,
                        topEnd = if (index == 0) 16.dp else 0.dp,
                        bottomEnd = if (index == day.bills.lastIndex) 16.dp else 0.dp,
                        bottomStart = if (index == day.bills.lastIndex) 16.dp else 0.dp
                    )
                    LedgerBillRow(
                        icon = bill.icon, colorHue = bill.colorHue, title = bill.title, categoryName = bill.categoryName,
                        subtitle = bill.subtitle, amountText = bill.amountText, isExpense = bill.isExpense,
                        onClick = { onBillClick(bill.id) },
                        modifier = Modifier.animateItem().clip(shape),
                        showDivider = index < day.bills.lastIndex
                    )
                }
                item(key = "day_space_${day.dayLabel}") { Spacer(Modifier.height(12.dp)) }
            }
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
            color = if (expense) ExpenseGreen else IncomeRed, softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()))
        Text("共 $count 笔", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(name = "奶油手账", widthDp = 360, heightDp = 560, showBackground = true)
@Composable
private fun HomePreview() {
    GuluTheme(darkTheme = false) {
        HomeContent(HomeUiState(monthLabel = "2026年9月"), {}, {}, {})
    }
}
