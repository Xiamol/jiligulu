package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.data.littleworld.LittleWorldState
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.ui.billdetail.BillDetailSheet
import com.jiligulu.app.ui.calculator.CalculatorDialog
import com.jiligulu.app.ui.components.GuluDialog
import com.jiligulu.app.ui.components.LedgerCard
import com.jiligulu.app.ui.components.rememberPageData
import com.jiligulu.app.ui.components.SpringLazyColumn
import com.jiligulu.app.ui.persona.GuluMascot
import com.jiligulu.app.ui.theme.GuluBrandFont
import com.jiligulu.app.ui.theme.GuluPurpleDeep
import com.jiligulu.app.ui.theme.PaperInkLight
import com.jiligulu.app.ui.theme.PaperNoteLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun LittleWorldScreen(
    onBack: () -> Unit,
    onOpenWishBook: () -> Unit,
    onOpenFutureNotes: () -> Unit,
    onOpenMemories: () -> Unit,
    onRecordAmount: (String) -> Unit = {}
) {
    val app = LocalContext.current.applicationContext as JiliguluApp
    val repository = app.container.littleWorld
    val state by rememberPageData(repository.state, LittleWorldState())
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000L); date = LocalDate.now() } }
    val fortune = remember(date) { DailyFortunes.forDate(date) }
    var opened by rememberSaveable(date.toString()) { mutableStateOf(false) }
    var showFavorites by rememberSaveable { mutableStateOf(false) }
    var showCalculator by rememberSaveable { mutableStateOf(false) }
    var selectedBill by remember { mutableStateOf<Long?>(null) }
    var oldBill by remember { mutableStateOf<BillEntity?>(null) }
    var billLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(date, state.timeMachineEnabled) {
        if (!state.timeMachineEnabled) { oldBill = null; billLoading = false; return@LaunchedEffect }
        billLoading = true
        try {
            withContext(Dispatchers.IO) {
                val past = app.container.billRepository.recent(200)
                    .filter { it.timestamp < Formatters.dayStart(System.currentTimeMillis()) }
                if (past.isEmpty()) null else past[Math.floorMod(date.toEpochDay(), past.size.toLong()).toInt()]
            }.let { oldBill = it }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "时光机歇一会儿，稍后再来看看吧" }
        billLoading = false
    }
    fun perform(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error = "没有保存成功，请再试一次" }
        }
    }
    fun favorite(id: Int) {
        perform { repository.toggleFortune(id) }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        WorldPageHeader("阿噜的小窝", "把小日子慢慢装起来", onBack) {
            IconButton(onClick = { showFavorites = true }) {
                BadgedBox(badge = { if (state.favoriteFortunes.isNotEmpty()) Badge(
                    containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary) {
                    Text(state.favoriteFortunes.size.toString())
                } }) {
                    Icon(Icons.Outlined.FavoriteBorder, "我的小签收藏", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        SpringLazyColumn(contentPadding = PaddingValues(18.dp, 4.dp, 18.dp, 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("愿望、来信，还有日常的小惊喜。", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    GuluMascot(Modifier.size(44.dp))
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp).rotate(-1.2f)) {
                    StickerPaperArtwork(Modifier.matchParentSize(), PaperNoteLight)
                    Column(Modifier.padding(horizontal = 32.dp).padding(top = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("今日小签", fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal,
                                fontSize = 16.sp, color = PaperInkLight, modifier = Modifier.weight(1f))
                            Text("${date.monthValue}月${date.dayOfMonth}日", style = MaterialTheme.typography.labelSmall, color = PaperInkLight)
                            if (opened) IconButton(onClick = { favorite(fortune.id) }, modifier = Modifier.size(36.dp)) {
                                Icon(if (fortune.id in state.favoriteFortunes) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = if (fortune.id in state.favoriteFortunes) "取消收藏今日小签" else "收藏今日小签",
                                    tint = GuluPurpleDeep, modifier = Modifier.size(20.dp))
                            }
                        }
                        if (opened) {
                            Text("${fortune.mark}  ${fortune.title}", style = MaterialTheme.typography.labelLarge, color = GuluPurpleDeep)
                            val fortuneText = remember(fortune.id) {
                                // Keep complete short sentences together instead of leaving one
                                // final Chinese character by itself on the second line.
                                val pause = fortune.text.indexOf('。')
                                if (fortune.text.length >= 28 && pause >= 10 && pause <= fortune.text.length - 6)
                                    fortune.text.substring(0, pause + 1) + "\n" + fortune.text.substring(pause + 1)
                                else fortune.text
                            }
                            Text(fortuneText, style = MaterialTheme.typography.bodyMedium, color = PaperInkLight)
                        } else {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("阿噜悄悄准备的小纸条 ♡", style = MaterialTheme.typography.bodySmall,
                                    color = PaperInkLight, modifier = Modifier.weight(1f))
                                TextButton(onClick = { opened = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text("抽一张 ✨", color = GuluPurpleDeep, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        WorldPaperTile("⭐", "星星愿望册", "慢慢装满小愿望", -1.8f, Color(0xFFF4EAFB), onOpenWishBook, Modifier.weight(1f), wishJar = true)
                        WorldPaperTile("💌", "给未来的信", "让时间替你递信", 1.4f, Color(0xFFFFECEC), onOpenFutureNotes, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        WorldPaperTile("📸", "生活纪念册", "照片与周明信片", 1.3f, Color(0xFFEAF3E8), onOpenMemories, Modifier.weight(1f))
                        WorldPaperTile("🧮", "阿噜小算盘", "算好就能记一笔", -1.5f, Color(0xFFFFF2D7), { showCalculator = true }, Modifier.weight(1f))
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .55f))) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🕰️  账单时光机", fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            FilterChip(selected = state.timeMachineEnabled, onClick = { perform { repository.setTimeMachine(!state.timeMachineEnabled) } },
                                label = { Text(if (state.timeMachineEnabled) "开着" else "歇会儿", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(32.dp))
                        }
                        when {
                            !state.timeMachineEnabled -> Text("想翻旧日子时，再叫阿噜呀。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            billLoading -> Text("正在翻翻旧账本…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            oldBill == null -> Text("多记几页账单，阿噜就能带你回去看看。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> oldBill?.let { bill ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { selectedBill = bill.id }, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(Formatters.dayLabel(bill.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(bill.detail.ifBlank { "一笔小账单" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text("${if (bill.type == BillType.INCOME) "+" else "−"}¥${Formatters.fenToYuanText(bill.amountFen)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    Icon(Icons.Outlined.ChevronRight, "回去看看", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        }
    }
    selectedBill?.let { id -> BillDetailSheet(id) { selectedBill = null } }
    if (showCalculator) CalculatorDialog(onDismiss = { showCalculator = false }, onUse = { amount -> showCalculator = false; onRecordAmount(amount) })
    if (showFavorites) GuluDialog("夹在书里的小签", { showFavorites = false }, compact = true) {
        val favorites = DailyFortunes.all.filter { it.id in state.favoriteFortunes }
        if (favorites.isEmpty()) Text("还没有收藏。抽到喜欢的小签，点一下小爱心吧 ♡")
        favorites.forEach { note ->
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("${note.mark}  ${note.title}", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    Text(note.text, modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { favorite(note.id) }) { Icon(Icons.Outlined.Favorite, "取消收藏", tint = MaterialTheme.colorScheme.primary) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .45f))
        }
    }
    error?.let { message -> GuluDialog("阿噜的小提示", { error = null }, compact = true) { Text(message) } }
}

@Composable
internal fun WorldPageHeader(title: String, subtitle: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}

@Composable
private fun WorldPaperTile(emoji: String, title: String, subtitle: String, angle: Float, tint: Color,
    onClick: () -> Unit, modifier: Modifier = Modifier, wishJar: Boolean = false) {
    Box(modifier.height(112.dp).rotate(angle).clickable(onClickLabel = "打开$title", onClick = onClick)) {
        StickerPaperArtwork(Modifier.matchParentSize(), tint)
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(top = 15.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (wishJar) StarWishJar(.55f, Modifier.size(42.dp))
            else Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 30.sp) }
            Text(title, fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal, fontSize = 15.sp,
                lineHeight = 20.sp, color = GuluPurpleDeep, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = PaperInkLight,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
