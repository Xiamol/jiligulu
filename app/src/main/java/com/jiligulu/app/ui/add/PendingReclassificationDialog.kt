package com.jiligulu.app.ui.add

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.ui.components.GuluDialog
import com.jiligulu.app.ui.components.CategoryBadge

/** A bounded list with a fixed confirmation footer; no bill is changed while suggestions arrive. */
@Composable
internal fun PendingReclassificationDialog(
    state: PendingReclassificationState, onDismiss: () -> Unit, onConfirm: (Set<Long>) -> Unit
) {
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var seen by remember { mutableStateOf(emptySet<Long>()) }
    LaunchedEffect(state.proposals) {
        val current = state.proposals.map { it.original.id }.toSet()
        selected = (selected + (current - seen)).intersect(current)
        seen = current
    }
    GuluDialog("把待定收拾好", onDismiss = onDismiss, compact = true, dense = true, busy = state.saving,
        confirmLabel = if (state.result != null) "收好啦" else "重新分类 (${selected.size})",
        dismissLabel = if (state.result == null) "先等等" else null,
        confirmEnabled = state.result != null || (!state.loading && selected.isNotEmpty()),
        onConfirm = { if (state.result != null) onDismiss() else onConfirm(selected) }) {
        state.result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) } ?: run {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                Text(if (state.loading) "分类中 ${state.processed}/${state.total}"
                    else "${state.total} 笔待定 · ${state.proposals.size} 笔有了建议",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.proposals.forEach { item ->
                Row(Modifier.fillMaxWidth().testTag("pending-reclass-${item.original.id}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Checkbox(item.original.id in selected, onCheckedChange = { checked ->
                        selected = if (checked) selected + item.original.id else selected - item.original.id
                    }, enabled = !state.loading && !state.saving, modifier = Modifier.size(30.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.original.detail.ifBlank { "这笔账" }, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((if (item.original.type.name == "INCOME") "+" else "−") + "¥" +
                            Formatters.fenToYuanText(item.original.amountFen), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CategoryBadge(item.suggestion.category, item.suggestion.iconEmoji, size = 24.dp)
                    Text(item.suggestion.category, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.widthIn(max = 78.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            if (!state.loading) Text(when {
                state.total == 0 -> "待定里没有账单，已经整整齐齐啦 ♡"
                state.proposals.isEmpty() -> "还没有确定的分类，账单会继续留在待定。"
                state.total > state.proposals.size -> "${state.total - state.proposals.size} 笔还不确定，先留在待定。"
                else -> "确认只更换分类，金额、日期和夹的照片都保留。"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
