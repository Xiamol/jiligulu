package com.jiligulu.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jiligulu.app.ui.theme.GuluBrandFont
import com.jiligulu.app.core.audio.UiCue

/** Bounded, scrollable paper dialog shared by help and settings confirmations. */
@Composable
fun GuluDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String = "知道啦",
    onConfirm: () -> Unit = onDismiss,
    dismissLabel: String? = null,
    busy: Boolean = false,
    compact: Boolean = false,
    compactWidth: Dp? = null,
    dense: Boolean = false,
    confirmEnabled: Boolean = true,
    confirmCue: UiCue = UiCue.TOUCH,
    dismissCue: UiCue = UiCue.NAVIGATE,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy, usePlatformDefaultWidth = !compact)) {
        com.jiligulu.app.ui.capture.DialogGlassBackdrop()
        val width = if (compact) Modifier.widthIn(max = compactWidth ?: 300.dp).fillMaxWidth()
            else Modifier.fillMaxWidth()
        LedgerCard(width.heightIn(max = (LocalConfiguration.current.screenHeightDp * if (compact) .62f else .82f).dp),
            contentPadding = if(dense || compact) 12.dp else 16.dp) {
            Text(title, modifier = Modifier.padding(bottom = if(dense) 6.dp else 10.dp),
                style = (if(dense) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge).copy(fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.primary)
            val bodyScroll = rememberScrollState()
            SpringScrollColumn(Modifier.weight(1f, fill = false), state = bodyScroll,
                verticalArrangement = Arrangement.spacedBy(if(dense) 6.dp else 10.dp), content = content)
            Row(Modifier.fillMaxWidth().padding(top = if(dense) 6.dp else 8.dp), horizontalArrangement = Arrangement.End) {
                dismissLabel?.let { TextButton(onClick = uiTap(dismissCue, onDismiss), enabled = !busy) { Text(it) } }
                TextButton(onClick = uiTap(confirmCue, onConfirm), enabled = !busy && confirmEnabled) {
                    Text(if (busy) "正在处理…" else confirmLabel)
                }
            }
        }
    }
}
