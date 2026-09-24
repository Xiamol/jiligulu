package com.jiligulu.app.ui.announcement

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jiligulu.app.data.announcement.AnnouncementRepository
import com.jiligulu.app.data.announcement.AnnouncementState
import com.jiligulu.app.ui.components.GuluDialog
import com.jiligulu.app.ui.theme.GuluBrandFont
import kotlinx.coroutines.launch

@Composable
fun AnnouncementBoard(state: AnnouncementState, onOpen: (String) -> Unit) {
    val latest = state.entries.firstOrNull()
    Surface(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        .testTag("announcement-board")
        .clickable(enabled = !state.loading) { onOpen(latest?.id.orEmpty()) },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.13f))) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(latest?.emoji?.ifBlank { "💌" } ?: "💌", style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("阿噜的小信箱", fontFamily = GuluBrandFont, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(latest?.title ?: when {
                    state.loading -> "正在看看有没有新消息…"
                    state.offline -> "暂时没连上公告，稍后再来看看"
                    else -> "还没有新公告，先把日子记好 ♡"
                },
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                latest?.let { Text(it.summary.ifBlank { it.body.replace('\n', ' ') },
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (latest != null) Text(if (state.entries.size > 1) "${state.entries.size} 封来信 · 点击阅读" else "点开读一读 →",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (latest != null && state.offline) Text("暂时显示已保存的来信", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun AnnouncementDialogHost(repository: AnnouncementRepository, enabled: Boolean) {
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (!enabled || state.loading) return
    if (state.emptyMailboxOpen) {
        GuluDialog(title = "💌 阿噜的小信箱", onDismiss = repository::close, confirmLabel = "收好信笺") {
            Text(if (state.offline) "这次暂时没连上公告服务，可以回到首页下拉刷新，再看看新来信。"
                else "信箱里暂时还没有新消息。\n有新的公告或节日祝福时，阿噜会把来信放在这里。",
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("announcement-empty"))
        }
        return
    }
    val entry = state.opened ?: return
    GuluDialog(title = "${entry.emoji.ifBlank { "💌" }} ${entry.title}",
        onDismiss = repository::close, dismissLabel = "关闭", confirmLabel = "这条不再弹出",
        onConfirm = { scope.launch { repository.muteOpened() } }, busy = state.saving) {
        Text(entry.body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("announcement-body"))
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.entries.size > 1) {
            val index = state.entries.indexOfFirst { it.id == entry.id }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = index > 0 && !state.saving, onClick = { repository.open(state.entries[index - 1].id) }) { Text("上一封") }
                Text("${index + 1} / ${state.entries.size}", style = MaterialTheme.typography.labelMedium)
                TextButton(enabled = index < state.entries.lastIndex && !state.saving, onClick = { repository.open(state.entries[index + 1].id) }) { Text("下一封") }
            }
        }
    }
}

/** Keep update and announcement dialogs mutually exclusive, including late update responses. */
internal fun shouldShowUpdatePrompt(ready: Boolean, releaseKey: String?, dismissedKey: String?, noticeOpen: Boolean): Boolean =
    ready && releaseKey != null && releaseKey != dismissedKey && !noticeOpen
