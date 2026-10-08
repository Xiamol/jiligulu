package com.jiligulu.app.ui.add

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.ui.components.GuluDialog
import com.jiligulu.app.ui.components.CategoryBadge
import com.jiligulu.app.ui.components.SpringLazyColumn
import com.jiligulu.app.domain.category.CategorySuggestions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A bounded list with a fixed confirmation footer; no bill is changed while suggestions arrive. */
@Composable
internal fun PendingReclassificationDialog(
    state: PendingReclassificationState, onDismiss: () -> Unit, onConfirm: (Set<Long>) -> Unit,
    onRetry: () -> Unit = {}
) {
    // Default new arrivals to selected without an asynchronous effect that can undo a tap.
    var deselected by remember { mutableStateOf(emptySet<Long>()) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    val bills = state.bills.ifEmpty { state.proposals.map { it.original } }
    val billIds = remember(bills) { bills.map { it.id }.toSet() }
    val selected = billIds - deselected
    val proposals = remember(state.proposals) { state.proposals.associateBy { it.original.id } }
    val ready = selected.intersect(proposals.keys)
    val newNames = state.proposals.filter { it.original.id in ready && it.targetCategoryId == null }
        .map { it.suggestion.category }.distinctBy(CategorySuggestions::key)
    GuluDialog("把待定收拾好", onDismiss = onDismiss, compact = true, dense = true, busy = state.saving,
        confirmLabel = if (state.result != null) "收好啦" else "确认 ${ready.size} 笔",
        dismissLabel = if (state.result == null) "先等等" else null,
        confirmEnabled = state.result != null || ready.isNotEmpty(),
        onConfirm = { if (state.result != null) onDismiss() else onConfirm((billIds - deselected).intersect(proposals.keys)) }) {
        state.result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) } ?: run {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                Text(if (state.loading) "正在建议 ${state.processed}/${state.total}"
                    else "${state.total} 笔待定 · 可分类 ${ready.size} 笔",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRetry, enabled = !state.loading && !state.saving,
                    contentPadding = PaddingValues(horizontal = 3.dp), modifier = Modifier.height(30.dp)) { Text("再建议") }
            }
            if (bills.isNotEmpty()) SpringLazyColumn(Modifier.fillMaxWidth()
                .height(minOf(248, bills.size * 62).dp).testTag("pending-bills-preview"), handOffOnRepeat = true) {
            items(bills, key = { it.id }) { bill ->
                val item = proposals[bill.id]
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).testTag("pending-reclass-${bill.id}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(40.dp).testTag("pending-select-${bill.id}").toggleable(
                        value = bill.id in selected, enabled = !state.saving, role = Role.Checkbox,
                        onValueChange = { checked ->
                            deselected = if (checked) deselected - bill.id else deselected + bill.id
                        }), contentAlignment = Alignment.Center) {
                        Checkbox(bill.id in selected, onCheckedChange = null, modifier = Modifier.size(24.dp))
                    }
                    Column(Modifier.weight(1f).clickable { expandedId = if (expandedId == bill.id) null else bill.id }) {
                        Text(bill.detail.ifBlank { "这笔账" }, style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (expandedId == bill.id) 4 else 1, overflow = TextOverflow.Ellipsis)
                        Text((if (bill.type.name == "INCOME") "+" else "−") + "¥" +
                            Formatters.fenToYuanText(bill.amountFen) + " · " + Instant.ofEpochMilli(bill.timestamp)
                                .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d HH:mm")),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (expandedId == bill.id && bill.note.isNotBlank()) Text(bill.note,
                            maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item != null) CategoryBadge(item.suggestion.category, item.suggestion.iconEmoji, size = 22.dp)
                    Text(item?.suggestion?.category?.let { it + if (item?.targetCategoryId == null) "·新" else "" } ?: "待定",
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(max = 78.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            }
            // Keep the dialog and its touch targets still when new-category metadata arrives.
            Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.CenterStart) {
                val summary = if (newNames.isNotEmpty()) "将创建：${newNames.joinToString("、")}" else when {
                    state.total == 0 -> "待定里没有账单，已经整整齐齐啦 ♡"
                    state.total > state.proposals.size -> "${state.total - state.proposals.size} 笔未匹配，先留在待定"
                    else -> "选好后确认，金额和日期保留"
                }
                Text(summary + state.error?.let { "\n$it" }.orEmpty(), maxLines = 2,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (newNames.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (newNames.isNotEmpty()) Modifier.testTag("pending-new-categories") else Modifier)
            }
        }
    }
}
