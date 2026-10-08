package com.jiligulu.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jiligulu.app.core.audio.UiSound
import com.jiligulu.app.data.prefs.BillContextPrefs
import com.jiligulu.app.data.prefs.BillContextWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun BillContextSettings(enabled: Boolean = true) {
    val context = LocalContext.current
    val prefs = remember(context) { BillContextPrefs(context) }
    val nullableWindow = remember(prefs) { prefs.window.map<BillContextWindow, BillContextWindow?> { it } }
    val saved by nullableWindow.collectAsStateWithLifecycle(initialValue = null)
    var pending by remember { mutableStateOf<BillContextWindow?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val selected = pending ?: saved ?: BillContextWindow.THREE_DAYS
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("聊天账单范围", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            SettingHelpButton("聊天账单范围", "默认带入最近3天，最多30笔。7天最多100笔、30天300笔、90天600笔；全部表示不限制时间，仍只取最近1000笔。账单明细还受约4.8万字符预算限制，超长细则会截取，并在请求中注明。更大范围会增加模型输入和费用。此设置不删除账本，精确查询仍使用完整账本。")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BillContextWindow.entries.forEach { window ->
                FilterChip(selected == window, onClick = {
                    if (!saving && saved != null && selected != window) {
                        UiSound.select(context)
                        pending = window; saving = true; error = null
                        scope.launch {
                            try { withContext(NonCancellable) { prefs.setWindow(window) } }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = "这次没保存好，再试一下吧" }
                            finally { pending = null; saving = false }
                        }
                    }
                }, label = { Text(window.label, style = MaterialTheme.typography.labelSmall) },
                    enabled = enabled && saved != null && !saving,
                    modifier = Modifier.weight(1f).testTag("bill-context-${window.key}"))
            }
        }
        Text("最多${selected.maxBillCount}笔 · 更多范围会增加输入费用", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
