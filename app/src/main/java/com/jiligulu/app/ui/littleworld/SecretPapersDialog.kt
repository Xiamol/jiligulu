package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.data.littleworld.LittleWorldState
import com.jiligulu.app.data.littleworld.SecretPaper
import com.jiligulu.app.data.littleworld.HeartLetterStatus
import com.jiligulu.app.ui.components.*
import com.jiligulu.app.ui.theme.GuluBrandFont
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

/** Small, fixed paper window. Reading, saved notes and writing share one anchored footer. */
@Composable
fun SecretPapersDialog(notes: List<String>, onDismiss: () -> Unit, onOpenReplies: (() -> Unit)? = null) {
    val app = LocalContext.current.applicationContext as JiliguluApp
    val repository = app.container.littleWorld
    val letters = app.container.heartLetters
    val state by rememberPageData(repository.state, LittleWorldState())
    val sending by letters.sendingIds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val presets = remember(notes) { notes.map { body ->
        val key = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        SecretPaper(id = "preset-$key", title = "阿噜的小纸条", body = body, createdAt = 0)
    } }
    var mode by rememberSaveable { mutableStateOf("read") }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var fromFavorites by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var draftId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var draftCreatedAt by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var draftLoaded by remember { mutableStateOf(false) }
    var selectedWrittenId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val papers = if(fromFavorites) state.favoriteSecretPapers else presets + state.writtenSecretPapers
    val paper = papers.getOrNull(index.coerceIn(0, papers.lastIndex.coerceAtLeast(0)))
    val receipt = state.heartLetters.firstOrNull { it.paper.id == paper?.id }
    LaunchedEffect(repository) {
        repository.snapshot().heartLetterDraft?.let {
            if (title.isBlank() && body.isBlank()) {
                draftId = it.id; draftCreatedAt = it.createdAt; title = it.title; body = it.body; mode = "write"
            }
        }
        draftLoaded = true
    }
    LaunchedEffect(draftLoaded, mode, draftId, title, body) {
        if (draftLoaded && mode == "write") {
            delay(250)
            try {
                repository.saveHeartDraft(if (title.isBlank() && body.isBlank()) null
                    else SecretPaper(draftId, title, body, draftCreatedAt))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "草稿暂时没存好，关闭前请再试一次。" }
        }
    }
    LaunchedEffect(selectedWrittenId, state.writtenSecretPapers) {
        selectedWrittenId?.let { id -> state.writtenSecretPapers.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let {
            fromFavorites = false; index = presets.size + it; selectedWrittenId = null
        } }
    }
    fun closeThen(action: () -> Unit) {
        if (busy) return
        scope.launch {
            try {
                if (draftLoaded || title.isNotBlank() || body.isNotBlank()) withContext(NonCancellable) {
                    repository.saveHeartDraft(if (title.isBlank() && body.isBlank()) null
                        else SecretPaper(draftId, title, body, draftCreatedAt))
                }
                action()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "草稿还没收好，再试一次吧。" }
        }
    }
    fun close() = closeThen(onDismiss)
    fun write(block: suspend () -> Unit) {
        if(busy) return
        busy = true
        scope.launch { try { block(); error = null }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { error = "还没收好，再试一次吧。" }
            finally { busy = false } }
    }
    val height = minOf(if (mode == "write") 410.dp else 330.dp, (LocalConfiguration.current.screenHeightDp * .72f).dp)
    Dialog(onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        com.jiligulu.app.ui.capture.DialogGlassBackdrop()
        SecretWoodSurface(Modifier.widthIn(max = 280.dp).fillMaxWidth().height(height),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 22.dp)) {
            Text(if(mode == "write") "写给阿噜" else if(mode == "favorites") "喜欢的纸条" else "秘密纸条",
                Modifier.fillMaxWidth().height(28.dp), textAlign = TextAlign.Center, fontSize = 17.sp,
                maxLines = 1, color = SecretWoodInk)
            Row(Modifier.fillMaxWidth().height(38.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                if(mode != "read") TextButton(onClick = uiTap { mode = "read" }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.ArrowBack, null, Modifier.size(16.dp))
                    Text("返回", fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp)) }
                if(mode == "read") {
                    TextButton(onClick = uiTap { mode = "favorites" }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.FavoriteBorder, null, Modifier.size(16.dp))
                        Text("收藏夹", fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp)) }
                    TextButton(onClick = uiTap { mode = "write" }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Create, null, Modifier.size(16.dp))
                        Text("写一张", fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp)) }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if(mode == "favorites") SpringLazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if(state.favoriteSecretPapers.isEmpty()) item { Text("遇到喜欢的话，点小爱心收好。", style = MaterialTheme.typography.bodyMedium) }
                    items(state.favoriteSecretPapers, key = { it.id }) { saved ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(saved.body, Modifier.weight(1f).clickable(onClick = uiTap {
                                fromFavorites = true; index = state.favoriteSecretPapers.indexOfFirst { it.id == saved.id }; mode = "read"
                            }).padding(vertical = 4.dp), maxLines = 3, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = uiTap { write { repository.toggleSecretPaper(saved) } }, enabled = !busy,
                                modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.Favorite, "取下收藏", Modifier.size(18.dp)) }
                        }
                    }
                } else if(mode == "write") Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.jiligulu.app.ui.components.CompactFormField("标题", title, { title = it.take(40) },
                        placeholder = "给这张纸条起个名字", enabled = !busy)
                    androidx.compose.foundation.text.BasicTextField(body, { body = it.take(1500) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        enabled = !busy, cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(6.dp).testTag("heart-letter-body"), decorationBox = { inner ->
                            Box { if(body.isBlank()) Text("想告诉阿噜的小心事…", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f)); inner() }
                        })
                    Text("寄出后，当前 AI 服务会只读这张纸条写回信，送进未来信箱。关闭窗口仍会继续；草稿留在本机。",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                } else SpringScrollColumn(Modifier.fillMaxSize().padding(horizontal = 5.dp),
                    verticalArrangement = Arrangement.Center) {
                    Text(paper?.body ?: "收藏夹还空着，先去遇见一句喜欢的话。", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface)
                    receipt?.let { letter ->
                        Text(when {
                            letter.paper.id in sending -> "阿噜正在写回信，稍后会送进未来信箱。"
                            letter.status == HeartLetterStatus.REPLIED -> "阿噜已回信，去未来信箱拆开吧。"
                            letter.status == HeartLetterStatus.CANCELLED -> "已取消发送，原信留在这里。"
                            letter.status == HeartLetterStatus.FAILED -> "这次没收到回信，可检查 AI 服务后再寄一次。"
                            else -> "上次寄信没有完成，可以再寄一次。"
                        }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, maxLines = 1) }
            }
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                if(mode == "write") {
                    TextButton(onClick = uiTap { write {
                        val saved = SecretPaper(draftId, title.ifBlank { "给阿噜的小纸条" }, body, draftCreatedAt)
                        repository.saveSecretPaper(saved); repository.saveHeartDraft(null)
                        selectedWrittenId = saved.id; title = ""; body = ""; draftId = UUID.randomUUID().toString()
                        draftCreatedAt = System.currentTimeMillis(); mode = "read"
                    } }, enabled = !busy && body.isNotBlank(), modifier = Modifier.weight(1f)) { Text("只保存") }
                    TextButton(onClick = uiTap { write {
                        val saved = SecretPaper(draftId, title.ifBlank { "给阿噜的小纸条" }, body, draftCreatedAt)
                        letters.send(saved); selectedWrittenId = saved.id
                        title = ""; body = ""; draftId = UUID.randomUUID().toString()
                        draftCreatedAt = System.currentTimeMillis(); mode = "read"
                    } }, enabled = !busy && body.isNotBlank(), modifier = Modifier.weight(1f).testTag("heart-letter-send")) { Text("寄给阿噜") }
                } else if(mode == "favorites") {
                    TextButton(onClick = uiTap { fromFavorites = false; mode = "read" }, modifier = Modifier.width(90.dp)) { Text("继续翻翻") }
                    TextButton(onClick = uiTap { mode = "write" }, modifier = Modifier.width(90.dp)) { Text("写一张") }
                } else {
                    TextButton(onClick = uiTap { if(papers.isNotEmpty()) index = (index - 1 + papers.size) % papers.size },
                        modifier = Modifier.weight(1f),colors=ButtonDefaults.textButtonColors(contentColor=SecretWoodInk)) { Text("上一张", fontSize = 12.sp) }
                    IconButton(onClick = uiTap { paper?.let { write { repository.toggleSecretPaper(it) } } },
                        enabled = paper != null && !busy, modifier = Modifier.width(32.dp).height(40.dp)) {
                        Icon(if(state.favoriteSecretPapers.any { it.id == paper?.id }) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                            "收藏纸条", Modifier.size(19.dp), tint = SecretWoodInk) }
                    if (paper?.id?.startsWith("preset-") == false) TextButton(onClick = uiTap {
                        if (receipt?.status == HeartLetterStatus.REPLIED) onOpenReplies?.let { closeThen(it) }
                        else paper?.let { current -> write {
                            if (current.id in sending) letters.cancelSending(current.id) else letters.send(current)
                        } }
                    }, enabled = !busy && (receipt?.status != HeartLetterStatus.REPLIED || onOpenReplies != null),
                        modifier = Modifier.width(80.dp).testTag("heart-letter-retry")) {
                        Text(if (paper?.id?.let { it in sending } == true) "取消发送"
                            else if (receipt?.status == HeartLetterStatus.REPLIED) "去信箱"
                            else if (receipt == null) "寄给阿噜" else "再寄一次", fontSize = 11.sp)
                    }
                    TextButton(onClick = uiTap { if(papers.isNotEmpty()) index = (index + 1) % papers.size },
                        modifier = Modifier.weight(1f),colors=ButtonDefaults.textButtonColors(contentColor=SecretWoodInk)) { Text("下一张", fontSize = 12.sp) }
                }
            }
        }
    }
}
