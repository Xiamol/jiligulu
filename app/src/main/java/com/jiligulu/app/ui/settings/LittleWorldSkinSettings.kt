package com.jiligulu.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.data.prefs.LittleWorldSkin
import com.jiligulu.app.ui.littleworld.LittleWorldSkinArtwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LittleWorldSkinSettings(enabled: Boolean) {
    val prefs = (LocalContext.current.applicationContext as JiliguluApp).container.userPrefs
    val saved by prefs.littleWorldSkin.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    Text("小窝皮肤", style = MaterialTheme.typography.titleSmall)
    Text("点选就换装，重启也会记住 ♡", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LittleWorldSkin.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { skin ->
                    val checked = saved == skin
                    Surface(onClick = {
                        if (checked) return@Surface
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            saving = true; error = false
                            // A quick Back immediately after a tap must not cancel the preference commit.
                            try { withContext(NonCancellable) { prefs.setLittleWorldSkin(skin) } }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = true }
                            finally { saving = false }
                        }
                    }, enabled = enabled && saved != null && !saving, shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(if (checked) 2.dp else 1.dp,
                            if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.weight(1f).semantics {
                            role = Role.RadioButton; selected = checked
                            contentDescription = "小窝皮肤：${skin.title}"
                        }) {
                        Column(Modifier.padding(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.fillMaxWidth().height(116.dp).clip(RoundedCornerShape(10.dp))) {
                                LittleWorldSkinArtwork(skin, Modifier.fillMaxSize().padding(horizontal = 8.dp))
                            }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(skin.title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                if (checked) Icon(Icons.Rounded.CheckCircle, "已选中", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
    if (error) Text("皮肤没能保存，再点一次试试。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
