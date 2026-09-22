package com.jiligulu.app.ui.trash

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiligulu.app.ui.theme.ExpenseGreen
import com.jiligulu.app.ui.theme.GuluBrandFont
import com.jiligulu.app.ui.theme.IncomeRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回收站页面。
 *
 * 交互按 coder 定稿：卡片列表（按删除时间倒序）→ 点选/取消勾选 → 底部「恢复所选 / 取消」。
 * 恢复和彻底删除都只作用于勾选项，默认什么都不选。
 */
@Composable
fun TrashScreen(
    onBack: () -> Unit = {},
    vm: TrashViewModel = viewModel(factory = TrashViewModel.Factory)
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var confirmPurge by rememberSaveable { mutableStateOf(false) }
    var showRetention by rememberSaveable { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
        ) {
            TrashHeader(
                count = state.items.size,
                allSelected = state.allSelected,
                onBack = onBack,
                onToggleAll = vm::toggleAll,
                onRetention = { showRetention = true }
            )

            Box(Modifier.weight(1f)) {
                when {
                    state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    state.items.isEmpty() -> EmptyTrash()

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.items, key = { it.id }) { item ->
                            TrashBillCard(
                                item = item,
                                selected = item.id in state.selected,
                                enabled = !state.isWorking,
                                onClick = { vm.toggle(item.id) }
                            )
                        }
                        item {
                            Text(
                                "回收站保留 ${TrashViewModel.retentionLabel(state.retentionDays)}，" +
                                    "过期会自动清理。想留住的话，先恢复回账本。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp)
                            )
                        }
                    }
                }
            }

            state.error?.let { Text(it, Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            state.message?.let { CleanSnackbar(it, vm::consumeMessage) }

            TrashActionBar(
                selectedCount = state.selected.size,
                isWorking = state.isWorking,
                hasItems = state.items.isNotEmpty(),
                onRestore = vm::restoreSelected,
                onPurge = { confirmPurge = true },
                onCancelSelection = vm::clearSelection
            )
        }
    }

    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { confirmPurge = false },
            title = { Text("彻底删掉这 ${state.selected.size} 笔？") },
            text = { Text("这些账单会永久消失，谁都找不回来了。如果只是想眼不见为净，留在回收站就行。") },
            confirmButton = {
                TextButton(
                    onClick = { confirmPurge = false; vm.purgeSelected() },
                    modifier = Modifier.testTag("trash-confirm-purge")
                ) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text("再想想") } }
        )
    }

    if (showRetention) {
        RetentionDialog(
            current = state.retentionDays,
            onDismiss = { showRetention = false },
            onPick = { days -> vm.setRetentionDays(days); showRetention = false }
        )
    }
}

@Composable
private fun TrashHeader(
    count: Int,
    allSelected: Boolean,
    onBack: () -> Unit,
    onToggleAll: () -> Unit,
    onRetention: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Column(Modifier.weight(1f)) {
            Text(
                "回收站",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal
                ),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                if (count == 0) "空空如也，真好" else "$count 笔暂时收在这里",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (count > 0) {
            TextButton(onClick = onToggleAll, modifier = Modifier.testTag("trash-toggle-all")) {
                Text(if (allSelected) "全不选" else "全选")
            }
        }
        TextButton(onClick = onRetention) { Text("保留期") }
    }
}

/** 一张回收站账单卡：整卡可点，勾选状态同步显示。 */
@Composable
private fun TrashBillCard(
    item: TrashItemUi,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .testTag("trash-card-${item.id}"),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )
    ) {
        Row(
            Modifier.padding(end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onClick() },
                enabled = enabled
            )
            Text(item.icon, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    item.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "删除于 " + formatDeletedAt(item.deletedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                item.amountText,
                style = MaterialTheme.typography.titleSmall,
                color = if (item.isExpense) ExpenseGreen else IncomeRed
            )
        }
    }
}

@Composable
private fun EmptyTrash() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🗑️", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text("回收站是空的", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "删掉的账单会先来这里待一阵子，后悔了随时能捞回来。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 底部操作区：按选中数量决定按钮可用性与文案。 */
@Composable
private fun TrashActionBar(
    selectedCount: Int,
    isWorking: Boolean,
    hasItems: Boolean,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
    onCancelSelection: () -> Unit
) {
    if (!hasItems) return
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                if (selectedCount == 0) "点卡片就能勾选" else "已选 $selectedCount 笔",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onCancelSelection,
                    enabled = !isWorking && selectedCount > 0,
                    modifier = Modifier.testTag("trash-cancel-selection")
                ) { Text("取消") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = onPurge,
                    enabled = !isWorking && selectedCount > 0,
                    modifier = Modifier.testTag("trash-purge")
                ) { Text("彻底删除", color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = onRestore,
                    enabled = !isWorking && selectedCount > 0,
                    modifier = Modifier.testTag("trash-restore")
                ) { Text(if (isWorking) "处理中…" else "恢复所选") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RetentionDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("回收站保留多久？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "超过保留期的账单会被自动清掉，之后再也找不回来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrashViewModel.RETENTION_OPTIONS.forEach { days ->
                        FilterChip(
                            selected = days == current,
                            onClick = { onPick(days) },
                            label = { Text(TrashViewModel.retentionLabel(days),
                                style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } }
    )
}

@Composable
private fun CleanSnackbar(message: String, onDismiss: () -> Unit) {
    LaunchedEffect(message) {
        kotlinx.coroutines.delay(2_600)
        onDismiss()
    }
    Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                message,
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

private fun formatDeletedAt(millis: Long): String {
    if (millis <= 0L) return "刚刚"
    val elapsed = System.currentTimeMillis() - millis
    return when {
        elapsed in 0 until 60_000L -> "刚刚"
        elapsed in 0 until 3_600_000L -> "${elapsed / 60_000L} 分钟前"
        elapsed in 0 until 86_400_000L -> "${elapsed / 3_600_000L} 小时前"
        else -> SimpleDateFormat("M 月 d 日 HH:mm", Locale.CHINA).format(Date(millis))
    }
}
