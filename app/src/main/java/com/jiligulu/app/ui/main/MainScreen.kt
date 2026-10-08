package com.jiligulu.app.ui.main

import androidx.compose.animation.core.spring
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import com.jiligulu.app.data.littleworld.Sticker
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.ui.announcement.MailboxHeaderButton
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiligulu.app.ui.theme.GuluBrandFont
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiligulu.app.ui.home.HomeScreen
import com.jiligulu.app.ui.home.HomeViewModel
import com.jiligulu.app.ui.persona.DrinkingOverlay
import com.jiligulu.app.ui.persona.GuluCompanionHeader
import com.jiligulu.app.ui.persona.PersonaViewModel
import com.jiligulu.app.ui.stats.StatsScreen
import com.jiligulu.app.ui.littleworld.LittleWorldScreen
import com.jiligulu.app.ui.littleworld.SecretEntrance
import com.jiligulu.app.ui.littleworld.SecretEntranceOverlay
import com.jiligulu.app.ui.littleworld.sceneCombinedClickable
import com.jiligulu.app.ui.components.forwardMainPageSwipe
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

/** Each destination owns its header and scroll state inside one stable pager viewport. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun MainScreen(
    onAddBill: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
    homeVm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
    personaVm: PersonaViewModel = viewModel(factory = PersonaViewModel.Factory),
    onPickSticker: (Sticker) -> Unit = {},
    onOpenWishBook: () -> Unit = {},
    onOpenFutureNotes: () -> Unit = {},
    onOpenMemories: () -> Unit = {},
    onOpenTimeMachine: () -> Unit = {},
    onOpenSecretBase: () -> Unit = {},
    onRecordAmount: (String) -> Unit = {},
    active: Boolean = true
) {
    val app = LocalContext.current.applicationContext as JiliguluApp
    var selectedDestination by rememberSaveable(key = "main-destination-id") { mutableStateOf(MainDestination.LEDGER.id) }
    val selectedTab = MainDestination.fromId(selectedDestination).index
    var secretEntrance by remember { mutableStateOf<SecretEntrance?>(null) }
    RoomStatusBarAppearance(selectedTab == MainDestination.WORLD.index)
    val message by personaVm.bubble.collectAsStateWithLifecycle()
    val drinkingId by personaVm.drinkingId.collectAsStateWithLifecycle()
    val showDrinking = drinkingId != null
    // All main pages keep stable viewports and their own scroll anchors even off screen.
    val pager = key(MainDestination.entries.joinToString { it.id }) {
        rememberPagerState(initialPage = selectedTab.coerceIn(0, MainPageCount - 1)) { MainPageCount }
    }
    val motion = remember { TabMotionSession() }
    val requests = remember { Channel<TabMotion>(Channel.CONFLATED) }
    var manipulating by remember { mutableStateOf(false) }
    var worldModalOpen by remember { mutableStateOf(false) }
    var pageWidth by remember { mutableFloatStateOf(1f) }
    fun moveTo(value: Float) {
        motion.dragging = true
        motion.progress = value.coerceIn(0f, (MainPageCount - 1).toFloat())
        manipulating = true
        requests.trySend(TabMotion.Position(++motion.sequence, motion.progress))
    }
    fun beginDrag() { moveTo((pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, (MainPageCount - 1).toFloat())) }
    fun finishDrag(velocity: Float = 0f) {
        if (!motion.dragging) return
        motion.dragging = false
        requests.trySend(TabMotion.Settle(++motion.sequence, motion.progress,
            TabScrubPosition.settle(motion.progress, velocity)))
    }
    fun navigate(target: Int) {
        motion.dragging = false
        manipulating = true
        requests.trySend(TabMotion.Settle(++motion.sequence,
            (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, (MainPageCount - 1).toFloat()), target.coerceIn(0, MainPageCount - 1)))
    }
    val pageDrag: (Float) -> Unit = { delta ->
        if (!motion.dragging) beginDrag()
        moveTo(TabScrubPosition.fromPixels(motion.progress, -delta, pageWidth))
    }
    val pageDragEnd: (Float) -> Unit = { velocity -> finishDrag(velocity) }
    LaunchedEffect(pager, requests) {
        var settling: Job? = null
        fun startSettle(request: TabMotion.Settle) {
            settling = launch {
                try {
                    pager.animateScrollToPage(request.target,
                        animationSpec = spring(dampingRatio = 1f, stiffness = 650f, visibilityThreshold = 1f))
                } finally {
                    // A new touch can interrupt settling; only the newest request releases active state.
                    if (request.sequence == motion.sequence) manipulating = false
                }
            }
        }
        while (true) {
            val request = requests.receive()
            // New press/tap may interrupt a previous settle. MOVE events share one scroll
            // mutation below rather than cancelling and rebuilding the pager on every frame.
            settling?.cancelAndJoin()
            settling = null
            if (request is TabMotion.Settle) {
                startSettle(request)
                continue
            }
            var release: TabMotion.Settle? = null
            pager.scroll(MutatePriority.UserInput) {
                fun applyPosition(value: Float) {
                    val current = pager.currentPage + pager.currentPageOffsetFraction
                    val stride = (pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing).toFloat().coerceAtLeast(1f)
                    scrollBy((value.coerceIn(0f, (MainPageCount - 1).toFloat()) - current) * stride)
                }
                applyPosition(request.progress)
                while (true) {
                    val next = requests.receive()
                    applyPosition(next.progress)
                    if (next is TabMotion.Settle) {
                        release = next
                        break
                    }
                }
            }
            release?.let(::startSettle)
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { Triple(pager.settledPage, pager.isScrollInProgress, manipulating) }
            .distinctUntilChanged().collect { (page, moving, controlled) ->
                if (!moving && !controlled) selectedDestination = MainDestination.fromIndex(page).id
            }
    }
    LaunchedEffect(selectedTab) { if (selectedTab == 0) homeVm.showToday() }
    val lifecycleOwner = LocalLifecycleOwner.current
    com.jiligulu.app.ui.littleworld.SecretEntranceLifecycle { secretEntrance = null }
    BackHandler(enabled = showDrinking, onBack = personaVm::cancelDrinking)
    BackHandler(enabled = secretEntrance!=null) {secretEntrance=null}

    LaunchedEffect(lifecycleOwner, personaVm, active, selectedTab) {
        if (!active || selectedTab != 0) {
            personaVm.stopIdleTicker()
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            personaVm.onAppOpen()
            personaVm.startIdleTicker()
            try {
                awaitCancellation()
            } finally {
                personaVm.stopIdleTicker()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        com.jiligulu.app.ui.littleworld.SkinSceneDecor(Modifier.matchParentSize())
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            // The room paints behind the status bar; other pages apply their own safe header.
            // Keeping the inset policy fixed avoids resizing outgoing pages during a swipe.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    MainTabNavigation(pager, selectedTab, ::navigate, ::beginDrag, ::moveTo,
                        onScrubEnd = { finishDrag() })
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).onSizeChanged { pageWidth = it.width.toFloat() }) {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().testTag("main-pages"),
                    userScrollEnabled = false, beyondViewportPageCount = MainPageCount - 1,
                    verticalAlignment = Alignment.Top, key = { MainDestination.fromIndex(it).id }) { page ->
                    when (page) {
                        0 -> Column(Modifier.fillMaxSize()) {
                            MainPageHeader(app, selectedTab == 0, onOpenSettings) {secretEntrance=SecretEntrance.LOGO}
                            // The companion belongs to this page, including while it exits.
                            // Switching tabs must not resize the outgoing ledger viewport.
                            GuluCompanionHeader(message = message,
                                onRefresh = personaVm::onMascotClick, onWaterClick = personaVm::startDrinking,
                                active = active && selectedTab == 0,
                                modifier = Modifier.padding(horizontal = 20.dp).forwardMainPageSwipe(
                                    enabled = { selectedTab == 0 }, onDrag = pageDrag, onDragEnd = pageDragEnd))
                            Spacer(Modifier.height(12.dp))
                            HomeScreen(onOpenChat = onOpenChat, onAddBill = onAddBill, vm = homeVm,
                                active = active && selectedTab == 0, onOpenStats = { navigate(MainDestination.STATISTICS.index) }, onPickSticker = onPickSticker,
                                onPageDrag = pageDrag, onPageDragEnd = pageDragEnd)
                        }
                        2 -> LittleWorldScreen(onBack = { navigate(MainDestination.STATISTICS.index) }, onOpenWishBook = onOpenWishBook,
                            onOpenFutureNotes = onOpenFutureNotes, onOpenMemories = onOpenMemories,
                            onOpenTimeMachine=onOpenTimeMachine,onOpenSecretBase=onOpenSecretBase,
                            onSecretEntrance={ secretEntrance=it },
                            onOpenSettings=onOpenSettings,
                            onRecordAmount = onRecordAmount, embedded = true, active = active && selectedTab == MainDestination.WORLD.index,
                            onModalChanged = { worldModalOpen = it },
                            modifier = Modifier.fillMaxSize().forwardMainPageSwipe(
                                enabled = { selectedTab == MainDestination.WORLD.index && !worldModalOpen }, onDrag = pageDrag,
                                onDragEnd = pageDragEnd, allowRight = true))
                        1 -> Column(Modifier.fillMaxSize()) {
                            MainPageHeader(app, selectedTab == MainDestination.STATISTICS.index, onOpenSettings) {secretEntrance=SecretEntrance.LOGO}
                            Box(Modifier.weight(1f)) { StatsScreen(active = active && selectedTab == MainDestination.STATISTICS.index,
                                onPageDrag = pageDrag, onPageDragEnd = pageDragEnd) }
                        }
                    }
                }
            }
        }
        secretEntrance?.let {entry -> SecretEntranceOverlay(entry, {
            if(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onOpenSecretBase()
        },Modifier.fillMaxSize()) }
        DrinkingOverlay(visible = showDrinking, onFinished = personaVm::completeDrinking,
            onCancel = personaVm::cancelDrinking)
    }
}

/** The room has a bright painted wall even when the system uses dark mode. */
@Composable
private fun RoomStatusBarAppearance(active:Boolean) {
    val view=LocalView.current
    val activity=remember(view) {
        var context=view.context
        while(context is android.content.ContextWrapper && context !is android.app.Activity) context=context.baseContext
        context as? android.app.Activity
    }
    val dark=MaterialTheme.colorScheme.background.luminance()<.5f
    androidx.compose.runtime.SideEffect {
        activity?.let {androidx.core.view.WindowCompat.getInsetsController(it.window,view).isAppearanceLightStatusBars=active||!dark}
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MainPageHeader(app: JiliguluApp, active: Boolean, onOpenSettings: () -> Unit, onOpenSecretBase: () -> Unit) {
    Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("叽里咕噜", fontFamily = GuluBrandFont, fontSize = 28.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).sceneCombinedClickable(onClick = {}, onLongClick = onOpenSecretBase))
            MailboxHeaderButton(app.container.announcements,tagged=active)
            IconButton(onClick = com.jiligulu.app.ui.components.uiTap(com.jiligulu.app.core.audio.UiCue.NAVIGATE, onOpenSettings)) {
                Icon(Icons.Outlined.Settings, contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

private class TabMotionSession {
    var sequence = 0L
    var progress = 0f
    var dragging = false
}

private sealed interface TabMotion {
    val sequence: Long
    val progress: Float
    data class Position(override val sequence: Long, override val progress: Float) : TabMotion
    data class Settle(override val sequence: Long, override val progress: Float, val target: Int) : TabMotion
}
