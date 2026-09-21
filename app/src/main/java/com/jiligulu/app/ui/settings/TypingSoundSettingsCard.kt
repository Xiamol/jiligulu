package com.jiligulu.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.audio.KeyboardSound
import com.jiligulu.app.ui.components.LedgerCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun TypingSoundSettingsCard(onPreview: () -> Unit) {
    val app = LocalContext.current.applicationContext as JiliguluApp
    val enabled by app.container.userPrefs.typingSoundEnabled.collectAsStateWithLifecycle(true)
    val player = remember { KeyboardSound(app) }
    DisposableEffect(player) { onDispose { player.close() } }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    LedgerCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("初次见面的声音", style = MaterialTheme.typography.titleMedium)
            Switch(checked = enabled, onCheckedChange = { value -> scope.launch {
                try { app.container.userPrefs.setTypingSoundEnabled(value); error = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "还没保存成功，请再试一下。" }
            } })
        }
        Text("内置轻键音，跟随媒体音量；静音模式不播放。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row {
            TextButton(onClick = player::tap, enabled = enabled) { Text("试听键音") }
            TextButton(onClick = onPreview) { Text("重看初次见面") }
        }
    }
}
