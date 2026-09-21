package com.jiligulu.app.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.ui.persona.GuluMascot
import com.jiligulu.app.ui.components.BillDateTimeField
import com.jiligulu.app.ui.theme.ExpenseGreen
import com.jiligulu.app.ui.theme.IncomeRed
import com.jiligulu.app.ui.theme.GuluBrandFont
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Persistent conversation with soft lavender bubbles and editable ledger draft cards. */
@Composable
fun ChatScreen(
    onBack: () -> Unit = {},
    vm: ChatViewModel = viewModel(factory = ChatViewModel.Factory)
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    val sendInput = {
        if (input.isNotBlank() && ready && !sending) {
            vm.send(input)
            input = ""
        }
    }

    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding()
    ) {
        // ---------- 顶栏 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 4.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text("叽里咕噜", style = MaterialTheme.typography.titleLarge,
                    fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.primary)
                Text(
                    "陪你记下生活 · 对话自动保存 ♡",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            GuluMascot(Modifier.size(44.dp))
        }

        // ---------- 消息流 ----------
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(items, key = { it.id }) { item ->
                // 设计稿动效③：对话气泡从底部上滑+渐显
                Box(Modifier.animateItem()) {
                    when (item) {
                        is ChatItem.UserMsg -> UserBubble(item.text)
                        is ChatItem.GuluMsg -> GuluBubble(item)
                        is ChatItem.DraftCard -> DraftCardView(
                            card = item,
                            categories = categories,
                            onUpdate = { index, transform -> vm.updateDraft(item.id, index, transform) },
                            onConfirm = { vm.confirmCard(item.id) },
                            onCancel = { vm.cancelCard(item.id) }
                        )
                    }
                }
            }
        }

        if (error != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (!ready) TextButton(onClick = vm::loadHistory) { Text("重试") }
            }
        }

        // ---------- 输入栏 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (sending) "咕噜正在整理这笔账…" else "比如：昨天中午吃饭 9 元",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                singleLine = true,
                enabled = ready,
                shape = MaterialTheme.shapes.extraLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendInput() })
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = sendInput,
                enabled = input.isNotBlank() && ready && !sending,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }
    }
}

}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun GuluBubble(msg: ChatItem.GuluMsg) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        GuluMascot(Modifier.size(34.dp))
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(5.dp, 18.dp, 18.dp, 18.dp))
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(5.dp, 18.dp, 18.dp, 18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (msg.loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(msg.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DraftCardView(
    card: ChatItem.DraftCard,
    categories: List<CategoryEntity>,
    onUpdate: (Int, (DraftUi) -> DraftUi) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val editing = card.status == ChatItem.Status.EDITING
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 设计稿动效④：草稿卡展开/收起高度动画
            .animateContentSize()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
            .border(0.7.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .padding(16.dp)
    ) {
        Text(
            when (card.status) {
                ChatItem.Status.CONFIRMED -> "✓ 已记入账本 · ${card.savedCount} 笔"
                ChatItem.Status.CANCELLED -> "这张草稿已收起"
                else -> "帮你整理好啦"
            },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "「${card.rawInput}」",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))

        if (card.status == ChatItem.Status.CONFIRMED || card.status == ChatItem.Status.CANCELLED) {
            card.drafts.filter { it.checked }.forEach { draft ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(draft.iconEmoji.ifBlank { "🧾" }, style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f)) {
                        Text(draft.detail.ifBlank { draft.categoryName }, style = MaterialTheme.typography.bodyMedium)
                        draft.timestamp?.let { timestamp ->
                            Text(
                                Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text("${if (draft.type == BillType.EXPENSE) "−" else "+"}¥${draft.amountText}",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (draft.type == BillType.EXPENSE) ExpenseGreen else IncomeRed)
                }
            }
            return@Column
        }

        card.drafts.forEachIndexed { index, draft ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = draft.checked,
                    onCheckedChange = { c -> onUpdate(index) { d -> d.copy(checked = c) } },
                    enabled = editing
                )
                        OutlinedTextField(
                            value = draft.amountText,
                            onValueChange = { v ->
                                onUpdate(index) { d -> d.copy(amountText = v.filter { c -> c.isDigit() || c == '.' }) }
                            },
                            modifier = Modifier.weight(1f),
                            prefix = { Text("¥") },
                            placeholder = { Text("金额") },
                            singleLine = true,
                            enabled = editing,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (draft.type == BillType.EXPENSE) "支出" else "收入",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (draft.type == BillType.EXPENSE) ExpenseGreen else IncomeRed
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = draft.detail,
                        onValueChange = { v -> onUpdate(index) { d -> d.copy(detail = v) } },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("细则（如：牛肉面）") },
                        singleLine = true,
                        enabled = editing
                    )
                    Spacer(Modifier.height(8.dp))
                    BillDateTimeField(
                        timestamp = draft.timestamp,
                        onTimestampChange = { timestamp ->
                            onUpdate(index) { it.copy(timestamp = timestamp, timeNeedsReview = false,
                                timeHint = if (timestamp == null) "确认入账时记录此刻" else "已手动调整时间") }
                        },
                        enabled = editing,
                        allowCurrentTime = !draft.timeNeedsReview,
                        label = if (draft.timeNeedsReview) "请先选择账单时间" else "账单时间"
                    )
                    Text(draft.timeHint, style = MaterialTheme.typography.labelSmall,
                        color = if (draft.timeNeedsReview) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    // 分类选择：现有分类 + AI 建议的新分类
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        categories.forEach { c ->
                            FilterChip(
                                selected = draft.categoryName.equals(c.name, true),
                                onClick = { onUpdate(index) { d -> d.copy(categoryName = c.name, isNewCategory = false) } },
                                label = { Text("${c.iconValue} ${c.name}", style = MaterialTheme.typography.labelMedium) },
                                enabled = editing
                            )
                        }
                        if (draft.isNewCategory && categories.none { it.name.equals(draft.categoryName, true) }) {
                            FilterChip(
                                selected = true,
                                onClick = {},
                                label = { Text("${draft.iconEmoji.ifBlank { "🆕" }} ${draft.categoryName}·新", style = MaterialTheme.typography.labelMedium) },
                                enabled = editing
                            )
                        }
                    }
            }
            if (index < card.drafts.size - 1) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(14.dp))
            }
        }

        Spacer(Modifier.height(10.dp))
        when (card.status) {
            ChatItem.Status.EDITING -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCancel) { Text("取消") }
                    Spacer(Modifier.weight(1f))
                    val selected = card.drafts.filter { it.checked }
                    val validCount = selected.size
                    Button(
                        onClick = onConfirm,
                        enabled = validCount > 0 && selected.all { it.isValid }
                    ) {
                        Text("确认记账（$validCount）")
                    }
                }
            }
            ChatItem.Status.SAVING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在放进账本…", style = MaterialTheme.typography.bodySmall)
            }
            ChatItem.Status.CONFIRMED -> Text(
                "已入库 ${card.savedCount} 条",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ChatItem.Status.CANCELLED -> Text(
                "已取消",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
