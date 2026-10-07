package com.jiligulu.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jiligulu.app.core.audio.UiSound
import com.jiligulu.app.data.prefs.StatsBarMode
import com.jiligulu.app.data.prefs.StatsDisplayPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun StatsDisplaySettings(enabled: Boolean = true) {
    val context = LocalContext.current
    val prefs = remember(context.applicationContext) { StatsDisplayPrefs(context) }
    val saved by prefs.barMode.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("统计柱图", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatsBarMode.entries.forEach { mode ->
                val checked = saved == mode
                Surface(onClick = {
                    if (checked) return@Surface
                    UiSound.select(context)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        saving = true; error = false
                        try { withContext(NonCancellable) { prefs.setBarMode(mode) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = true }
                        finally { saving = false }
                    }
                }, enabled = enabled && saved != null && !saving,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).semantics {
                        role = Role.RadioButton; selected = checked
                    }, shape = MaterialTheme.shapes.medium,
                    color = if (checked) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)) {
                    Text(mode.label, Modifier.padding(vertical = 12.dp), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (checked) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (error) Text("没能保存，再点一次试试。", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error)
    }
}
