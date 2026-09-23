package com.jiligulu.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jiligulu.app.ui.components.GuluDialog

@Composable
fun ConversationSettingsCard(vm: SettingsViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    val busy = state.isClearingHistory
    LaunchedEffect(state.historyMessage, busy) {
        if (!busy && state.historyMessage != null) confirm = false
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("历史对话", style = MaterialTheme.typography.bodyLarge)
            Text("清理聊天，账本和未入账草稿会留下",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { confirm = true }, enabled = !busy,
            modifier = Modifier.testTag("clear-history-entry")) { Text("清空") }
    }
    state.historyMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    if (confirm) GuluDialog(title = "给聊天腾个小空位？", onDismiss = { confirm = false },
        confirmLabel = "清空对话", dismissLabel = "先留着", busy = busy,
        onConfirm = vm::clearHistory) {
        Text("会删除聊天文字、已结束的卡片和待执行指令，并重置阿噜的对话上下文。清空后无法找回。")
        Text("已入账的账单、分类、预算、回收站账单和未入账草稿都会保留。",
            color = MaterialTheme.colorScheme.primary)
    }
}
