package com.jiligulu.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.ui.components.LedgerCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 从完整更新说明里挑出前几条要点。
 * 只保留项目符号行（'·' 开头），没有项目符号时退回前两句话。
 */
private fun briefNotes(notes: String): String {
    val lines = notes.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    if (lines.isEmpty()) return ""
    val bullets = lines.filter { it.startsWith("·") }
    if (bullets.size >= 2) return bullets.take(3).joinToString("\n")
    return lines.take(2).joinToString("\n")
}

@Composable
fun UpdateSettingsCard(checkOnOpen: Boolean = false) {
    val app = LocalContext.current.applicationContext as JiliguluApp
    val automatic by app.container.userPrefs.autoCheckUpdates.collectAsStateWithLifecycle(true)
    val checkedAt by app.container.userPrefs.updateCheckedAt.collectAsStateWithLifecycle(0L)
    val state by app.container.updates.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var error by remember { mutableStateOf<String?>(null) }
    var requestedOnOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(checkOnOpen) {
        if (checkOnOpen && !requestedOnOpen) {
            requestedOnOpen = true
            app.container.updates.check()
        }
    }
    LedgerCard {
        Text("应用更新", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("进入应用时自动检查", modifier = Modifier.padding(top = 14.dp))
            Switch(checked = automatic, onCheckedChange = { value ->
                scope.launch {
                    try { app.container.userPrefs.setAutoCheckUpdates(value) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "设置还没保存成功，请重试。" }
                }
            })
        }
        val status = when {
            state.checking -> "正在看看有没有新版本…"
            state.error != null -> state.error.orEmpty()
            state.available != null -> "发现新版本 ${state.available!!.version}"
            state.checked -> "当前已是最新版本"
            else -> "打开应用时会自动看看有没有新版本。"
        }
        Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!state.checking && checkedAt > 0L) {
            Text(
                "上次成功检查：" + lastCheckedText(checkedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row {
            TextButton(onClick = { scope.launch { app.container.updates.check() } },
                enabled = !state.checking) { Text("检查更新") }
        }
        state.available?.let { release ->
            TextButton(onClick = {
                try { uri.openUri(release.downloadUrl) }
                catch (_: Exception) { error = "没有找到可用的浏览器。" }
            }) { Text("下载 ${release.version}") }
        }
    }
}

/** 「刚刚 / 3 小时前 / 9 月 21 日」这样读起来比时间戳友好。 */
private fun lastCheckedText(checkedAt: Long): String {
    val elapsed = System.currentTimeMillis() - checkedAt
    if (elapsed in 0 until 60_000L) return "刚刚"
    if (elapsed in 0 until 3_600_000L) return "${elapsed / 60_000L} 分钟前"
    if (elapsed in 0 until 86_400_000L) return "${elapsed / 3_600_000L} 小时前"
    return java.text.SimpleDateFormat("M 月 d 日", java.util.Locale.CHINA)
        .format(java.util.Date(checkedAt))
}

@Composable
fun UpdatePromptHost(enabled: Boolean, onDismissed: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as JiliguluApp
    val state by app.container.updates.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    var dismissed by rememberSaveable { mutableStateOf<String?>(null) }
    var openError by rememberSaveable { mutableStateOf<String?>(null) }
    var showFullNotes by rememberSaveable { mutableStateOf(false) }
    val release = state.available ?: return
    val key = release.pageUrl + release.version
    if (!enabled || dismissed == key) return
    // 弹窗里只放几条要点，全文留给「完整说明」。
    val brief = remember(release.notes, release.version) { briefNotes(release.notes) }
    AlertDialog(
        onDismissRequest = { dismissed = key; onDismissed() },
        title = { Text("咕噜有新版本啦 · ${release.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    brief.ifBlank { "有一些新的改进，来看看吧。" },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("覆盖安装即可，账本会保留。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                openError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try { uri.openUri(release.downloadUrl); dismissed = key; onDismissed() }
                catch (_: Exception) { openError = "没有找到可用的浏览器，请稍后再试。" }
            }) { Text("下载新版") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { showFullNotes = true }) { Text("完整说明") }
                TextButton(onClick = { dismissed = key; onDismissed() }) { Text("稍后再说") }
            }
        }
    )
    if (showFullNotes) {
        AlertDialog(
            onDismissRequest = { showFullNotes = false },
            title = { Text("更新说明 · ${release.version}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(release.notes.ifBlank { "这次没有写更新说明。" })
            } },
            confirmButton = { TextButton(onClick = { showFullNotes = false }) { Text("知道啦") } }
        )
    }
}
