package com.jiligulu.app.ui.futurenotes

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.data.littleworld.FutureNote
import com.jiligulu.app.data.littleworld.LittleWorldState
import com.jiligulu.app.ui.components.*
import com.jiligulu.app.ui.theme.GuluBrandFont
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
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        title = { Text("给未来的小信笺", fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.primary) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回") }
    }, actions = { IconButton(onClick = { editing = null; creating = true }) { Icon(Icons.Outlined.Add,"写一张便签") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Text("把今天的一句话，寄给未来的你 ♡", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("还在路上", "收件箱", "旧信匣").forEachIndexed { i, label -> FilterChip(tab == i,{ tab = i },label={Text(label)}) }
            }
            val now = clock
            val rows = remember(state.futureNotes, now, tab) { state.futureNotes.filter { n -> when(tab) { 0 -> n.dueAt > now && n.readAt == null; 1 -> n.dueAt <= now && n.readAt == null; else -> n.readAt != null } }.sortedByDescending { it.dueAt } }
            SpringLazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 4.dp, bottom=24.dp)) {
                if (rows.isEmpty()) item { PaperNote("这里先留一小块空白，等你的来信 ♡", Modifier.padding(vertical=36.dp)) }
                items(rows,key={it.id}) { n -> LedgerCard {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("💌", Modifier.padding(end=10.dp), style=MaterialTheme.typography.headlineSmall)
                        Column(Modifier.weight(1f)) { Text(n.title,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis); Text(noteDate(n.dueAt),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(n.body, maxLines=2, overflow=TextOverflow.Ellipsis, style=MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick={ opened = n }) { Text("读一读") }
                        if(n.dueAt>now) TextButton(onClick={ editing=n; creating=true }){Text("修改",color=MaterialTheme.colorScheme.onSurfaceVariant)}
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick={ deleting=n }){Text("删除",color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                } }
            }
        }
    }
    if(creating) NoteEditor(editing,busy,onDismiss={if(!busy)creating=false},onSave={ note -> write {
        repo.saveFutureNote(note); creating=false
        try { FutureNoteReminder.schedule(context,note) } catch (_: Exception) { error="便签已保存，后台提醒暂时没接上，下次打开会重试。" }
    } })
    opened?.let { n -> GuluDialog("💌 ${n.title}",onDismiss={opened=null},busy=busy,compact=true,confirmLabel=if(n.dueAt<=System.currentTimeMillis())"收好信笺" else "继续等它到达",onConfirm={
        if(n.dueAt<=System.currentTimeMillis()) write { repo.markNoteRead(n.id); FutureNoteReminder.cancel(context,n.id); opened=null } else opened=null
    }) { Text(n.body,style=MaterialTheme.typography.bodyMedium); Text(noteDate(n.dueAt),style=MaterialTheme.typography.labelSmall) } }
    deleting?.let { n -> GuluDialog("删除这张便签？",onDismiss={deleting=null},busy=busy,compact=true,dismissLabel="留着",confirmLabel="删除",onConfirm={write{
        repo.deleteFutureNote(n.id);FutureNoteReminder.cancel(context,n.id);deleting=null
    }}){Text("这张便签和它的提醒会一起移除。")} }
    error?.let { GuluDialog("这次还没完成",onDismiss={error=null},compact=true){Text(it)} }
}

@Composable
private fun NoteEditor(original:FutureNote?,busy:Boolean,onDismiss:()->Unit,onSave:(FutureNote)->Unit) {
    val context=LocalContext.current
    val zone=ZoneId.systemDefault()
    var title by rememberSaveable(original?.id){mutableStateOf(original?.title.orEmpty())}
    var body by rememberSaveable(original?.id){mutableStateOf(original?.body.orEmpty())}
    var due by rememberSaveable(original?.id){mutableLongStateOf(original?.dueAt ?: LocalDate.now(zone).plusDays(1).atTime(9,0).atZone(zone).toInstant().toEpochMilli())}
    var notify by rememberSaveable(original?.id){mutableStateOf(original?.notificationEnabled ?: false)}
    var showDate by remember{mutableStateOf(false)}
    var showTime by remember{mutableStateOf(false)}
    var permissionTip by remember{mutableStateOf(false)}
    var validation by remember{mutableStateOf<String?>(null)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){ok->notify=ok;permissionTip=!ok}
    GuluDialog("写给未来的你 💌",onDismiss=onDismiss,compact=true,confirmLabel="寄出去",busy=busy,
        onConfirm={ if(title.isBlank()||body.isBlank()||due<=System.currentTimeMillis()) validation="标题、正文和未来的时间都填好，才能寄出去哦" else onSave((original ?: FutureNote(title=title,body=body,dueAt=due)).copy(title=title.trim(),body=body.trim(),dueAt=due,notificationEnabled=notify,
            presentedAt=null,readAt=null,notifiedAt=null)) }) {
        validation?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        OutlinedTextField(title,{title=it.take(40)},label={Text("信笺标题")},singleLine=true,shape=RoundedCornerShape(14.dp),enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(body,{body=it.take(2000)},label={Text("想对那时候的自己说…")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),enabled=!busy,minLines=3,maxLines=6)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={showDate=true},enabled=!busy,modifier=Modifier.weight(1.25f),shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(horizontal=10.dp)) {
                Icon(Icons.Outlined.CalendarToday,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp))
                Text(noteDate(due).substringBefore(" "),style=MaterialTheme.typography.bodySmall,maxLines=1)
            }
            OutlinedButton(onClick={showTime=true},enabled=!busy,modifier=Modifier.weight(1f),shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(horizontal=10.dp)) {
                Icon(Icons.Outlined.Schedule,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp))
                Text(noteDate(due).substringAfter(" "),style=MaterialTheme.typography.bodySmall,maxLines=1)
            }
        }
        if(due<=System.currentTimeMillis())Text("选一个未来的时间吧",color=MaterialTheme.colorScheme.error)
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("也用手机通知提醒我",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
            Switch(notify,{enabled-> if(enabled&&!FutureNoteReminder.hasPermission(context)&&Build.VERSION.SDK_INT>=33)permission.launch(Manifest.permission.POST_NOTIFICATIONS) else notify=enabled},enabled=!busy)
        }
        Text("默认到期后进软件读信；后台通知可能由系统稍后送达。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(permissionTip)Text("通知权限没开启，便签仍会保留并在软件内提醒。",style=MaterialTheme.typography.bodySmall)
    }
    if(showDate)CompactCalendarDialog(due,{showDate=false},{ day->
        val old=Instant.ofEpochMilli(due).atZone(zone)
        due=Instant.ofEpochMilli(day).atZone(zone).toLocalDate().atTime(old.hour,old.minute).atZone(zone).toInstant().toEpochMilli();showDate=false
    },latestMonth=YearMonth.now(zone).plusYears(20))
    if(showTime){val old=Instant.ofEpochMilli(due).atZone(zone);TimePickerDialog("挑一个时刻",old.hour,old.minute,{h,m->due=old.toLocalDate().atTime(h,m).atZone(zone).toInstant().toEpochMilli();showTime=false},{showTime=false})}
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
            scope.launch{ try { if(read){repo.markNoteRead(note.id);FutureNoteReminder.cancel(context,note.id)}else repo.markNotePresented(note.id) } catch (_: Exception) { android.util.Log.w("FutureNote","Inbox acknowledgement will retry next visit") } }
        }
        GuluDialog("💌 ${note.title}",onDismiss={close(false)},compact=true,dismissLabel="留在信匣",confirmLabel="收好啦",onConfirm={close(true)}){
            Text(note.body,style=MaterialTheme.typography.bodyMedium)
            Text("过去的你，托阿噜送来的 ♡",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
        }
    }
}
