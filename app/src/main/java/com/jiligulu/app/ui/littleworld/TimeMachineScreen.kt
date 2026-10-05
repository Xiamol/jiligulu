package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.ui.billdetail.BillDetailSheet
import com.jiligulu.app.ui.components.CompactCalendarDialog
import com.jiligulu.app.ui.components.GuluDialog
import com.jiligulu.app.ui.components.rememberPageData
import com.jiligulu.app.ui.theme.ExpenseCoral
import com.jiligulu.app.ui.theme.IncomeGreen
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.launch

@Composable
fun TimeMachineScreen(onBack:()->Unit) {
    val app=androidx.compose.ui.platform.LocalContext.current.applicationContext as JiliguluApp
    val zone=remember { ZoneId.systemDefault() }
    var day by rememberSaveable { mutableLongStateOf(LocalDate.now().minusDays(1).toEpochDay()) }
    val date=LocalDate.ofEpochDay(day)
    val start=remember(day) { date.atStartOfDay(zone).toInstant().toEpochMilli() }
    val end=remember(day) { date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }
    val source=remember(day) { app.container.billRepository.observeBetween(start,end) }
    val rows by rememberPageData<List<BillEntity>?>(source,null)
    var selected by remember { mutableStateOf<Long?>(null) }
    var drawer by rememberSaveable { mutableStateOf(false) }
    var ticketDesk by remember { mutableStateOf(false) }
    var chooseDate by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    var travelling by remember { mutableStateOf(false) }
    var travelHint by remember { mutableStateOf("点列车，去一个记过账的日子。") }
    fun depart() {
        if (travelling) return
        travelling = true
        scope.launch {
            try {
                val history = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.container.billRepository.recent(300) }
                val days = history.map { java.time.Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate().toEpochDay() }
                    .filter { it < LocalDate.now().toEpochDay() }.distinct()
                if(days.isNotEmpty()) {
                    day = days[Random.nextInt(days.size)]
                    travelHint = "列车到了，点纪念册看看这一天。"
                } else travelHint = "还没有旧车票，等你记下第一天的生活。"
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                travelHint = "旧车票暂时没翻到，再点列车试试。"
            } finally { travelling = false }
        }
    }
    ImmersiveDestinationScene(ImmersiveDestination.TIME_TRAIN, "时光列车", onBack,
        listOf(
            DestinationObject(if(travelling) "列车准备中…" else "让列车出发", .21f, .16f, .58f, .52f,
                labelX = .51f, labelY = .62f, enabled = !travelling) { depart() },
            DestinationObject("挑一张车票", .06f, .64f, .29f, .30f,
                labelX = .22f, labelY = .70f, tilt = 2f) { ticketDesk = true },
            DestinationObject("当天账单", .39f, .76f, .48f, .18f,
                labelX = .66f, labelY = .78f, tilt = 2f) { drawer = true }
        ), Modifier.fillMaxSize().navigationBarsPadding(), labelBottomClearance = 100.dp) {
        DestinationPaper(Modifier.align(Alignment.BottomCenter).padding(horizontal = 26.dp, vertical = 22.dp)
            .widthIn(max = 350.dp).fillMaxWidth().rotate(-1f)) {
            Column(Modifier.padding(horizontal = 5.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { day-- }) { Text("‹") }
                    TextButton(onClick = { ticketDesk = true }, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text("${date.year} · ${date.monthValue}月${date.dayOfMonth}日  ▾", style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { day++ }, enabled = day < LocalDate.now().toEpochDay()) { Text("›") }
                }
                Text(if(travelling) "阿噜正在挑旧车票…" else travelHint,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (drawer) DestinationDrawer("${date.monthValue}月${date.dayOfMonth}日的行李", { drawer = false },
        subtitle = "${date.year} 年 · ${rows?.size?.let { "$it 笔生活记录" } ?: "正在翻旧车票…"}",
        actions = { TextButton(onClick = { ticketDesk = true }) { Text("换车票") } }
    ) {
        if(rows == null) item { Text("阿噜正在翻这一天的旧记录…", style = MaterialTheme.typography.bodySmall) }
        else if(rows!!.isEmpty()) item {
            Text("这一天留了空白 ♡")
            Text("点列车，再去另一页有记录的日子。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { drawer = false; depart() }, enabled = !travelling) { Text("再让列车出发") }
        }
        else items(rows!!.size, key = { rows!![it].id }) { index ->
            val bill = rows!![index]
            AlbumPaperPage(Modifier.clickable { selected = bill.id }) {
                Text(bill.detail.ifBlank { "一笔小生活" }, style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(java.time.Instant.ofEpochMilli(bill.timestamp).atZone(zone).toLocalTime().toString().take(5),
                        style = MaterialTheme.typography.bodySmall)
                    Text("${if(bill.type == BillType.INCOME) "+" else "−"}¥${Formatters.fenToYuanText(bill.amountFen)}",
                        color = if(bill.type == BillType.INCOME) IncomeGreen else ExpenseCoral)
                }
                if(bill.note.isNotBlank()) Text(bill.note, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
    }
    if (ticketDesk) GuluDialog("挑一张时光车票", { ticketDesk = false }, compact = true, confirmLabel = "收好车票") {
        listOf("昨天" to 1L, "一周前" to 7L, "一年前" to 365L).forEach { (label, daysAgo) ->
            TextButton(onClick = { day = LocalDate.now().minusDays(daysAgo).toEpochDay(); ticketDesk = false },
                modifier = Modifier.fillMaxWidth()) { Text("去$label") }
        }
        OutlinedButton(onClick = { ticketDesk = false; chooseDate = true }, modifier = Modifier.fillMaxWidth()) {
            Text("从日历里挑一天")
        }
    }
    if(chooseDate) CompactCalendarDialog(start,{chooseDate=false},{ value->
        day=java.time.Instant.ofEpochMilli(value).atZone(zone).toLocalDate().toEpochDay();chooseDate=false
    })
    selected?.let { BillDetailSheet(it) {selected=null} }
}
