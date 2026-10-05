package com.jiligulu.app.ui.littleworld

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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.data.littleworld.LittleWorldState
import com.jiligulu.app.data.prefs.LittleWorldSkin
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.ui.billdetail.BillDetailSheet
import com.jiligulu.app.ui.calculator.CalculatorDialog
import com.jiligulu.app.ui.components.GuluDialog
import com.jiligulu.app.ui.components.LedgerCard
import com.jiligulu.app.ui.components.SpringLazyColumn
import com.jiligulu.app.ui.persona.GuluMascot
import com.jiligulu.app.ui.theme.GuluBrandFont
import com.jiligulu.app.ui.theme.PaperInkLight
import com.jiligulu.app.ui.theme.PaperNoteLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import java.time.LocalDate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun LittleWorldScreen(
    onBack: () -> Unit,
    onOpenWishBook: () -> Unit,
    onOpenFutureNotes: () -> Unit,
    onOpenMemories: () -> Unit,
    onRecordAmount: (String) -> Unit = {},
    onOpenTimeMachine: () -> Unit = {},
    onOpenSecretBase: () -> Unit = {},
    embedded: Boolean = false,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    onModalChanged: (Boolean) -> Unit = {},
    onOpenSettings: (() -> Unit)? = null
) {
    val app = LocalContext.current.applicationContext as JiliguluApp
    val repository = app.container.littleWorld
    val announcements = app.container.announcements
    val mailbox by announcements.state.collectAsStateWithLifecycle()
    val skin by app.container.userPrefs.littleWorldSkin.collectAsStateWithLifecycle(initialValue = null)
    val lifecycleOwner = LocalLifecycleOwner.current
    var error by remember { mutableStateOf<String?>(null) }
    val initialState = remember { LittleWorldState() }
    val state by produceState(initialState, repository, active, lifecycleOwner) {
        try {
            if (active) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                withContext(Dispatchers.IO) { repository.state.collect { value = it } }
            } else {
                // Pre-composed neighbour pages get one real snapshot, then keep it quietly.
                // Restarting this producer retains its previous value instead of flashing empty.
                value = withContext(Dispatchers.IO) { repository.snapshot() }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "小收藏暂时没读好，稍后再来看看吧" }
    }
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(active, lifecycleOwner) {
        if (active) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            date = LocalDate.now()
            while (true) { delay(60_000L); date = LocalDate.now() }
        }
    }
    val fortune = remember(date) { DailyFortunes.forDate(date) }
    var showFortune by rememberSaveable { mutableStateOf(false) }
    var showFavorites by rememberSaveable { mutableStateOf(false) }
    var showCalculator by rememberSaveable { mutableStateOf(false) }
    var secretPullAt by remember { mutableLongStateOf(0L) }
    var secretHint by remember { mutableStateOf(false) }
    LaunchedEffect(active) { secretPullAt=0L;secretHint=false }
    LaunchedEffect(secretHint) { if(secretHint) {delay(2600);secretHint=false} }
    val notifyModal by rememberUpdatedState(onModalChanged)
    val modalOpen = active && (showFavorites || showCalculator || showFortune || error != null)
    LaunchedEffect(modalOpen) { notifyModal(modalOpen) }
    DisposableEffect(Unit) { onDispose { notifyModal(false) } }

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

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .then(if (embedded) Modifier else Modifier.navigationBarsPadding())) {
        if (!embedded) {
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
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        val roomHeight=maxHeight
        // A single viewport-sized room preserves the spring/two-pull secret gesture
        // without turning the illustration into a taller, scrolling page.
        SpringLazyColumn(contentPadding = PaddingValues(0.dp),
            onTopPull={ distance->
                val now=android.os.SystemClock.uptimeMillis()
                if(active&&distance>=44f) {
                    if(secretPullAt>0&&now-secretPullAt<2600) {secretPullAt=0;secretHint=false;onOpenSecretBase()}
                    else {secretPullAt=now;secretHint=true}
                } else secretPullAt=0
            },
            modifier = Modifier.fillMaxSize()) {
            item {
                InteractiveRoomStage(onOpenWishBook,onOpenFutureNotes,onOpenMemories,
                    {showCalculator=true},{showFortune=true},onOpenTimeMachine,
                    {showFortune=true},modifier=Modifier.fillMaxWidth().height(roomHeight),
                    onMailbox = {
                        announcements.open((mailbox.entries.firstOrNull { it.id in mailbox.unreadIds }
                            ?: mailbox.entries.firstOrNull())?.id.orEmpty())
                    }, onSettings = onOpenSettings, controlsActive = active,
                    mailboxLoading = mailbox.loading, unreadCount = mailbox.unreadIds.size)
            }
        }
        if(secretHint) Surface(Modifier.align(Alignment.TopCenter)
            .then(if (embedded) Modifier.statusBarsPadding() else Modifier)
            .padding(horizontal=18.dp,vertical=5.dp),
            shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.primaryContainer) {
            Text("不要再下拉啦，那里是阿噜的秘密基地～",Modifier.padding(horizontal=12.dp,vertical=7.dp),
                style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onPrimaryContainer)
        }
        }
    }
    if (active && showCalculator) CalculatorDialog(onDismiss = { showCalculator = false }, onUse = { amount -> showCalculator = false; onRecordAmount(amount) })
    if(active && showFortune) GuluDialog("今日小签",{showFortune=false},compact=true) {
        Text("${fortune.mark}  ${fortune.title}",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
        Text(fortune.text,style=MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={favorite(fortune.id)}) {Text(if(fortune.id in state.favoriteFortunes) "已夹进书里 ♡" else "收藏这张小签 ♡")}
            TextButton(onClick={showFortune=false;showFavorites=true}) {Text("翻翻收藏")}
        }
    }
    if (active && showFavorites) GuluDialog("夹在书里的小签", { showFavorites = false }, compact = true) {
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
    if (active) error?.let { message -> GuluDialog("阿噜的小提示", { error = null }, compact = true) { Text(message) } }
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
private fun LifeDeskCorner(state: LittleWorldState, date: LocalDate, onOpenWishBook: () -> Unit, onFavorites: () -> Unit,
    skin: LittleWorldSkin?) {
    val featured = remember(state.wishes) { state.wishes.firstOrNull { it.completedAt == null }
        ?: state.wishes.maxByOrNull { it.completedAt ?: it.createdAt } }
    BoxWithConstraints(Modifier.fillMaxWidth().height(156.dp).rotate(-.6f)) {
        StickerPaperArtwork(Modifier.matchParentSize(), skin?.noteColor ?: PaperNoteLight)
        Row(Modifier.fillMaxWidth().padding(start = 34.dp, end = 26.dp, top = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("阿噜的生活桌", fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal,
                fontSize = 20.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Text("${date.monthValue}/${date.dayOfMonth}", style = MaterialTheme.typography.labelSmall, color = PaperInkLight)
            IconButton(onClick = onFavorites, modifier = Modifier.size(36.dp)) {
                BadgedBox(badge = { if (state.favoriteFortunes.isNotEmpty()) Badge(
                    containerColor = Color(0xFFECE4F6), contentColor = MaterialTheme.colorScheme.primary) { Text(state.favoriteFortunes.size.toString()) } }) {
                    Icon(Icons.Outlined.FavoriteBorder, "我的小签收藏", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
        }
        val progress = featured?.let { (it.savedFen.toDouble() / it.targetFen.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f) } ?: 0f
        StarWishJar(progress, Modifier.width(66.dp).height(85.dp).align(Alignment.TopStart).offset(x = 18.dp, y = 43.dp)
            .clickable(onClickLabel = "打开星星愿望册", onClick = onOpenWishBook), complete = featured?.completedAt != null)
        val noteWidth = (maxWidth - 164.dp).coerceIn(108.dp, 148.dp)
        Box(Modifier.width(noteWidth).height(82.dp).align(Alignment.Center).offset(y = 6.dp).rotate(-3f)
            .clickable(onClickLabel = "打开星星愿望册", onClick = onOpenWishBook)) {
            StickerPaperArtwork(Modifier.matchParentSize(), Color(0xFFFFF6DF))
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (featured?.completedAt != null) "一个小愿望实现啦" else "正在攒的小愿望", style = MaterialTheme.typography.labelSmall,
                    color = PaperInkLight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(featured?.title ?: "给喜欢留个位置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(featured?.let { "¥${Formatters.fenToYuanText(it.savedFen)} / ¥${Formatters.fenToYuanText(it.targetFen)}" } ?: "从一颗小星星开始", style = MaterialTheme.typography.labelSmall,
                    color = PaperInkLight, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        GuluMascot(Modifier.size(69.dp).align(Alignment.TopEnd).offset(x = (-13).dp, y = 48.dp))
        Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 16.dp), horizontalArrangement = Arrangement.Center) {
            Text("星瓶 ${state.wishes.size}   ·   来信 ${state.futureNotes.size}   ·   明信片 ${state.cards.size}",
                style = MaterialTheme.typography.labelSmall, color = PaperInkLight)
        }
        skin?.let { selected -> LittleWorldSkinSticker(selected, SkinStickerPart.TAB,
            Modifier.size(72.dp, 54.dp).align(Alignment.TopCenter).offset(x = 29.dp, y = (-12).dp).rotate(5f)) }
    }
}

@Composable
private fun WorldPaperTile(emoji: String, title: String, subtitle: String, angle: Float, tint: Color,
    onClick: () -> Unit, modifier: Modifier = Modifier, wishJar: Boolean = false, skin: LittleWorldSkin? = null) {
    Box(modifier.height(112.dp).rotate(angle).clickable(onClickLabel = "打开$title", onClick = onClick)) {
        StickerPaperArtwork(Modifier.matchParentSize(), skin?.let { lerp(tint, it.noteColor, .3f) } ?: tint)
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(top = 13.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (wishJar) StarWishJar(.55f, Modifier.size(36.dp))
            else Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 30.sp) }
            Text(title, fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal, fontSize = 15.sp,
                lineHeight = 20.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = PaperInkLight,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
