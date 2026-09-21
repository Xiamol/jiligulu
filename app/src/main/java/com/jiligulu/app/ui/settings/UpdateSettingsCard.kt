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

@Composable
fun UpdateSettingsCard() {
    val app = LocalContext.current.applicationContext as JiliguluApp
    val automatic by app.container.userPrefs.autoCheckUpdates.collectAsStateWithLifecycle(true)
    val state by app.container.updates.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var error by remember { mutableStateOf<String?>(null) }
    LedgerCard {
        Text("应用更新", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("启动时自动检查", modifier = Modifier.padding(top = 14.dp))
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
            else -> "自动检查最多每 6 小时一次，下载和安装由你确认。"
        }
        Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
fun UpdatePromptHost(enabled: Boolean) {
    val app = LocalContext.current.applicationContext as JiliguluApp
    val state by app.container.updates.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    var dismissed by rememberSaveable { mutableStateOf<String?>(null) }
    var openError by rememberSaveable { mutableStateOf<String?>(null) }
    val release = state.available ?: return
    val key = release.pageUrl + release.version
    if (!enabled || dismissed == key) return
    AlertDialog(
        onDismissRequest = { dismissed = key },
        title = { Text("咕噜有新版本啦 · ${release.version}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(release.notes.ifBlank { "有一些新的改进，来看看吧。" }, style = MaterialTheme.typography.bodyMedium)
                Text("将打开浏览器下载；请覆盖安装，保留你的账本。", style = MaterialTheme.typography.bodySmall)
                openError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try { uri.openUri(release.downloadUrl); dismissed = key }
                catch (_: Exception) { openError = "没有找到可用的浏览器，请稍后再试。" }
            }) { Text("下载新版") }
        },
        dismissButton = { TextButton(onClick = { dismissed = key }) { Text("稍后再说") } }
    )
}
