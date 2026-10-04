package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.ui.billdetail.BillDetailSheet
import com.jiligulu.app.ui.components.CompactCalendarDialog
import com.jiligulu.app.ui.components.SpringLazyColumn
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
    var chooseDate by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    var travelling by remember { mutableStateOf(false) }
    SpringLazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().padding(horizontal=18.dp),
        contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { WorldScenePanel(WorldScene.TIME_MACHINE,"登上时光列车",onBack,
            listOf("昨天","一周前","一年前"),
            listOf(1L,7L,365L).indexOf(LocalDate.now().toEpochDay()-day),
            {day=LocalDate.now().minusDays(listOf(1L,7L,365L)[it]).toEpochDay()},
            "选车票",{chooseDate=true},onObject={
                if(!travelling) { travelling=true;scope.launch {
                    try {
                        val history=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.container.billRepository.recent(300) }
                        val days=history.map { java.time.Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate().toEpochDay() }
                            .filter { it<LocalDate.now().toEpochDay() }.distinct()
                        if(days.isNotEmpty()) day=days[Random.nextInt(days.size)]
                    } finally { travelling=false }
                } }
            }) }
        item { AlbumPaperPage { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={day--}) { Text("‹") }
            TextButton(onClick={chooseDate=true},modifier=Modifier.weight(1f)) { Text("${date.monthValue}月${date.dayOfMonth}日 · ${date.year}  ▾") }
            TextButton(onClick={day++},enabled=day<LocalDate.now().toEpochDay()) { Text("›") }
        }; Text(if(travelling) "列车正在挑一张旧车票…" else "点列车，随机去一个记过账的日子。",style=MaterialTheme.typography.labelSmall) } }
        if(rows==null) item { Text("阿噜正在翻旧车票…",style=MaterialTheme.typography.bodySmall) }
        else if(rows!!.isEmpty()) item { AlbumPaperPage { Text("这一天留了空白 ♡"); Text("点小火车，去另一页有记录的日子。",style=MaterialTheme.typography.bodySmall) } }
        else items(rows!!.size,key={rows!![it].id}) { index -> val bill=rows!![index]
            AlbumPaperPage(Modifier.clickable { selected=bill.id }) {
                Text(bill.detail.ifBlank { "一笔小生活" },style=MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(java.time.Instant.ofEpochMilli(bill.timestamp).atZone(zone).toLocalTime().toString().take(5),style=MaterialTheme.typography.bodySmall)
                    Text("${if(bill.type==BillType.INCOME) "+" else "−"}¥${Formatters.fenToYuanText(bill.amountFen)}",
                        color=if(bill.type==BillType.INCOME) IncomeGreen else ExpenseCoral)
                }
                if(bill.note.isNotBlank()) Text(bill.note,style=MaterialTheme.typography.bodySmall,maxLines=2)
            }
        }
    }
    if(chooseDate) CompactCalendarDialog(start,{chooseDate=false},{ value->
        day=java.time.Instant.ofEpochMilli(value).atZone(zone).toLocalDate().toEpochDay();chooseDate=false
    })
    selected?.let { BillDetailSheet(it) {selected=null} }
}
