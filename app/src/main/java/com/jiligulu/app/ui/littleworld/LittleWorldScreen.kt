package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.jiligulu.app.ui.persona.GuluMascot
import com.jiligulu.app.ui.theme.GuluBrandFont
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

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        WorldPageHeader("阿噜的小窝", "把小日子慢慢装起来", onBack)
        LazyColumn(contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.weight(1f)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("生活里，也有好多\n值得留下的小星星。", style = MaterialTheme.typography.titleLarge,
                            fontFamily = GuluBrandFont, color = MaterialTheme.colorScheme.primary)
                        Text("这里的小东西都在本地陪着你 ♡", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                    GuluMascot(Modifier.size(88.dp))
                }
            }
            item {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .5f))) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("今日小签", fontFamily = GuluBrandFont, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            Text("${date.monthValue}月${date.dayOfMonth}日", style = MaterialTheme.typography.labelMedium)
                        }
                        if (opened) {
                            Text("${fortune.mark}  ${fortune.title}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Text(fortune.text, style = MaterialTheme.typography.bodyLarge)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("今天就这一张，明天再来呀", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                IconButton(onClick = { favorite(fortune.id) }) {
                                    Icon(if (fortune.id in state.favoriteFortunes) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                                        contentDescription = if (fortune.id in state.favoriteFortunes) "取消收藏今日小签" else "收藏今日小签",
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        } else {
                            Text("阿噜偷偷准备了一张小纸条。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { opened = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("抽一张，看看今天的小温柔 ✨") }
                        }
                        TextButton(onClick = { showFavorites = true }, contentPadding = PaddingValues(0.dp)) { Text("我的小签收藏 · ${state.favoriteFortunes.size} 张") }
                    }
                }
            }
            item { WorldEntrance("⭐", "星星愿望册", "慢慢攒、慢慢实现，留下每一个满星瓶", onOpenWishBook) }
            item { WorldEntrance("💌", "给未来的自己", "写一封小信，让阿噜在那一天递给你", onOpenFutureNotes) }
            item { WorldEntrance("📸", "生活纪念册", "照片票根、周明信片，保存或分享一页生活", onOpenMemories) }
            item { WorldEntrance("🧮", "阿噜小算盘", "算式、实时结果，算完就能带入记账", { showCalculator = true }) }
            item {
                LedgerCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🕰️  账单时光机", fontFamily = GuluBrandFont, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        Switch(checked = state.timeMachineEnabled, onCheckedChange = { enabled ->
                            perform { repository.setTimeMachine(enabled) }
                        })
                    }
                    when {
                        !state.timeMachineEnabled -> Text("时光机已休息，想翻旧日子时再叫它。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        billLoading -> Text("阿噜正在翻翻旧账本…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        oldBill == null -> Text("多留几页小账单，阿噜就能带你回去看看啦。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> oldBill?.let { bill ->
                            Text("${Formatters.dayLabel(bill.timestamp)}，你记下了", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(bill.detail.ifBlank { "一笔小账单" }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${if (bill.type == BillType.INCOME) "+" else "−"}¥${Formatters.fenToYuanText(bill.amountFen)}", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                                TextButton(onClick = { selectedBill = bill.id }) { Text("回去看看 →") }
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
            Text(title, fontFamily = GuluBrandFont, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}

@Composable
private fun WorldEntrance(emoji: String, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .55f))) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).rotate(-4f).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 26.sp) }
            Column(Modifier.weight(1f)) {
                Text(title, fontFamily = GuluBrandFont, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            Icon(Icons.Outlined.ChevronRight, "打开$title", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
