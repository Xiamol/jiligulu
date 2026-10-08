package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jiligulu.app.ui.components.SpringLazyColumn
import com.jiligulu.app.ui.components.SpringScrollColumn
import com.jiligulu.app.ui.components.uiTap

/** Note length and bookmark state never move the two footer controls. */
@Composable
internal fun DailyFortuneDialog(note: LittleFortune, saved: Boolean, onDismiss: () -> Unit,
    onBookmark: () -> Unit, onCollection: () -> Unit) {
    FortunePopup("今日小签", onDismiss) {
        Text("${note.mark}  ${note.title}", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(26.dp))
        SpringScrollColumn(Modifier.fillMaxWidth().height(90.dp)) {
            Text(note.text, style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = uiTap(com.jiligulu.app.core.audio.UiCue.TOGGLE,onBookmark), modifier = Modifier.weight(1f)) {
                Icon(if (saved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = null, modifier = Modifier.size(16.dp))
                Text(if (saved) "已收藏" else "收藏", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 5.dp))
            }
            TextButton(onClick = uiTap(com.jiligulu.app.core.audio.UiCue.PAPER,onCollection), modifier = Modifier.weight(1f)) {
                Text("翻翻收藏", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun FortuneCollectionDialog(savedIds: Set<Int>, onDismiss: () -> Unit, onBookmark: (Int) -> Unit) {
    val notes = DailyFortunes.all.filter { it.id in savedIds }
    FortunePopup("夹在书里的小签", onDismiss) {
        SpringLazyColumn(Modifier.fillMaxWidth().height(220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (notes.isEmpty()) item {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Text("书签还空着。\n喜欢哪一句，就夹进来吧 ♡", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(notes, key = { it.id }) { note ->
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Text("${note.mark}  ${note.title}", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Text(note.text, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = uiTap(com.jiligulu.app.core.audio.UiCue.TOGGLE) { onBookmark(note.id) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Favorite, "取消收藏", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            }
        }
    }
}

/** Purposeful collection/selection actions remain; plain closing uses back or the outside. */
@Composable
internal fun FortunePopup(title: String, onDismiss: () -> Unit, width: Dp = 280.dp,
    content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        com.jiligulu.app.ui.capture.DialogGlassBackdrop()
        SecretWoodSurface(Modifier.widthIn(max = width).fillMaxWidth()
            .heightIn(max = (LocalConfiguration.current.screenHeightDp * .7f).dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 18.dp)) {
            Text(title, Modifier.fillMaxWidth().padding(bottom = 10.dp),
                style = MaterialTheme.typography.titleMedium, color = SecretWoodInk, textAlign = TextAlign.Center)
            SpringScrollColumn(Modifier.weight(1f, fill = false).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}
