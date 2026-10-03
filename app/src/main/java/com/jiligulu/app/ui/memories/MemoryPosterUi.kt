package com.jiligulu.app.ui.memories

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.data.littleworld.MemoryCard
import com.jiligulu.app.ui.components.LedgerCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
fun LifePhotoField(path: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val currentChange by rememberUpdatedState(onChange)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = null
            try { currentChange(MemoryFiles.importPhoto(context, uri)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "照片没能收好，再选一次试试。" }
            finally { busy = false }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (path.isNotBlank()) MemoryPhoto(path, Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(18.dp)))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy) {
                Text(if (busy) "正在夹好照片…" else if (path.isBlank()) "📎 夹一张生活照片" else "换张照片")
            }
            if (path.isNotBlank()) TextButton(onClick = { onChange("") }, enabled = !busy) { Text("取下") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun MemoryPosterButton(title: String, caption: String, photoPath: String, amountFen: Long? = null,
    dateMillis: Long? = null, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier) { Text("✦ 做张生活海报") }
    if (open) MemoryPosterDialog(PosterData(title, caption, photoPath, amountFen, dateMillis), onDismiss = { open = false })
}

@Composable
fun MemoryPosterDialog(data: PosterData, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = (context.applicationContext as JiliguluApp).container.littleWorld
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(data.title) }
    var caption by remember { mutableStateOf(data.caption) }
    var showAmount by remember { mutableStateOf(false) }
    val seasonalStamp = remember { when (java.time.LocalDate.now().monthValue) {
        in 3..5 -> "春日来信"; in 6..8 -> "夏日小记"; in 9..11 -> "秋日收藏"; else -> "冬日暖意"
    } }
    var stamp by remember { mutableStateOf(seasonalStamp) }
    var file by remember { mutableStateOf<File?>(null) }
    var rendering by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val id = remember { UUID.randomUUID().toString() }
    val generated = remember { mutableSetOf<File>() }
    val preserved = remember { mutableSetOf<File>() }
    val documentFile = remember { mutableStateOf<File?>(null) }

    suspend fun archive(current: File) {
        repository.saveCard(MemoryCard(id = id, title = title.ifBlank { data.title }, caption = caption, imagePath = current.absolutePath))
        preserved.add(current)
    }
    fun act(action: suspend (File) -> Unit) {
        val current = file ?: return
        if (busy || rendering) return
        scope.launch {
            busy = true; error = null
            try { archive(current); action(current) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "这张小海报没有保存成功，请再试一次。" }
            finally { busy = false }
        }
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val current = documentFile.value
        if (uri != null && current != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { output -> current.inputStream().use { it.copyTo(output) } } ?: error("无法保存") }
                Toast.makeText(context, "生活小海报保存好啦 ♡", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "未能保存图片，请换个位置试试。" }
            finally { busy = false; documentFile.value = null }
        }
    }
    LaunchedEffect(title, caption, showAmount, stamp) {
        rendering = true; error = null
        delay(260)
        try {
            val next = MemoryPoster.render(context, data.copy(title = title, caption = caption), showAmount, stamp)
            generated.add(next)
            val previous = file
            file = next
            if (previous != null && previous !in preserved) withContext(Dispatchers.IO) { previous.delete() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "海报还没画好，再试一次吧。" }
        finally { rendering = false }
    }
    DisposableEffect(Unit) { onDispose { generated.filterNot { it in preserved }.forEach { it.delete() } } }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = !busy, dismissOnBackPress = !busy)) {
        LedgerCard(Modifier.fillMaxWidth(.9f).widthIn(max = 380.dp).heightIn(max = 720.dp).imePadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("把生活做成一张小海报", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it.take(32) }, label = { Text(if (data.week == null) "给这一刻起个名字" else "给这一周起个名字") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(caption, { caption = it.take(120) }, label = { Text("留一句话") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (data.amountFen != null || data.week != null) {
                        Checkbox(checked = showAmount, onCheckedChange = { showAmount = it }); Text("展示金额", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { stamp = when (stamp) { seasonalStamp -> "好好生活"; "好好生活" -> "小小快乐"; "小小快乐" -> "愿望成真"; else -> seasonalStamp } }) { Text("$stamp ▾") }
                }
                Box(Modifier.fillMaxWidth().height(285.dp), contentAlignment = Alignment.Center) {
                    file?.let { MemoryPhoto(it.absolutePath, Modifier.fillMaxHeight().aspectRatio(.75f).clip(RoundedCornerShape(12.dp)), ContentScale.Fit) }
                    if (rendering) CircularProgressIndicator(Modifier.size(28.dp))
                }
                Text("金额默认藏好，只把你想分享的生活留下。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { act { MemoryPoster.share(context, it) } }, enabled = !rendering && !busy && file != null, modifier = Modifier.weight(1f)) { Text("分享") }
                Button(onClick = { act {
                    if (Build.VERSION.SDK_INT >= 29) {
                        MemoryPoster.saveGallery(context, it)
                        Toast.makeText(context, "存进相册和纪念册啦 ♡", Toast.LENGTH_SHORT).show()
                    } else { documentFile.value = it; createDocument.launch("阿噜生活小海报.png") }
                } }, enabled = !rendering && !busy && file != null, modifier = Modifier.weight(1f)) { Text(if (busy) "收好中…" else "保存图片") }
            }
            TextButton(onClick = { act { Toast.makeText(context, "已经夹进生活纪念册啦 ♡", Toast.LENGTH_SHORT).show() } }, enabled = !rendering && !busy && file != null, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("只存进生活纪念册") }
        }
    }
}
