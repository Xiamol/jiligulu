package com.jiligulu.app.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiligulu.app.ui.components.GuluDialog
import com.jiligulu.app.ui.components.uiTap

/** Help stays beside its setting and remains fully readable in a bounded, scrollable dialog. */
@Composable
fun SettingHelpButton(title: String, description: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = uiTap { open = true }, modifier = modifier.size(32.dp)) {
        Icon(Icons.Outlined.HelpOutline, contentDescription = "$title说明", modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) GuluDialog(title, onDismiss = { open = false }, compact = true, dense = true, compactWidth = 300.dp) {
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
