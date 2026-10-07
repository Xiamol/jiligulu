package com.jiligulu.app.ui.littleworld

import androidx.lifecycle.repeatOnLifecycle

import android.os.SystemClock
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.audio.UiSound
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.littleworld.LittleWorldState
import com.jiligulu.app.ui.billdetail.BillDetailSheet
import com.jiligulu.app.ui.components.CompactCalendarDialog
import com.jiligulu.app.ui.components.GuluDialog
import com.jiligulu.app.ui.components.rememberPageData
import com.jiligulu.app.ui.components.uiTap
import com.jiligulu.app.ui.theme.ExpenseCoral
import com.jiligulu.app.ui.theme.IncomeGreen
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

private data class TrainDayRecords(val day: Long, val bills: List<BillEntity>)

@Composable
fun TimeMachineScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as JiliguluApp
    val repo = app.container.littleWorld
    val world by rememberPageData(repo.state, LittleWorldState())
    val zone = remember { ZoneId.systemDefault() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val today by produceState(LocalDate.now(zone).toEpochDay(), lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { value = LocalDate.now(zone).toEpochDay(); kotlinx.coroutines.delay(60_000L) }
        }
    }
    var nextStop by rememberSaveable { mutableLongStateOf(today - 1) }
    var arrivedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var travelling by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("先挑车票，再点列车检票出发") }
    var error by remember { mutableStateOf<String?>(null) }
    var showRoutes by remember { mutableStateOf(false) }
    var showTickets by remember { mutableStateOf(false) }
    var showLuggage by remember { mutableStateOf(false) }
    var showCalendar by remember { mutableStateOf(false) }
    var showChallenge by remember { mutableStateOf(false) }
    var selectedBillId by remember { mutableStateOf<Long?>(null) }
    var challengeSolved by remember(arrivedDay) { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var tripJob by remember { mutableStateOf<Job?>(null) }
    val history by produceState<List<BillEntity>>(emptyList(), app, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { value = withContext(Dispatchers.IO) { app.container.billRepository.recent(300) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "旧车票暂时没翻出来，日历仍可以选站" }
            kotlinx.coroutines.awaitCancellation()
        }
    }
    val historicalDays = remember(history, today) {
        history.map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate().toEpochDay() }
            .filter { it in LocalDate.of(1900, 1, 1).toEpochDay() until today }.distinct()
    }
    val source: Flow<TrainDayRecords?> = remember(arrivedDay, app) {
        arrivedDay?.let { day ->
            val date = LocalDate.ofEpochDay(day)
            app.container.billRepository.observeBetween(date.atStartOfDay(zone).toInstant().toEpochMilli(),
                date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()).map { TrainDayRecords(day, it) }
        } ?: flowOf(null)
    }
    val payload by rememberPageData<TrainDayRecords?>(source, null)
    val rows = payload?.takeIf { it.day == arrivedDay }?.bills
    val memoryChoices = remember(rows) { rows.orEmpty().groupBy { Triple(it.detail, it.type, it.amountFen) }
        .values.map { group -> group.minBy { it.timestamp } }.sortedBy { it.timestamp }.take(3) }
    fun pickStation(day: Long) {
        if (day !in LocalDate.of(1900, 1, 1).toEpochDay()..LocalDate.now(zone).toEpochDay()) { error = "可选范围是1900年1月1日到今天"; return }
        nextStop = day
        phase = "车票准备好了，点列车检票出发"
        showRoutes = false
        showTickets = false
    }
    fun depart() {
        if (travelling) return
        val destination = nextStop
        travelling = true
        tripJob = scope.launch {
            try {
                phase = "正在检票…"
                progress.snapTo(0f)
                progress.animateTo(.28f, tween(350, easing = FastOutSlowInEasing))
                phase = "列车开往 ${stationDate(destination)}"
                progress.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
                arrivedDay = destination
                repo.saveTrainTicket(destination)
                phase = "已到站，车票收进票夹啦"
                UiSound.select(context)
            } catch (cancelled: CancellationException) {
                phase = "旅程先停一下，点列车重新出发"
                throw cancelled
            } catch (_: Exception) {
                phase = "已到站，车票还没收好"
                error = "车票暂时没保存成功，再次抵达这一站就能重试"
            } finally { travelling = false }
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) tripJob?.cancel() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); tripJob?.cancel() }
    }
    val nextDate = LocalDate.ofEpochDay(nextStop)
    ImmersiveDestinationScene(ImmersiveDestination.TIME_TRAIN, "时光列车", onBack,
        listOf(
            DestinationObject(if (travelling) "列车正在路上" else "检票出发", .21f, .16f, .58f, .52f,
                labelX = .51f, labelY = .62f, enabled = !travelling) { depart() },
            DestinationObject("挑一张车票", .06f, .64f, .29f, .30f,
                labelX = .22f, labelY = .70f, tilt = 2f, enabled = !travelling) { showRoutes = true },
            DestinationObject(if (arrivedDay == null) "到站后领行李" else "打开时光行李", .39f, .76f, .48f, .18f,
                labelX = .66f, labelY = .78f, tilt = 2f, enabled = !travelling) {
                    if (arrivedDay == null) showRoutes = true else showLuggage = true
                }
        ), Modifier.fillMaxSize().navigationBarsPadding(), labelBottomClearance = 90.dp) {
        DestinationPaper(Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 15.dp)
            .widthIn(max = 300.dp).fillMaxWidth().height(70.dp).clickable(enabled = !travelling) { UiSound.paper(context); showRoutes = true }) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (arrivedDay == nextStop && !travelling) "已到站" else "下一站", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("  ${nextDate.year}/${nextDate.monthValue}/${nextDate.dayOfMonth}",
                        Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Icon(if (arrivedDay == nextStop && !travelling) Icons.Outlined.CheckCircleOutline else Icons.Outlined.ConfirmationNumber,
                        contentDescription = null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(phase, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (travelling) TrainBoardingOverlay(progress)
    }
    if (showRoutes) GuluDialog("去哪一站？", { showRoutes = false }, compact = true, compactWidth = 280.dp, dense = true,
        confirmLabel = "收起") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = uiTap { pickStation(today) }, modifier = Modifier.weight(1f)) { Text("今天") }
            TextButton(onClick = uiTap { pickStation(today - 1) }, modifier = Modifier.weight(1f)) { Text("昨天") }
        }
        TextButton(onClick = uiTap { pickStation(LocalDate.now(zone).minusYears(1).toEpochDay()) }, modifier = Modifier.fillMaxWidth()) { Text("那年今日") }
        TextButton(onClick = uiTap { pickStation(historicalDays[Random.nextInt(historicalDays.size)]) },
            enabled = historicalDays.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("抽一张有记录的旧车票") }
        TextButton(onClick = uiTap { showRoutes = false; showCalendar = true }, modifier = Modifier.fillMaxWidth()) { Text("从日历里选一站") }
        TextButton(onClick = uiTap { showRoutes = false; showTickets = true }, modifier = Modifier.fillMaxWidth()) { Text("翻翻我的车票") }
    }
    if (showCalendar) CompactCalendarDialog(nextDate.atStartOfDay(zone).toInstant().toEpochMilli(), { showCalendar = false }, { value ->
        pickStation(Instant.ofEpochMilli(value).atZone(zone).toLocalDate().toEpochDay()); showCalendar = false
    }, compactWidth = 280.dp)
    if (showTickets) DestinationDrawer("我的时光车票", { showTickets = false }, compact = true,
        subtitle = "每次抵达，都把这一天好好收着") {
        if (world.trainTickets.isEmpty()) item { Text("票夹还空着。乘一次列车，就能留下第一张车票 ♡", style = MaterialTheme.typography.bodySmall) }
        items(world.trainTickets.asReversed(), key = { it.dayEpoch }) { ticket ->
            DestinationPaper(Modifier.fillMaxWidth().height(48.dp).clickable { UiSound.paper(context); pickStation(ticket.dayEpoch) }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ConfirmationNumber, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(LocalDate.ofEpochDay(ticket.dayEpoch).toString(), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                    Text("再去一次 ›", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (showLuggage && arrivedDay != null) DestinationDrawer("${stationDate(arrivedDay!!)}的行李", { showLuggage = false }, compact = true,
        subtitle = LocalDate.ofEpochDay(arrivedDay!!).toString() + if (challengeSolved) " · 回忆找回来啦 ♡" else " · 翻翻那天的小生活",
        actions = { TextButton(onClick = uiTap { showChallenge = true }, enabled = memoryChoices.size >= 2, modifier = Modifier.weight(1f)) { Text("猜个小回忆") } }) {
        when {
            rows == null -> item { Text("阿噜正在搬这一天的行李…", style = MaterialTheme.typography.bodySmall) }
            rows.isEmpty() -> item { Text("这一天留白了。空白也是一张时光车票 ♡", style = MaterialTheme.typography.bodySmall) }
            else -> items(rows, key = { it.id }) { bill ->
                Column(Modifier.fillMaxWidth().clickable { UiSound.navigate(context); selectedBillId = bill.id }.padding(vertical = 7.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(bill.detail.ifBlank { "一笔小生活" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${if (bill.type == BillType.INCOME) "+" else "−"}${Formatters.fenToYuanText(bill.amountFen)}",
                            style = MaterialTheme.typography.labelLarge, color = if (bill.type == BillType.INCOME) IncomeGreen else ExpenseCoral)
                    }
                    Text(Formatters.timeLabel(bill.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (bill.note.isNotBlank()) Text(bill.note, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(Modifier.padding(top = 7.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                }
            }
        }
    }
    if (showChallenge && memoryChoices.size >= 2) TrainMemoryChallenge(memoryChoices, arrivedDay ?: nextStop,
        { showChallenge = false }, { challengeSolved = true })
    selectedBillId?.let { BillDetailSheet(it) { selectedBillId = null } }
    error?.let { message -> GuluDialog("阿噜的小提示", { error = null }, compact = true, compactWidth = 260.dp, dense = true) { Text(message) } }
}

private fun stationDate(day: Long): String = LocalDate.ofEpochDay(day).let { "${it.monthValue}月${it.dayOfMonth}日" }

/** A short ticket travels toward the train; no endless scene animation is left running. */
@Composable
private fun BoxScope.TrainBoardingOverlay(progress: Animatable<Float, AnimationVector1D>) {
    val stamped by remember(progress) { derivedStateOf { progress.value >= .28f } }
    Box(Modifier.matchParentSize().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {})
    DestinationPaper(Modifier.align(Alignment.Center).width(132.dp).height(58.dp).graphicsLayer {
        val fraction = progress.value
        translationY = -size.height * fraction * 1.6f
        rotationZ = -7f + fraction * 13f
        alpha = if (fraction < .75f) 1f else ((1f - fraction) / .25f).coerceIn(0f, 1f)
    }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (!stamped) "检票 · 阿噜盖章" else "咔嗒，出发啦", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Text("时光专列", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TrainMemoryChallenge(records: List<BillEntity>, day: Long, onDismiss: () -> Unit, onSolved: () -> Unit) {
    val context = LocalContext.current
    val choices = remember(records, day) { (records.sortedBy { it.timestamp }.take(1) + records.sortedBy { it.timestamp }.drop(1).take(2))
        .shuffled(Random(day.hashCode())) }
    val earliest = records.minOf { it.timestamp }
    var selected by remember(day) { mutableStateOf<Long?>(null) }
    var correct by remember(day) { mutableStateOf(false) }
    GuluDialog("${stationDate(day)}的小回忆", onDismiss, compact = true, compactWidth = 280.dp, dense = true,
        confirmLabel = "收好回忆") {
        Text("这天最早的一笔，是哪件小事？", style = MaterialTheme.typography.bodySmall)
        choices.forEach { bill ->
            TextButton(onClick = { UiSound.select(context); selected = bill.id; correct = bill.timestamp == earliest; if (correct) onSolved() },
                modifier = Modifier.fillMaxWidth().height(38.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text((if (selected == bill.id) if (correct) "♡ " else "· " else "") + bill.detail.ifBlank { "一笔小生活" },
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("  ¥${Formatters.fenToYuanText(bill.amountFen)}", style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(if (selected == null) "慢慢想，阿噜不催你" else if (correct) "想起来啦 · ${Formatters.timeLabel(earliest)} ♡" else "再想想，记忆就藏在这几张票里",
            Modifier.height(28.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}
