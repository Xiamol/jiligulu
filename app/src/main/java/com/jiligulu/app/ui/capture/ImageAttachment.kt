package com.jiligulu.app.ui.capture

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*

@Composable
fun ImageAttachment(enabled: Boolean, onText: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file by ImageBillImport.pending.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { busy = true; job = scope.launch {
            try { ImageBillImport.accept(ImageBillImport.import(context, uri)); error = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "图片未能打开" }
            finally { busy = false }
        } }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        if (file == null) {
            TextButton(enabled = enabled && !busy, onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (busy) "正在准备图片…" else "添加账单图片")
            }
        } else {
            val preview by produceState<android.graphics.Bitmap?>(null, file) {
                value = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(file!!.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }) }
            }
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Row {
                    preview?.let { Image(it.asImageBitmap(), "待识别的账单图片", Modifier.size(76.dp)) }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("让阿噜读一读 🧾", style = MaterialTheme.typography.titleSmall)
                        Text("点击识别后发给 DeepSeek；结果可修改，确认后才记账。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row {
                    TextButton(enabled = enabled && !busy, onClick = {
                        val selected = file ?: return@TextButton
                        busy = true; error = null
                        job = scope.launch {
                            try {
                                val text = ImageBillImport.recognize(context, selected)
                                if (ImageBillImport.pending.value == selected) { onText(text); ImageBillImport.clear() }
                            }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "图片识别失败，请重试" }
                            finally { busy = false }
                        }
                    }) { Text(if (busy) "阿噜正在读…" else "识别并填入") }
                    TextButton(onClick = { job?.cancel(); busy = false; ImageBillImport.clear(); error = null }) { Text(if (busy) "取消识别" else "移除图片") }
                }
            } }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
