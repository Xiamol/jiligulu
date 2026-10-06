package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.data.littleworld.LittleWorldState
import com.jiligulu.app.data.littleworld.SecretPaper
import com.jiligulu.app.ui.components.*
import com.jiligulu.app.ui.theme.GuluBrandFont
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.security.MessageDigest

/** Small, fixed paper window. Reading, saved notes and writing share one anchored footer. */
@Composable
fun SecretPapersDialog(notes: List<String>, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as JiliguluApp
    val repository = app.container.littleWorld
    val state by rememberPageData(repository.state, LittleWorldState())
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
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val papers = if(fromFavorites) state.favoriteSecretPapers else presets + state.writtenSecretPapers
    val paper = papers.getOrNull(index.coerceIn(0, papers.lastIndex.coerceAtLeast(0)))
    fun write(block: suspend () -> Unit) {
        if(busy) return
        busy = true
        scope.launch { try { block(); error = null }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { error = "还没收好，再试一次吧。" }
            finally { busy = false } }
    }
    val height = minOf(296.dp, (LocalConfiguration.current.screenHeightDp * .72f).dp)
    Dialog(onDismissRequest = { if(!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        com.jiligulu.app.ui.capture.DialogGlassBackdrop()
        SecretWoodSurface(Modifier.widthIn(max = 280.dp).fillMaxWidth().height(height),contentPadding=PaddingValues(14.dp)) {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                if(mode != "read") IconButton(onClick = uiTap { mode = "read" }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.ArrowBack, "返回纸条", Modifier.size(18.dp)) }
                Text(if(mode == "write") "写给阿噜" else if(mode == "favorites") "喜欢的纸条" else "秘密纸条",
                    Modifier.weight(1f).padding(start=50.dp), fontSize = 16.sp,
                    maxLines = 1, color = SecretWoodInk)
                if(mode == "read") {
                    IconButton(onClick = uiTap { mode = "favorites" }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.FavoriteBorder, "收藏夹", Modifier.size(19.dp)) }
                    IconButton(onClick = uiTap { mode = "write" }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.Create, "写纸条", Modifier.size(19.dp)) }
                }
                IconButton(onClick = uiTap(onDismiss), enabled = !busy, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, "收起", Modifier.size(18.dp)) }
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
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(6.dp), decorationBox = { inner ->
                            Box { if(body.isBlank()) Text("想告诉阿噜的小心事…", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f)); inner() }
                        })
                } else SpringScrollColumn(Modifier.fillMaxSize().padding(horizontal = 5.dp),
                    verticalArrangement = Arrangement.Center) {
                    Text(paper?.body ?: "收藏夹还空着，先去遇见一句喜欢的话。", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, maxLines = 1) }
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                if(mode == "write") {
                    TextButton(onClick = uiTap { mode = "read" }, enabled = !busy, modifier = Modifier.width(80.dp)) { Text("返回") }
                    TextButton(onClick = uiTap { write {
                        val saved = SecretPaper(title = title.ifBlank { "给阿噜的小纸条" }, body = body)
                        repository.saveSecretPaper(saved); title = ""; body = ""; fromFavorites = false
                        index = presets.size + state.writtenSecretPapers.size; mode = "read"
                    } }, enabled = !busy && body.isNotBlank(), modifier = Modifier.width(100.dp)) { Text("收好纸条") }
                } else if(mode == "favorites") {
                    TextButton(onClick = uiTap { fromFavorites = false; mode = "read" }, modifier = Modifier.width(90.dp)) { Text("继续翻翻") }
                    TextButton(onClick = uiTap { mode = "write" }, modifier = Modifier.width(90.dp)) { Text("写一张") }
                } else {
                    TextButton(onClick = uiTap { if(papers.isNotEmpty()) index = (index - 1 + papers.size) % papers.size },
                        modifier = Modifier.width(72.dp),colors=ButtonDefaults.textButtonColors(contentColor=SecretWoodInk)) { Text("上一张", fontSize = 12.sp) }
                    IconButton(onClick = uiTap { paper?.let { write { repository.toggleSecretPaper(it) } } },
                        enabled = paper != null && !busy, modifier = Modifier.size(44.dp)) {
                        Icon(if(state.favoriteSecretPapers.any { it.id == paper?.id }) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                            "收藏纸条", Modifier.size(21.dp), tint = SecretWoodInk) }
                    TextButton(onClick = uiTap { if(papers.isNotEmpty()) index = (index + 1) % papers.size },
                        modifier = Modifier.width(72.dp),colors=ButtonDefaults.textButtonColors(contentColor=SecretWoodInk)) { Text("下一张", fontSize = 12.sp) }
                }
            }
        }
    }
}
