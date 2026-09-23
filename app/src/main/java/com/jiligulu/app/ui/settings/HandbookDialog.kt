package com.jiligulu.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.jiligulu.app.ui.components.GuluDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HandbookDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var handbook by remember(context) { mutableStateOf<String?>(null) }
    LaunchedEffect(context) {
        handbook = try {
            withContext(Dispatchers.IO) { context.assets.open("handbook.md").bufferedReader().use { it.readText() } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "手册暂时没打开，请关闭后再试一次。"
        }
    }
    GuluDialog(title = "阿噜使用手册 ♡", onDismiss = onDismiss) {
        val text = handbook
        if (text == null) Text("正在翻开小手册…")
        else text.split(Regex("\\r?\\n\\s*\\r?\\n")).filter { it.isNotBlank() }.forEach { paragraph ->
            val heading = paragraph.startsWith("## ")
            Text(paragraph.removePrefix("## "),
                style = if (heading) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                color = if (heading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
    }
}
