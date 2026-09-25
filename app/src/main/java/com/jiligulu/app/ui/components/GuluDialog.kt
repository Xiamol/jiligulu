package com.jiligulu.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jiligulu.app.ui.theme.GuluBrandFont

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
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy, usePlatformDefaultWidth = !compact)) {
        LedgerCard(Modifier.fillMaxWidth(if (compact) .84f else 1f).then(if (compact) Modifier.widthIn(max = 330.dp) else Modifier).heightIn(max = (LocalConfiguration.current.screenHeightDp * if (compact) .62f else .82f).dp)) {
            Text(title, modifier = Modifier.padding(bottom = 16.dp),
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.primary)
            val bodyScroll = rememberScrollState()
            Column(Modifier.weight(1f, fill = false).edgeSpring({ bodyScroll.canScrollBackward }, { bodyScroll.canScrollForward }).verticalScroll(bodyScroll),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                dismissLabel?.let { TextButton(onClick = onDismiss, enabled = !busy) { Text(it) } }
                TextButton(onClick = onConfirm, enabled = !busy) {
                    Text(if (busy) "正在处理…" else confirmLabel)
                }
            }
        }
    }
}
