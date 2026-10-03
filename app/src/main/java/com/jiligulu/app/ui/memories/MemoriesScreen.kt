package com.jiligulu.app.ui.memories

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.data.littleworld.LittleWorldState
import com.jiligulu.app.data.littleworld.MemoryCard
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.ui.billdetail.BillDetailSheet
import com.jiligulu.app.ui.components.GuluDialog
import com.jiligulu.app.ui.components.LedgerCard
import com.jiligulu.app.ui.components.rememberPageData
import com.jiligulu.app.ui.components.SpringLazyColumn
import com.jiligulu.app.ui.theme.GuluBrandFont
import com.jiligulu.app.ui.theme.IncomeGreen
import com.jiligulu.app.ui.theme.ExpenseCoral
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

data class WeeklyMemory(val rangeLabel: String, val billsCount: Int, val expenseFen: Long,
    val incomeFen: Long, val categories: List<Pair<String, Long>>)

internal fun memoryWeekRange(offset: Int, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset.toLong())
    return monday.atStartOfDay(zone).toInstant().toEpochMilli() to monday.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

internal fun summarizeMemoryWeek(bills: List<BillEntity>, categories: List<CategoryEntity>, rangeLabel: String): WeeklyMemory {
    val names = categories.associate { it.id to it.name }
    val expense = bills.filter { it.type == BillType.EXPENSE }
    return WeeklyMemory(rangeLabel, bills.size, expense.sumOf { it.amountFen },
        bills.filter { it.type == BillType.INCOME }.sumOf { it.amountFen },
        expense.groupBy { it.categoryId }.map { (id, rows) -> (names[id] ?: "未分类") to rows.sumOf { it.amountFen } }.sortedByDescending { it.second })
}

@Composable
fun MemoriesScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as JiliguluApp
    val photosSource = remember { app.container.billRepository.observePhotoMemories() }
    val photos by rememberPageData(photosSource, emptyList())
    val state by rememberPageData(app.container.littleWorld.state, LittleWorldState())
    val categoryRows by rememberPageData<List<CategoryEntity>?>(app.container.categoryRepository.categories, null)
    val categories = categoryRows.orEmpty()
    var offset by rememberSaveable { mutableIntStateOf(-1) }
    val range = remember(offset) { memoryWeekRange(offset) }
    val weekSource = remember(range) { app.container.billRepository.observeBetween(range.first, range.second) }
    val weekRows by rememberPageData<List<BillEntity>?>(weekSource, null)
    val bills = weekRows.orEmpty()
    val dates = remember(range) {
        val zone = ZoneId.systemDefault()
        val start = java.time.Instant.ofEpochMilli(range.first).atZone(zone).toLocalDate()
        val end = java.time.Instant.ofEpochMilli(range.second).atZone(zone).toLocalDate().minusDays(1)
        val format = DateTimeFormatter.ofPattern("M月d日")
        "${start.format(format)} — ${end.format(format)}"
    }
    val summary = remember(bills, categories, dates) { summarizeMemoryWeek(bills, categories, dates) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedBill by remember { mutableStateOf<Long?>(null) }
    var selectedCard by remember { mutableStateOf<MemoryCard?>(null) }
    var makeWeek by remember { mutableStateOf(false) }

    SpringLazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                Column {
                    Text("生活纪念册", fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text("日子里的小事，都值得被收好。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            LedgerCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("💌",fontSize=22.sp,modifier=Modifier.padding(end=8.dp))
                    Text("给这一周起个名字", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${summary.billsCount} 笔", color = MaterialTheme.colorScheme.primary)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { offset-- }) { Text("‹") }
                    Text(dates, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,textAlign=TextAlign.Center)
                    TextButton(onClick = { offset++ }, enabled = offset < 0) { Text("›") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("支出 ¥${Formatters.fenToYuanText(summary.expenseFen)}", style = MaterialTheme.typography.bodySmall, color = ExpenseCoral)
                    Text("收入 ¥${Formatters.fenToYuanText(summary.incomeFen)}", style = MaterialTheme.typography.bodySmall, color = IncomeGreen)
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = { makeWeek = true }, enabled = weekRows != null && categoryRows != null,
                    modifier = Modifier.fillMaxWidth()) { Text("做一张周明信片") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("生活明信片 ${state.cards.size}") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("照片票根 ${photos.size}") })
            }
        }
        if (tab == 0) {
            if (state.cards.isEmpty()) item { LedgerCard { Text("还没有夹进小明信片呢 ♡"); Text("做一张周账单，或从账单详情把照片变成海报。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            items(state.cards.sortedByDescending { it.createdAt }, key = { "card-${it.id}" }) { card ->
                LedgerCard(Modifier.clickable { selectedCard = card }) {
                    MemoryPhoto(card.imagePath, Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(14.dp)), androidx.compose.ui.layout.ContentScale.Fit)
                    Spacer(Modifier.height(10.dp))
                    Text(card.title, style = MaterialTheme.typography.titleMedium)
                    if (card.caption.isNotBlank()) Text(card.caption, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    Text("收好于 ${Formatters.dayLabel(card.createdAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            if (photos.isEmpty()) item { LedgerCard { Text("相册里还空空的，等一个小瞬间。", style = MaterialTheme.typography.bodyMedium); Text("打开任意账单 → 夹一张生活照片 → 保存修改。", style = MaterialTheme.typography.bodySmall) } }
            items(photos, key = { "bill-${it.id}" }) { bill ->
                LedgerCard(Modifier.clickable { selectedBill = bill.id }) {
                    MemoryPhoto(bill.photoUri.orEmpty(), Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(14.dp)))
                    Spacer(Modifier.height(10.dp))
                    Text(bill.detail.ifBlank { "一张生活票根" }, style = MaterialTheme.typography.titleMedium)
                    if (bill.note.isNotBlank()) Text(bill.note, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    Text(Formatters.dayLabel(bill.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (makeWeek) MemoryPosterDialog(PosterData("${if (offset == -1) "上周" else if (offset == 0) "这周" else "那一周"}的生活小记", "认真过日子的证据，阿噜替你夹好啦。", dateMillis = range.first, week = summary), { makeWeek = false })
    selectedBill?.let { BillDetailSheet(it, { selectedBill = null }) }
    selectedCard?.let { ArchivedCardDialog(it, { selectedCard = null }) }
}

@Composable
private fun ArchivedCardDialog(card: MemoryCard, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as JiliguluApp
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val file = File(card.imagePath)
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) scope.launch {
            try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("无法保存") }; Toast.makeText(context, "小海报保存好啦 ♡", Toast.LENGTH_SHORT).show() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "未能保存图片，请再试一次。" }
        }
    }
    GuluDialog(card.title, onDismiss, compact = true) {
        MemoryPhoto(card.imagePath, Modifier.fillMaxWidth().height(310.dp), androidx.compose.ui.layout.ContentScale.Fit)
        if (card.caption.isNotBlank()) Text(card.caption, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { runCatching { MemoryPoster.share(context, file) }.onFailure { error = "暂时没能打开分享。" } }) { Text("分享") }
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT < 29) createDocument.launch("阿噜生活小海报.png")
                else scope.launch {
                    busy = true
                    try { MemoryPoster.saveGallery(context, file); Toast.makeText(context, "存进相册啦 ♡", Toast.LENGTH_SHORT).show() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "未能保存图片，请再试一次。" }
                    finally { busy = false }
                }
            }, enabled = !busy) { Text("保存") }
            TextButton(onClick = { deleting = true }) { Text("取下", color = MaterialTheme.colorScheme.error) }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    if (deleting) GuluDialog("把这张明信片取下？", { deleting = false }, "取下", onConfirm = {
        scope.launch {
            try { app.container.littleWorld.deleteCard(card.id); onDismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "没能取下，请再试一次。" }
            finally { deleting = false }
        }
    }, dismissLabel = "留着", compact = true) { Text("已经保存到相册的图片会继续留在相册里。") }
}
