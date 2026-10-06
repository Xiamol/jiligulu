package com.jiligulu.app.ui.futurenotes

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalConfiguration
import com.jiligulu.app.ui.capture.DialogGlassBackdrop
import com.jiligulu.app.core.audio.UiSound
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.data.littleworld.FutureNote
import com.jiligulu.app.data.littleworld.LittleWorldState
import com.jiligulu.app.ui.components.*
import com.jiligulu.app.ui.littleworld.DestinationDrawer
import com.jiligulu.app.ui.littleworld.DestinationObject
import com.jiligulu.app.ui.littleworld.ImmersiveDestination
import com.jiligulu.app.ui.littleworld.ImmersiveDestinationScene
import com.jiligulu.app.ui.littleworld.StickerPaperArtwork
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FutureNotesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = (context.applicationContext as JiliguluApp).container.littleWorld
    val state by rememberPageData(repo.state, LittleWorldState())
    val scope = rememberCoroutineScope()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val clock by produceState(System.currentTimeMillis(), lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000L) }
        }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var drawer by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FutureNote?>(null) }
    var creating by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<FutureNote?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<FutureNote?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun write(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch { try { block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) { error = e.message ?: "还没保存好，请再试试" } finally { busy = false } }
    }
    val now = clock
    val rows = remember(state.futureNotes, now, tab) {
        state.futureNotes.filter { n -> when(tab) {
            0 -> n.dueAt > now && n.readAt == null
            1 -> n.dueAt <= now && n.readAt == null
            else -> n.readAt != null
        } }.sortedByDescending { it.dueAt }
    }
    val arrived = state.futureNotes.count { it.dueAt <= now && it.readAt == null }
    ImmersiveDestinationScene(ImmersiveDestination.FUTURE_POST, "未来邮局", onBack,
        listOf(
            DestinationObject("在路上", .14f, .25f, .40f, .30f,
                labelX = .33f, labelY = .52f) { tab = 0; drawer = true },
            DestinationObject("收件箱", .70f, .21f, .28f, .23f,
                labelX = .81f, labelY = .43f, tilt = 2f) { tab = 1; drawer = true },
            DestinationObject("旧信匣", .18f, .62f, .62f, .22f,
                labelX = .43f, labelY = .78f, tilt = 2f) { tab = 2; drawer = true },
            DestinationObject("写一封信", .50f, .82f, .42f, .14f,
                labelX = .72f, labelY = .92f) { editing = null; creating = true }
        ), Modifier.fillMaxSize().navigationBarsPadding(), hasMail = arrived > 0)
    if (drawer) DestinationDrawer(
        title = when(tab) { 0 -> "阿噜还在送信"; 1 -> "今天的收件箱"; else -> "收好的旧信笺" },
        subtitle = when(tab) { 0 -> "${rows.size} 封信，正走向未来的你。"; 1 -> "${rows.size} 封信，到了可以拆开的日子。"; else -> "${rows.size} 封信，藏着过去的心事。" },
        onDismiss = { drawer = false }, compact = true,
        actions = { TextButton(onClick = uiTap { editing = null; creating = true }) { Text("写一封") } }
    ) {
        if (rows.isEmpty()) item {
            Text(when(tab) {
                0 -> "阿噜的邮袋还空着。写封信，约好未来再见吧 ♡"
                1 -> "邮筒里还没有到期的信，阿噜会替你守好。"
                else -> "读过的信会收进这只木匣。"
            }, style = MaterialTheme.typography.bodyMedium)
        }
        items(rows, key = { it.id }) { n ->
            Box(Modifier.fillMaxWidth().rotate(if(n.id.hashCode() % 2 == 0) -.7f else .6f)) {
                StickerPaperArtwork(Modifier.matchParentSize(), MaterialTheme.colorScheme.tertiaryContainer)
                Column(Modifier.fillMaxWidth().clickable { UiSound.tap(context); opened = n }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(n.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        if (n.dueAt > now) IconButton(onClick = uiTap { editing = n; creating = true }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Outlined.EditNote, "修改信笺", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = uiTap { deleting = n }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Outlined.DeleteOutline, "删除信笺", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(n.body, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Text(noteDate(n.dueAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
    if(creating) NoteEditor(editing,busy,onDismiss={if(!busy)creating=false},onSave={ note -> write {
        repo.saveFutureNote(note); creating=false
        try { FutureNoteReminder.schedule(context,note) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { error="便签已保存，后台提醒暂时没接上，下次打开会重试。" }
    } })
    opened?.let { n -> PostPaperDialog(n.title, { opened = null }, height = 300.dp, busy = busy,
        confirmLabel = if (n.dueAt <= System.currentTimeMillis()) "收好信笺" else "等它到达",
        onConfirm = {
            if (n.dueAt <= System.currentTimeMillis() && n.readAt == null) write {
                repo.markNoteRead(n.id); FutureNoteReminder.cancel(context, n.id); opened = null
            } else opened = null
        }) {
        Text(n.body, style = MaterialTheme.typography.bodyMedium)
        Text(noteDate(n.dueAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
    deleting?.let { n -> GuluDialog("删除这张便签？",onDismiss={deleting=null},busy=busy,compact=true,compactWidth=260.dp,dense=true,dismissLabel="留着",confirmLabel="删除",onConfirm={write{
        repo.deleteFutureNote(n.id);FutureNoteReminder.cancel(context,n.id);deleting=null
    }}){Text("这张便签和它的提醒会一起移除。")} }
    error?.let { GuluDialog("这次还没完成",onDismiss={error=null},compact=true,compactWidth=260.dp,dense=true){Text(it)} }
}

@Composable
private fun NoteEditor(original: FutureNote?, busy: Boolean, onDismiss: () -> Unit, onSave: (FutureNote) -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var title by rememberSaveable(original?.id) { mutableStateOf(original?.title.orEmpty()) }
    var body by rememberSaveable(original?.id) { mutableStateOf(original?.body.orEmpty()) }
    var due by rememberSaveable(original?.id) { mutableLongStateOf(original?.dueAt ?: LocalDate.now(zone).plusDays(1)
        .atTime(9, 0).atZone(zone).toInstant().toEpochMilli()) }
    var notify by rememberSaveable(original?.id) { mutableStateOf(original?.notificationEnabled ?: false) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var permissionTip by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> notify = ok; permissionTip = !ok }
    val canSend = title.isNotBlank() && body.isNotBlank() && due > System.currentTimeMillis()
    PostPaperDialog("写给未来的你", onDismiss, height = 330.dp, busy = busy, confirmLabel = "寄出去",
        dismissLabel = "取消", confirmEnabled = canSend, onConfirm = {
            onSave((original ?: FutureNote(title = title, body = body, dueAt = due)).copy(
                title = title.trim(), body = body.trim(), dueAt = due, notificationEnabled = notify,
                presentedAt = null, readAt = null, notifiedAt = null))
        }) {
        CompactFormField("标题", title, { title = it.take(40) }, placeholder = "给那时的自己", enabled = !busy, minHeight = 36.dp)
        CompactFormField("正文", body, { body = it.take(2000) }, placeholder = "想对你说…", enabled = !busy,
            singleLine = false, maxLines = 3, minHeight = 80.dp)
        val date = Instant.ofEpochMilli(due).atZone(zone)
        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CalendarToday, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
            TextButton(onClick = uiTap { showDate = true }, enabled = !busy, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 3.dp)) {
                Text("${date.year}/${date.monthValue}/${date.dayOfMonth}", style = MaterialTheme.typography.labelMedium)
            }
            Icon(Icons.Outlined.Schedule, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
            TextButton(onClick = uiTap { showTime = true }, enabled = !busy, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(date.format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(notify, onCheckedChange = { enabled ->
                UiSound.tap(context)
                if (enabled && !FutureNoteReminder.hasPermission(context) && Build.VERSION.SDK_INT >= 33)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS) else notify = enabled
            }, enabled = !busy, modifier = Modifier.size(28.dp))
            Text("到期时也用手机提醒", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
        }
        Text(when {
            permissionTip -> "通知未开启，仍会在软件内送达"
            due <= System.currentTimeMillis() -> "要挑一个未来的时间哦"
            notify -> "手机提醒可能略晚，进软件可直接读信"
            else -> "到期进软件，阿噜就递过来 ♡"
        }, style = MaterialTheme.typography.labelSmall, maxLines = 2,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.height(30.dp))
    }
    if (showDate) CompactCalendarDialog(due, { showDate = false }, { day ->
        val old = Instant.ofEpochMilli(due).atZone(zone)
        due = Instant.ofEpochMilli(day).atZone(zone).toLocalDate().atTime(old.hour, old.minute).atZone(zone).toInstant().toEpochMilli()
        showDate = false
    }, latestMonth = YearMonth.now(zone).plusYears(20), compactWidth = 280.dp)
    if (showTime) PostTimeDialog(due, { showTime = false }) { value -> due = value; showTime = false }
}

/** Fixed paper size, independently scrollable letter area, and two immovable footer slots. */
@Composable
private fun PostPaperDialog(title: String, onDismiss: () -> Unit, height: androidx.compose.ui.unit.Dp,
    busy: Boolean = false, confirmLabel: String, dismissLabel: String? = null, confirmEnabled: Boolean = true,
    onConfirm: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        DialogGlassBackdrop()
        LedgerCard(Modifier.widthIn(max = 280.dp).fillMaxWidth()
            .height(minOf(height, (LocalConfiguration.current.screenHeightDp * .65f).dp)), contentPadding = 12.dp) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(30.dp))
            SpringScrollColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
            Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
                if (dismissLabel != null) TextButton(onClick = uiTap(onDismiss), enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(dismissLabel, style = MaterialTheme.typography.labelMedium)
                } else Spacer(Modifier.weight(1f))
                TextButton(onClick = uiTap(onConfirm), enabled = !busy && confirmEnabled, modifier = Modifier.weight(1f)) {
                    if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    else Text(confirmLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PostTimeDialog(due: Long, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val old = Instant.ofEpochMilli(due).atZone(zone)
    var hour by rememberSaveable { mutableStateOf("%02d".format(old.hour)) }
    var minute by rememberSaveable { mutableStateOf("%02d".format(old.minute)) }
    val h = hour.toIntOrNull()?.takeIf { it in 0..23 }
    val m = minute.toIntOrNull()?.takeIf { it in 0..59 }
    PostPaperDialog("几点送到？", onDismiss, 180.dp, confirmLabel = "确定", dismissLabel = "取消", confirmEnabled = h != null && m != null,
        onConfirm = { if (h != null && m != null) onSave(old.toLocalDate().atTime(h, m).atZone(zone).toInstant().toEpochMilli()) }) {
        Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            listOf(true, false).forEachIndexed { i, isHour ->
                if (i == 1) Text(" : ", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.width(58.dp)) {
                    BasicTextField(if (isHour) hour else minute, onValueChange = { text ->
                        if (text.length <= 2 && text.all(Char::isDigit)) { if (isHour) hour = text else minute = text }
                    }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        Text("24 小时制", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun noteDate(value:Long)=Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy/M/d HH:mm"))

@Composable
fun DueFutureNoteHost(enabled:Boolean,requestedId:String?,onConsumed:()->Unit) {
    val context=LocalContext.current
    val repo=(context.applicationContext as JiliguluApp).container.littleWorld
    val state by repo.state.collectAsStateWithLifecycle(initialValue=LittleWorldState())
    val scope=rememberCoroutineScope()
    var current by remember{mutableStateOf<String?>(null)}
    var handled by remember{mutableStateOf(setOf<String>())}
    val now by produceState(System.currentTimeMillis(),enabled){if(enabled)while(true){value=System.currentTimeMillis();kotlinx.coroutines.delay(30000)}}
    LaunchedEffect(enabled,requestedId,state.futureNotes,now){
        if(enabled&&current==null){
            current=state.futureNotes.firstOrNull{it.id==requestedId}?.id
                ?: state.futureNotes.filter{it.dueAt<=now&&it.readAt==null&&it.presentedAt==null&&it.id !in handled}.minByOrNull{it.dueAt}?.id
        }
    }
    val note=state.futureNotes.firstOrNull{it.id==current}
    if(enabled&&note!=null){
        fun close(read:Boolean){
            handled=handled+note.id;current=null;if(note.id==requestedId)onConsumed()
            scope.launch{ try { if(read){repo.markNoteRead(note.id);FutureNoteReminder.cancel(context,note.id)}else repo.markNotePresented(note.id) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { android.util.Log.w("FutureNote","Inbox acknowledgement will retry next visit") } }
        }
        PostPaperDialog(note.title, onDismiss={close(false)}, height=300.dp, dismissLabel="留在信匣", confirmLabel="收好啦", onConfirm={close(true)}){
            Text(note.body,style=MaterialTheme.typography.bodyMedium)
            Text("过去的你，托阿噜送来的 ♡",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
        }
    }
}
