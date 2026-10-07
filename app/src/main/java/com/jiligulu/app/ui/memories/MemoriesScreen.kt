package com.jiligulu.app.ui.memories

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.data.littleworld.LittleWorldState
import com.jiligulu.app.data.littleworld.MemoryCard
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import com.jiligulu.app.ui.billdetail.BillDetailSheet
import com.jiligulu.app.ui.components.GuluDialog
import com.jiligulu.app.ui.components.uiTap
import com.jiligulu.app.ui.components.rememberPageData
import com.jiligulu.app.ui.theme.IncomeGreen
import com.jiligulu.app.ui.theme.ExpenseCoral
import com.jiligulu.app.ui.littleworld.AlbumPaperPage
import com.jiligulu.app.ui.littleworld.DestinationDrawer
import com.jiligulu.app.ui.littleworld.DestinationObject
import com.jiligulu.app.ui.littleworld.ImmersiveDestination
import com.jiligulu.app.ui.littleworld.ImmersiveDestinationScene
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
    var drawer by rememberSaveable { mutableIntStateOf(-1) }
    var selectedBill by remember { mutableStateOf<Long?>(null) }
    var selectedCard by remember { mutableStateOf<MemoryCard?>(null) }
    var selectedPhoto by remember { mutableStateOf<BillEntity?>(null) }
    var makeWeek by remember { mutableStateOf(false) }

    ImmersiveDestinationScene(ImmersiveDestination.MEMORIES, "生活纪念册", onBack,
        listOf(
            DestinationObject("生活明信片", .15f, .33f, .74f, .25f,
                labelX = .52f, labelY = .57f, soundCue = com.jiligulu.app.core.audio.UiCue.PAPER) { drawer = 0 },
            DestinationObject("生活照片", .04f, .52f, .29f, .25f,
                labelX = .22f, labelY = .74f, tilt = 2f, soundCue = com.jiligulu.app.core.audio.UiCue.PAPER) { drawer = 1 },
            DestinationObject("周明信片", .35f, .67f, .28f, .16f,
                labelX = .51f, labelY = .82f, tilt = 2f, soundCue = com.jiligulu.app.core.audio.UiCue.PAPER) { drawer = 2 }
        ), Modifier.fillMaxSize().navigationBarsPadding())
    if (drawer == 0 || drawer == 1) DestinationDrawer(
        title = if(drawer == 0) "夹好的生活明信片" else "一张张生活照片",
        subtitle = if(drawer == 0) "${state.cards.size} 张，翻开就能重新遇见那一天。" else "${photos.size} 张，和记过的账一起收在这里。",
        onDismiss = { drawer = -1 },
        actions = { TextButton(onClick = uiTap(com.jiligulu.app.core.audio.UiCue.PAPER) { drawer = 2 }, modifier = Modifier.weight(1f)) { Text("做周明信片") } }
    ) {
        if (drawer == 0) {
            if (state.cards.isEmpty()) item {
                Text("还没有夹进明信片呢 ♡", style = MaterialTheme.typography.bodyMedium)
                Text("点桌上的明信片工具，做一张周记；也能从账单详情把照片变成海报。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.cards.sortedByDescending { it.createdAt }, key = { "card-${it.id}" }) { card ->
                AlbumPaperPage(Modifier.clickable(onClick = uiTap(com.jiligulu.app.core.audio.UiCue.PAPER) { selectedCard = card })) {
                    MemoryPhoto(card.imagePath, Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)),
                        androidx.compose.ui.layout.ContentScale.Fit)
                    Text(card.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    if (card.caption.isNotBlank()) Text(card.caption, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    Text("收好于 ${Formatters.dayLabel(card.createdAt)}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            if (photos.isEmpty()) item {
                Text("相册还等着第一个小瞬间。", style = MaterialTheme.typography.bodyMedium)
                Text("打开一笔账，夹一张生活照片，再保存修改。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(photos, key = { "bill-${it.id}" }) { bill ->
                AlbumPaperPage(Modifier.clickable(onClick = uiTap(com.jiligulu.app.core.audio.UiCue.PAPER) { selectedPhoto = bill })) {
                    MemoryPhoto(bill.photoUri.orEmpty(), Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)))
                    Text(bill.detail.ifBlank { "一张生活照片" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    if (bill.note.isNotBlank()) Text(bill.note, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    Text(Formatters.dayLabel(bill.timestamp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (drawer == 2 && !makeWeek) GuluDialog("这一周的生活", onDismiss = { drawer = -1 }, compact = true, dense = true,
        compactWidth = 280.dp, confirmLabel = "做张明信片", confirmEnabled = weekRows != null && categoryRows != null,
        onConfirm = { makeWeek = true }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = uiTap(com.jiligulu.app.core.audio.UiCue.PAGE_TURN) { offset-- }) { Text("‹") }
            Text(dates, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            TextButton(onClick = uiTap(com.jiligulu.app.core.audio.UiCue.PAPER) { offset++ }, enabled = offset < 0) { Text("›") }
        }
        Text(if(weekRows == null || categoryRows == null) "正在翻这一周的生活记录…" else "${summary.billsCount} 笔认真过日子的证据。",
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("支出 ¥${Formatters.fenToYuanText(summary.expenseFen)}", color = ExpenseCoral, style = MaterialTheme.typography.bodySmall)
            Text("收入 ¥${Formatters.fenToYuanText(summary.incomeFen)}", color = IncomeGreen, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (makeWeek) MemoryPosterDialog(PosterData("${if (offset == -1) "上周" else if (offset == 0) "这周" else "那一周"}的生活小记", "认真过日子的证据，阿噜替你夹好啦。", dateMillis = range.first, week = summary), { makeWeek = false })
    selectedBill?.let { BillDetailSheet(it, { selectedBill = null }) }
    selectedCard?.let { ArchivedCardDialog(it, { selectedCard = null }) }
    selectedPhoto?.takeIf { selectedBill == null }?.let { photo -> GuluDialog(photo.detail.ifBlank { "这一页生活" },{selectedPhoto=null},compact=true,dense=true,
        compactWidth=300.dp,confirmLabel="查看账单",onConfirm={selectedBill=photo.id}) {
        MemoryPhoto(photo.photoUri.orEmpty(),Modifier.fillMaxWidth().height(240.dp),androidx.compose.ui.layout.ContentScale.Fit)
        if(photo.note.isNotBlank()) Text(photo.note,style=MaterialTheme.typography.bodySmall)
        Text(Formatters.dayLabel(photo.timestamp),style=MaterialTheme.typography.labelSmall)
        MemoryPosterButton(photo.detail,photo.note,photo.photoUri.orEmpty(),photo.amountFen,photo.timestamp)
    } }
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
        if (uri == null) busy = false else scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("无法保存") }; com.jiligulu.app.core.audio.UiSound.confirm(context); Toast.makeText(context, "小海报保存好啦 ♡", Toast.LENGTH_SHORT).show() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "未能保存图片，请再试一次。" }
            finally { busy = false }
        }
    }
    val save: () -> Unit = {
        if (!busy) {
            busy = true
            if (Build.VERSION.SDK_INT < 29) createDocument.launch("阿噜生活小海报.png")
            else scope.launch {
                try { MemoryPoster.saveGallery(context, file); com.jiligulu.app.core.audio.UiSound.confirm(context); Toast.makeText(context, "存进相册啦 ♡", Toast.LENGTH_SHORT).show() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "未能保存图片，请再试一次。" }
                finally { busy = false }
            }
        }
    }
    GuluDialog(card.title, onDismiss, compact = true, dense = true, compactWidth = 300.dp,
        confirmLabel = "存进相册", onConfirm = save, busy = busy, dismissLabel = "收起来") {
        MemoryPhoto(card.imagePath, Modifier.fillMaxWidth().height(240.dp), androidx.compose.ui.layout.ContentScale.Fit)
        if (card.caption.isNotBlank()) Text(card.caption, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = uiTap(com.jiligulu.app.core.audio.UiCue.NAVIGATE) { runCatching { MemoryPoster.share(context, file) }.onFailure { error = "暂时没能打开分享。" } }, enabled = !busy) { Text("分享") }
            TextButton(onClick = uiTap { deleting = true }, enabled = !busy) { Text("取下", color = MaterialTheme.colorScheme.error) }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    if (deleting) GuluDialog("把这张明信片取下？", { deleting = false }, "取下", onConfirm = {
        busy = true
        scope.launch {
            try { app.container.littleWorld.deleteCard(card.id); com.jiligulu.app.core.audio.UiSound.play(context, com.jiligulu.app.core.audio.UiCue.REMOVE); onDismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "没能取下，请再试一次。" }
            finally { deleting = false; busy = false }
        }
    }, dismissLabel = "留着", busy = busy, compact = true, dense = true, compactWidth = 260.dp) { Text("已经保存到相册的图片会继续留在相册里。", style = MaterialTheme.typography.bodySmall) }
}
