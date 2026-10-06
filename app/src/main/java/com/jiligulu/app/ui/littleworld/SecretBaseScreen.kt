package com.jiligulu.app.ui.littleworld

import androidx.activity.compose.BackHandler
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.R
import com.jiligulu.app.core.audio.UiSound
import com.jiligulu.app.ui.theme.GuluBrandFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt
import kotlin.random.Random

private val tinyPrizes = listOf("喝一口水", "歇两分钟", "写封未来信", "看一张照片", "夸夸自己", "伸个懒腰", "想个小愿望", "听一句悄悄话")
private val secretNotes = listOf(
    "这里没有待办清单，只有一张留给你的小椅子。", "阿噜把今天的好运藏在了你的口袋里。",
    "小芽悄悄长高了一点，你也可以慢慢来。", "今天不用成为厉害的大人，当个快乐的小孩也可以。",
    "一瓶星星装不下所有喜欢，那就慢慢再攒一瓶。", "别担心，刚刚那点走神，阿噜替你保密。",
    "秘密基地营业中：疲惫可以先寄放在这里。", "如果今天是普通的一天，那就收藏普通的快乐。",
    "阿噜不是来催你的，是来陪你坐一会儿的。", "抬头看看窗外，那也是你今天的一页。",
    "你不需要每次都赢，玩得开心就已经算数。", "这张纸条没有大道理：好好吃饭，睡个好觉 ♡"
)

private enum class SecretActivity { WHEEL, SNAKE, BOARD, GOMOKU, XIANGQI, PAPER }

private val snakeStateSaver = listSaver<SnakeState, Int>(
    save = { state ->
        listOf(state.width, state.height, state.direction.ordinal, state.pendingDirection.ordinal,
            state.food?.x ?: -1, state.food?.y ?: -1, state.score, if (state.gameOver) 1 else 0,
            if (state.won) 1 else 0) + state.body.flatMap { listOf(it.x, it.y) }
    },
    restore = { values ->
        SnakeState(values[0], values[1], values.drop(9).chunked(2).map { GridCell(it[0], it[1]) },
            SnakeDirection.entries[values[2]], SnakeDirection.entries[values[3]],
            if (values[4] < 0) null else GridCell(values[4], values[5]), values[6], values[7] == 1, values[8] == 1)
    }
)
private val gomokuStateSaver = listSaver<GomokuState, Int>(
    save = { state -> listOf(state.size, state.currentPlayer, state.outcome.ordinal,
        state.lastMove?.x ?: -1, state.lastMove?.y ?: -1) + state.board },
    restore = { values -> if (values[0] != 15) GomokuEngine.newGame() else
        GomokuState(values[0], values.drop(5), values[1], GomokuOutcome.entries[values[2]],
            if (values[3] < 0) null else GridCell(values[3], values[4])) }
)
private val xiangqiStateSaver = listSaver<XiangqiState, Int>(
    save = { state -> listOf(state.turnSide.ordinal, state.outcome.ordinal, state.ply,
        state.lastMove?.from?.let { it.y * 9 + it.x } ?: -1,
        state.lastMove?.to?.let { it.y * 9 + it.x } ?: -1) + state.board },
    restore = { values -> XiangqiState(board = values.drop(5), turnSide = XiangqiSide.entries[values[0]],
        outcome = XiangqiOutcome.entries[values[1]], ply = values[2], lastMove =
        if (values[3] < 0) null else XiangqiMove(GridCell(values[3] % 9, values[3] / 9), GridCell(values[4] % 9, values[4] / 9))) }
)
private val xiangqiClockSaver = listSaver<XiangqiThinkingClock, Long>(
    save = { listOf(it.ply.toLong(), it.side.ordinal.toLong(), it.remainingMillis, it.durationMillis) },
    restore = { XiangqiThinkingClock(it[0].toInt(), XiangqiSide.entries[it[1].toInt()], it[2], it.getOrNull(3) ?: XiangqiThinkingClock.TURN_MILLIS) }
)

/** One room, with toys on the furniture. Opening a toy never starts a background game. */
@Composable
fun SecretBaseScreen(onBack: () -> Unit, onOpenNotes: () -> Unit, onOpenMemories: () -> Unit) {
    val context = LocalContext.current
    val prefs = (context.applicationContext as JiliguluApp).container.userPrefs
    val gamePreferences = remember(context) { context.getSharedPreferences("gulu_secret_games", android.content.Context.MODE_PRIVATE) }
    val archive = remember(context) { LocalGameArchive(context) }
    var archiveReady by remember { mutableStateOf(false) }
    var gameLoading by remember { mutableStateOf(false) }
    var modeLoadJob by remember { mutableStateOf<Job?>(null) }
    var modeLoadGeneration by remember { mutableIntStateOf(0) }
    var leaveRoomAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val petSleeping by prefs.secretPetSleeping.collectAsStateWithLifecycle(gamePreferences.getBoolean("secret_night", false))
    val nickname by prefs.nickname.collectAsStateWithLifecycle("")
    var activity by rememberSaveable { mutableStateOf<SecretActivity?>(null) }
    val fullGame = activity == SecretActivity.SNAKE || activity == SecretActivity.GOMOKU || activity == SecretActivity.XIANGQI
    SceneSystemBars(lightIcons = !fullGame)
    var prize by rememberSaveable { mutableStateOf<String?>(null) }
    var spinning by remember { mutableStateOf(false) }
    val rotation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var spinJob by remember { mutableStateOf<Job?>(null) }
    var snake by rememberSaveable(stateSaver = snakeStateSaver) { mutableStateOf(SnakeEngine.newGame()) }
    var snakeRunning by remember { mutableStateOf(false) }
    var snakeStarted by rememberSaveable { mutableStateOf(false) }
    var gomoku by rememberSaveable(stateSaver = gomokuStateSaver) { mutableStateOf(GomokuEngine.newGame()) }
    var gomokuHistory by remember { mutableStateOf<List<GomokuState>>(emptyList()) }
    var gomokuPaused by remember { mutableStateOf(true) }
    var gomokuStarted by remember { mutableStateOf(false) }
    var gomokuMode by rememberSaveable { mutableStateOf(GomokuPlayMode.CPU) }
    var gomokuUndoConsent by remember { mutableStateOf(false) }
    var gomokuUndoResume by remember { mutableStateOf(false) }
    var xiangqi by rememberSaveable(stateSaver = xiangqiStateSaver) { mutableStateOf(XiangqiEngine.newGame()) }
    var xiangqiHistory by remember { mutableStateOf<List<XiangqiState>>(emptyList()) }
    var xiangqiRestoreToken by remember { mutableIntStateOf(0) }
    var undoConfirmVisible by remember { mutableStateOf(false) }
    var undoResume by remember { mutableStateOf(false) }
    var thinkingSeconds by rememberSaveable { mutableIntStateOf(gamePreferences.getInt("xiangqi_thinking_seconds", 120)
        .coerceIn(XiangqiThinkingClock.MIN_SECONDS, XiangqiThinkingClock.MAX_SECONDS)) }
    var xiangqiClock by rememberSaveable(stateSaver = xiangqiClockSaver) { mutableStateOf(XiangqiThinkingClock.reset(xiangqi, thinkingSeconds)) }
    var xiangqiStarted by rememberSaveable { mutableStateOf(false) }
    var clockSetupVisible by rememberSaveable { mutableStateOf(false) }
    var resumeAfterClockSetup by remember { mutableStateOf(false) }
    var clockEpoch by remember { mutableIntStateOf(0) }
    var clockTickAt by remember { mutableLongStateOf(0L) }
    var xiangqiPaused by remember { mutableStateOf(true) }
    var xiangqiMode by rememberSaveable { mutableStateOf(XiangqiPlayMode.CPU) }
    val lanSession = remember { XiangqiLanSession() }
    val lan by lanSession.state.collectAsStateWithLifecycle()
    val onlineSession = remember(context) { XiangqiOnlineSession(context) }
    val online by onlineSession.state.collectAsStateWithLifecycle()
    val gomokuLanSession = remember { GomokuLanSession() }
    val gomokuLan by gomokuLanSession.state.collectAsStateWithLifecycle()
    val gomokuOnlineSession = remember(context) { GomokuOnlineSession(context) }
    val gomokuOnline by gomokuOnlineSession.state.collectAsStateWithLifecycle()
    val xiangqiDiscovery = remember(context, nickname) { NsdRoomDiscovery(context, NearbyGameKind.XIANGQI, nickname.ifBlank { "棋友" }) }
    val gomokuDiscovery = remember(context, nickname) { NsdRoomDiscovery(context, NearbyGameKind.GOMOKU, nickname.ifBlank { "棋友" }) }
    val nearbyXiangqi by xiangqiDiscovery.state.collectAsStateWithLifecycle()
    val nearbyGomoku by gomokuDiscovery.state.collectAsStateWithLifecycle()
    var starTaps by rememberSaveable { mutableIntStateOf(0) }
    var night by remember { mutableStateOf(gamePreferences.getBoolean("secret_night", false)) }
    val sleeping = night || petSleeping
    var secretBubble by remember { mutableStateOf<String?>(null) }
    var bubbleToken by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var networkGraceJob by remember { mutableStateOf<Job?>(null) }
    var helpJob by remember { mutableStateOf<Job?>(null) }
    var helpBusy by remember { mutableStateOf(false) }
    var helpGeneration by remember { mutableIntStateOf(0) }
    var assistedSelection by remember { mutableStateOf<GridCell?>(null) }
    var helpXiangqiPosition by remember { mutableStateOf<XiangqiState?>(null) }
    var helpGomokuPosition by remember { mutableStateOf<GomokuState?>(null) }
    var gameControlsBottom by remember { mutableFloatStateOf(0f) }

    fun gomokuLocalMode(): LocalGameMode? = when (gomokuMode) {
        GomokuPlayMode.CPU -> LocalGameMode.CPU
        GomokuPlayMode.HOTSEAT -> LocalGameMode.HOTSEAT
        else -> null
    }
    fun xiangqiLocalMode(): LocalGameMode? = when (xiangqiMode) {
        XiangqiPlayMode.CPU -> LocalGameMode.CPU
        XiangqiPlayMode.HOTSEAT -> LocalGameMode.HOTSEAT
        else -> null
    }
    fun checkpointGomoku() {
        if (!archiveReady || gameLoading) return
        val mode = gomokuLocalMode() ?: return
        archive.saveGomoku(LocalGomokuSave(mode, gomoku, gomokuHistory, gomokuStarted, gomokuPaused))
    }
    fun checkpointXiangqi() {
        if (!archiveReady || gameLoading) return
        val mode = xiangqiLocalMode() ?: return
        archive.saveXiangqi(LocalXiangqiSave(mode, xiangqi, xiangqiHistory, xiangqiClock.forPosition(xiangqi),
            thinkingSeconds, xiangqiStarted, xiangqiPaused))
    }
    fun checkpointSnake() {
        if (archiveReady) archive.saveSnake(LocalSnakeSave(snake, snakeStarted, paused = !snakeRunning))
    }
    fun freezeThinkingClock() {
        if (clockTickAt > 0L) {
            xiangqiClock = xiangqiClock.forPosition(xiangqi).elapse(SystemClock.elapsedRealtime() - clockTickAt,
                active = !xiangqiPaused && xiangqiLocalMode() != null)
            clockTickAt = 0L
            clockEpoch++ // The cancelled ticker's finally block must not subtract this fraction twice.
        }
    }
    fun cancelModeLoad() {
        modeLoadGeneration++
        modeLoadJob?.cancel(); modeLoadJob = null; gameLoading = false
    }
    fun restoreGomoku(save: LocalGomokuSave?) {
        gomoku = save?.game ?: GomokuEngine.newGame()
        gomokuHistory = save?.undoHistory ?: emptyList()
        gomokuStarted = save?.started ?: false
        gomokuPaused = true
    }
    fun restoreXiangqi(save: LocalXiangqiSave?) {
        xiangqiRestoreToken++
        xiangqi = save?.game ?: XiangqiEngine.newGame()
        xiangqiHistory = save?.undoHistory ?: emptyList()
        thinkingSeconds = save?.thinkingSeconds ?: gamePreferences.getInt("xiangqi_thinking_seconds", 120)
            .coerceIn(XiangqiThinkingClock.MIN_SECONDS, XiangqiThinkingClock.MAX_SECONDS)
        xiangqiClock = save?.clock ?: XiangqiThinkingClock.reset(xiangqi, thinkingSeconds)
        xiangqiStarted = save?.started ?: false
        xiangqiPaused = true; clockTickAt = 0L; clockEpoch++
    }
    LaunchedEffect(archive) {
        val goMode = archive.preferredGomokuMode()
        val xqMode = archive.preferredXiangqiMode()
        val savedGo = archive.loadGomoku(goMode)
        val savedXq = archive.loadXiangqi(xqMode)
        val savedSnake = archive.loadSnake()
        restoreGomoku(savedGo); restoreXiangqi(savedXq)
        gomokuMode = if (goMode == LocalGameMode.CPU) GomokuPlayMode.CPU else GomokuPlayMode.HOTSEAT
        xiangqiMode = if (xqMode == LocalGameMode.CPU) XiangqiPlayMode.CPU else XiangqiPlayMode.HOTSEAT
        savedSnake?.let { snake = it.game; snakeStarted = it.started }
        snakeRunning = false; archiveReady = true
    }

    fun commitGomoku(next: GomokuState) {
        if (next == gomoku) return
        gomokuHistory = (gomokuHistory + gomoku).takeLast(225)
        gomoku = next; gomokuStarted = true
        checkpointGomoku()
        if (foreground && activity == SecretActivity.GOMOKU) UiSound.drop(context)
    }
    fun commitXiangqi(next: XiangqiState) {
        if (next == xiangqi) return
        xiangqiHistory = (xiangqiHistory + xiangqi).takeLast(512)
        xiangqi = next
        clockTickAt = 0L; clockEpoch++; xiangqiClock = xiangqiClock.forPosition(next)
        checkpointXiangqi()
        if (foreground && activity == SecretActivity.XIANGQI) UiSound.drop(context)
    }

    fun currentXiangqiPosition(): XiangqiState = when (xiangqiMode) {
        XiangqiPlayMode.ONLINE -> onlineSession.state.value.game
        XiangqiPlayMode.LAN -> lanSession.state.value.game
        else -> xiangqi
    }
    fun currentGomokuPosition(): GomokuState = when (gomokuMode) {
        GomokuPlayMode.NEARBY -> gomokuLanSession.state.value.game
        GomokuPlayMode.ONLINE -> gomokuOnlineSession.state.value.game
        else -> gomoku
    }
    fun eligibleGomokuTurn(): Boolean {
        if (!archiveReady || gameLoading || !foreground || sleeping || activity != SecretActivity.GOMOKU || gomokuUndoConsent) return false
        val position = currentGomokuPosition()
        if (position.outcome != GomokuOutcome.PLAYING) return false
        return when (gomokuMode) {
            GomokuPlayMode.CPU -> !gomokuPaused && position.currentPlayer == 1
            GomokuPlayMode.HOTSEAT -> !gomokuPaused
            GomokuPlayMode.NEARBY, GomokuPlayMode.ONLINE -> {
                val room = if (gomokuMode == GomokuPlayMode.ONLINE) gomokuOnlineSession.state.value else gomokuLanSession.state.value
                room.connected && !room.awaitingAck && room.pendingUndoRequest == null && position.currentPlayer == room.localPlayer
            }
        }
    }
    fun playGomokuMove(cell: GridCell) {
        if (!eligibleGomokuTurn()) return
        when (gomokuMode) {
            GomokuPlayMode.NEARBY -> gomokuLanSession.submitMove(cell)
            GomokuPlayMode.ONLINE -> gomokuOnlineSession.submitMove(cell)
            else -> commitGomoku(GomokuEngine.play(gomoku, cell.x, cell.y))
        }
    }
    fun eligibleXiangqiTurn(): Boolean {
        if (!archiveReady || gameLoading || !foreground || sleeping || activity != SecretActivity.XIANGQI || clockSetupVisible) return false
        val position = currentXiangqiPosition()
        if (position.outcome != XiangqiOutcome.PLAYING) return false
        return when (xiangqiMode) {
            XiangqiPlayMode.CPU -> xiangqiStarted && !xiangqiPaused && position.turnSide == XiangqiSide.RED
            XiangqiPlayMode.HOTSEAT -> xiangqiStarted && !xiangqiPaused
            XiangqiPlayMode.ONLINE, XiangqiPlayMode.LAN -> {
                val room = if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.state.value else lanSession.state.value
                room.connected && !room.awaitingAck && room.pendingUndoRequest == null && position.turnSide == room.localSide
            }
        }
    }
    fun playXiangqiMove(move: XiangqiMove) {
        if (!eligibleXiangqiTurn()) return
        if (xiangqiMode == XiangqiPlayMode.LAN) lanSession.submitMove(move)
        else if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.submitMove(move)
        else commitXiangqi(XiangqiEngine.play(xiangqi, move))
    }
    fun cancelHelp() {
        helpGeneration++
        helpJob?.cancel(); helpJob = null; helpBusy = false
        if (assistedSelection != null) {
            if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.selectPiece(null)
            else if (xiangqiMode == XiangqiPlayMode.LAN) lanSession.selectPiece(null)
        }
        assistedSelection = null; helpXiangqiPosition = null; helpGomokuPosition = null
    }
    fun requestXiangqiHelp() {
        if (helpBusy || !eligibleXiangqiTurn()) return
        val position = currentXiangqiPosition()
        val generation = ++helpGeneration
        helpXiangqiPosition = position; helpBusy = true
        helpJob = scope.launch {
            try {
                val move = withContext(Dispatchers.Default) {
                    val computeContext = currentCoroutineContext()
                    XiangqiStrongMoveHelper.chooseMove(position) { !computeContext.isActive }
                } ?: return@launch
                if (helpGeneration != generation || !eligibleXiangqiTurn() || currentXiangqiPosition() != position) return@launch
                assistedSelection = move.from
                UiSound.select(context)
                if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.selectPiece(move.from)
                else if (xiangqiMode == XiangqiPlayMode.LAN) lanSession.selectPiece(move.from)
                delay(600)
                if (helpGeneration == generation && eligibleXiangqiTurn() && currentXiangqiPosition() == position)
                    playXiangqiMove(move)
            } finally {
                if (helpGeneration == generation) {
                    helpBusy = false; assistedSelection = null; helpXiangqiPosition = null; helpJob = null
                }
            }
        }
    }
    fun requestGomokuHelp() {
        if (helpBusy || !eligibleGomokuTurn()) return
        val position = currentGomokuPosition()
        val generation = ++helpGeneration
        helpGomokuPosition = position; helpBusy = true
        helpJob = scope.launch {
            try {
                val move = withContext(Dispatchers.Default) {
                    val computeContext = currentCoroutineContext()
                    GomokuStrongMoveHelper.chooseMove(position) { !computeContext.isActive }
                } ?: return@launch
                delay(500)
                if (helpGeneration == generation && eligibleGomokuTurn() && currentGomokuPosition() == position)
                    playGomokuMove(move)
            } finally {
                if (helpGeneration == generation) { helpBusy = false; helpGomokuPosition = null; helpJob = null }
            }
        }
    }

    fun pauseLocalToys() {
        cancelHelp()
        freezeThinkingClock()
        snakeRunning = false
        gomokuPaused = true
        xiangqiPaused = true
        spinJob?.cancel()
        spinning = false
        checkpointGomoku(); checkpointXiangqi(); checkpointSnake()
    }
    fun closeNetworkRooms() {
        networkGraceJob?.cancel()
        networkGraceJob = null
        lanSession.close()
        onlineSession.close()
        gomokuLanSession.close(); gomokuOnlineSession.close()
        xiangqiDiscovery.stop(); gomokuDiscovery.stop()
    }
    fun changeGomokuMode(value: GomokuPlayMode) {
        if (!archiveReady) return
        pauseLocalToys(); cancelModeLoad(); closeNetworkRooms(); gomokuUndoConsent = false
        if (value == GomokuPlayMode.NEARBY || value == GomokuPlayMode.ONLINE) { gomokuMode = value; return }
        val localMode = if (value == GomokuPlayMode.CPU) LocalGameMode.CPU else LocalGameMode.HOTSEAT
        val token = ++modeLoadGeneration
        gameLoading = true
        modeLoadJob = scope.launch {
            try {
                val saved = archive.loadGomoku(localMode)
                if (token != modeLoadGeneration) return@launch
                restoreGomoku(saved); gomokuMode = value
                if (saved == null) { gomokuStarted = true; gomokuPaused = !foreground }
                gameLoading = false; checkpointGomoku()
            } finally { if (token == modeLoadGeneration) { gameLoading = false; modeLoadJob = null } }
        }
    }
    fun changeXiangqiMode(value: XiangqiPlayMode) {
        if (!archiveReady) return
        pauseLocalToys(); cancelModeLoad(); closeNetworkRooms(); undoConfirmVisible = false
        clockSetupVisible = false; resumeAfterClockSetup = false
        if (value == XiangqiPlayMode.LAN || value == XiangqiPlayMode.ONLINE) { xiangqiMode = value; return }
        val localMode = if (value == XiangqiPlayMode.CPU) LocalGameMode.CPU else LocalGameMode.HOTSEAT
        val token = ++modeLoadGeneration
        gameLoading = true
        modeLoadJob = scope.launch {
            try {
                val saved = archive.loadXiangqi(localMode)
                if (token != modeLoadGeneration) return@launch
                restoreXiangqi(saved); xiangqiMode = value
                gameLoading = false; checkpointXiangqi()
                if (!xiangqiStarted && foreground && activity == SecretActivity.XIANGQI) clockSetupVisible = true
            } finally { if (token == modeLoadGeneration) { gameLoading = false; modeLoadJob = null } }
        }
    }
    fun activeRoomConnected(): Boolean = when (activity) {
        SecretActivity.GOMOKU -> when (gomokuMode) {
            GomokuPlayMode.ONLINE -> gomokuOnlineSession.state.value.connected
            GomokuPlayMode.NEARBY -> gomokuLanSession.state.value.connected
            else -> false
        }
        SecretActivity.XIANGQI -> when (xiangqiMode) {
            XiangqiPlayMode.ONLINE -> onlineSession.state.value.connected
            XiangqiPlayMode.LAN -> lanSession.state.value.connected
            else -> false
        }
        else -> false
    }
    fun leaveNetworkSafely(action: () -> Unit) {
        if (activeRoomConnected()) { cancelHelp(); leaveRoomAction = action } else action()
    }
    fun pauseToys() { pauseLocalToys(); closeNetworkRooms() }
    fun closeToy() { pauseToys(); cancelModeLoad(); leaveRoomAction = null; activity = null; clockSetupVisible = false; resumeAfterClockSetup = false; undoConfirmVisible = false; gomokuUndoConsent = false }
    fun openToy(value: SecretActivity) {
        if (!archiveReady || gameLoading) return
        UiSound.tap(context)
        pauseToys()
        gameControlsBottom = 0f
        if (!sleeping && foreground) {
            activity = value
            if (value == SecretActivity.GOMOKU && gomokuLocalMode() != null && !gomokuStarted) {
                gomokuStarted = true; gomokuPaused = false; checkpointGomoku()
            }
            if (value == SecretActivity.XIANGQI && xiangqiMode != XiangqiPlayMode.ONLINE && xiangqiMode != XiangqiPlayMode.LAN && !xiangqiStarted) {
                xiangqiPaused = true
                resumeAfterClockSetup = false
                clockSetupVisible = true
            }
        }
    }
    fun rest(value: Boolean) {
        UiSound.tap(context)
        closeToy()
        night = value
        gamePreferences.edit().putBoolean("secret_night", value).apply()
        scope.launch { withContext(NonCancellable) { prefs.setSecretPetSleeping(value) } }
    }

    fun undoXiangqi() {
        cancelHelp()
        val target = LocalChessUndo.xiangqiTarget(xiangqiHistory, xiangqiMode == XiangqiPlayMode.CPU)
        if (target < 0) return
        xiangqi = xiangqiHistory[target]
        xiangqiHistory = xiangqiHistory.take(target)
        xiangqiClock = XiangqiThinkingClock.reset(xiangqi, thinkingSeconds)
        clockTickAt = 0L; clockEpoch++; checkpointXiangqi()
    }
    fun undoGomoku() {
        cancelHelp()
        val target = if (gomokuMode == GomokuPlayMode.HOTSEAT) gomokuHistory.lastIndex else LocalChessUndo.gomokuTarget(gomokuHistory)
        if (target >= 0) { gomoku = gomokuHistory[target]; gomokuHistory = gomokuHistory.take(target); checkpointGomoku() }
    }

    DisposableEffect(xiangqiDiscovery, gomokuDiscovery) {
        onDispose { xiangqiDiscovery.stop(); gomokuDiscovery.stop() }
    }
    LaunchedEffect(activity, foreground, sleeping, archiveReady, gameLoading, xiangqiMode, xiangqiDiscovery, lan.connected, lan.hostAddress, lan.sessionActive, lan.error) {
        if (archiveReady && !gameLoading && activity == SecretActivity.XIANGQI && foreground && !sleeping && xiangqiMode == XiangqiPlayMode.LAN && !lan.connected) {
            if (!lanSession.state.value.sessionActive && lanSession.state.value.error == null) lanSession.host()
            val room = lanSession.state.value
            if (room.sessionActive && room.localSide == XiangqiSide.RED && room.hostAddress.isNotBlank() && room.error == null) xiangqiDiscovery.start()
            else xiangqiDiscovery.stop()
        } else xiangqiDiscovery.stop()
    }
    LaunchedEffect(activity, foreground, sleeping, archiveReady, gameLoading, gomokuMode, gomokuDiscovery, gomokuLan.connected, gomokuLan.hostAddress, gomokuLan.sessionActive, gomokuLan.error) {
        if (archiveReady && !gameLoading && activity == SecretActivity.GOMOKU && foreground && !sleeping && gomokuMode == GomokuPlayMode.NEARBY && !gomokuLan.connected) {
            if (!gomokuLanSession.state.value.sessionActive && gomokuLanSession.state.value.error == null) gomokuLanSession.host()
            val room = gomokuLanSession.state.value
            if (room.sessionActive && room.localPlayer == 1 && room.hostAddress.isNotBlank() && room.error == null) gomokuDiscovery.start()
            else gomokuDiscovery.stop()
        } else gomokuDiscovery.stop()
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                resumeAfterClockSetup = false
                pauseLocalToys()
                archive.flushBlocking(350)
                foreground = false
                xiangqiDiscovery.stop(); gomokuDiscovery.stop()
                networkGraceJob?.cancel()
                val sharingRoom = !sleeping && (activity == SecretActivity.XIANGQI &&
                    (xiangqiMode == XiangqiPlayMode.ONLINE || xiangqiMode == XiangqiPlayMode.LAN) &&
                    (lanSession.state.value.sessionActive || onlineSession.state.value.sessionActive) ||
                    activity == SecretActivity.GOMOKU && (gomokuMode == GomokuPlayMode.NEARBY || gomokuMode == GomokuPlayMode.ONLINE) &&
                    (gomokuLanSession.state.value.sessionActive || gomokuOnlineSession.state.value.sessionActive))
                if (sharingRoom) networkGraceJob = scope.launch {
                    delay(120_000)
                    closeNetworkRooms()
                    networkGraceJob = null
                } else closeNetworkRooms()
            }
            if (event == Lifecycle.Event.ON_START) {
                networkGraceJob?.cancel(); networkGraceJob = null
                foreground = true
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); pauseLocalToys(); cancelModeLoad(); spinJob?.cancel(); closeNetworkRooms() }
    }
    LaunchedEffect(sleeping) { if (sleeping) closeToy() }
    val visibleNetwork = if (xiangqiMode == XiangqiPlayMode.ONLINE) online else lan
    var lastNetworkGame by remember(activity, xiangqiMode, foreground) { mutableStateOf(visibleNetwork.game) }
    LaunchedEffect(visibleNetwork.revision, activity, xiangqiMode, foreground) {
        val next = visibleNetwork.game
        if (foreground && activity == SecretActivity.XIANGQI &&
            (xiangqiMode == XiangqiPlayMode.ONLINE || xiangqiMode == XiangqiPlayMode.LAN) &&
            next.ply == lastNetworkGame.ply + 1 && next.board != lastNetworkGame.board) UiSound.drop(context)
        lastNetworkGame = next
    }
    val visibleGomokuRoom = if (gomokuMode == GomokuPlayMode.ONLINE) gomokuOnline else gomokuLan
    var lastNetworkGomoku by remember(activity, gomokuMode, foreground) { mutableStateOf(visibleGomokuRoom.game) }
    LaunchedEffect(visibleGomokuRoom.revision, activity, gomokuMode, foreground) {
        val next = visibleGomokuRoom.game
        if (foreground && activity == SecretActivity.GOMOKU &&
            (gomokuMode == GomokuPlayMode.ONLINE || gomokuMode == GomokuPlayMode.NEARBY) &&
            next.board.count { it != 0 } == lastNetworkGomoku.board.count { it != 0 } + 1 && next.board != lastNetworkGomoku.board) UiSound.drop(context)
        lastNetworkGomoku = next
    }
    LaunchedEffect(bubbleToken) { if (secretBubble != null) { delay(3000); secretBubble = null } }
    LaunchedEffect(gomoku, xiangqi, online.game, lan.game, gomokuOnline.game, gomokuLan.game,
        online.connected, lan.connected, online.awaitingAck, lan.awaitingAck, online.pendingUndoRequest, lan.pendingUndoRequest,
        gomokuOnline.pendingUndoRequest, gomokuLan.pendingUndoRequest) {
        if (helpBusy && ((helpGomokuPosition != null && (helpGomokuPosition != currentGomokuPosition() || !eligibleGomokuTurn())) ||
            (helpXiangqiPosition != null && (helpXiangqiPosition != currentXiangqiPosition() || !eligibleXiangqiTurn())))) cancelHelp()
    }
    LaunchedEffect(activity, foreground, sleeping, archiveReady, gameLoading, xiangqiPaused, xiangqiMode, xiangqi.turnSide, xiangqi.outcome) {
        if (archiveReady && !gameLoading && activity == SecretActivity.XIANGQI && foreground && !sleeping && !xiangqiPaused &&
            xiangqiMode == XiangqiPlayMode.CPU && xiangqi.turnSide == XiangqiSide.BLACK && xiangqi.outcome == XiangqiOutcome.PLAYING) {
            val position = xiangqi
            delay(420)
            val move = withContext(Dispatchers.Default) { XiangqiEngine.chooseCpuMove(position) }
            if (archiveReady && !gameLoading && activity == SecretActivity.XIANGQI && foreground && !sleeping && !xiangqiPaused &&
                xiangqiMode == XiangqiPlayMode.CPU && xiangqi == position && move != null) commitXiangqi(XiangqiEngine.play(position, move))
        }
    }
    LaunchedEffect(activity, foreground, sleeping, archiveReady, gameLoading, xiangqiPaused, xiangqiMode, xiangqi.ply,
        xiangqi.turnSide, xiangqi.outcome, clockEpoch) {
        xiangqiClock = xiangqiClock.forPosition(xiangqi)
        val localMode = xiangqiMode == XiangqiPlayMode.CPU || xiangqiMode == XiangqiPlayMode.HOTSEAT
        if (archiveReady && !gameLoading && activity == SecretActivity.XIANGQI && foreground && !sleeping && !xiangqiPaused &&
            localMode && xiangqi.outcome == XiangqiOutcome.PLAYING) {
            var lastTick = SystemClock.elapsedRealtime()
            clockTickAt = lastTick
            val clockPosition = xiangqi
            val epoch = clockEpoch
            try {
                while (!xiangqiClock.expired) {
                    delay(1000)
                    val now = SystemClock.elapsedRealtime()
                    xiangqiClock = xiangqiClock.elapse(now - lastTick, active = true)
                    lastTick = now
                    clockTickAt = now
                }
            } finally {
                if (clockEpoch == epoch && xiangqiClock.ply == clockPosition.ply && xiangqiClock.side == clockPosition.turnSide)
                    xiangqiClock = xiangqiClock.elapse(SystemClock.elapsedRealtime() - lastTick, active = true)
                if (clockEpoch == epoch) clockTickAt = 0L
            }
        }
    }
    LaunchedEffect(activity, foreground, sleeping, archiveReady, gameLoading, snakeRunning) {
        var ticks = 0
        while (archiveReady && !gameLoading && activity == SecretActivity.SNAKE && foreground && !sleeping && snakeRunning && !snake.gameOver) {
            delay((260L - (snake.score / 3) * 8L).coerceAtLeast(140L))
            if (activity == SecretActivity.SNAKE && foreground && !sleeping && snakeRunning) {
                val oldScore = snake.score
                snake = SnakeEngine.tick(snake)
                if (snake.gameOver) snakeRunning = false
                if (++ticks % 4 == 0 || oldScore != snake.score || snake.gameOver) checkpointSnake()
            }
        }
    }
    LaunchedEffect(activity, foreground, sleeping, archiveReady, gameLoading, gomokuMode, gomokuPaused, gomoku.currentPlayer, gomoku.outcome, gomoku.size) {
        if (archiveReady && !gameLoading && activity == SecretActivity.GOMOKU && foreground && !sleeping && !gomokuPaused &&
            gomokuMode == GomokuPlayMode.CPU &&
            gomoku.currentPlayer == 2 && gomoku.outcome == GomokuOutcome.PLAYING) {
            val position = gomoku
            delay(420)
            val move = withContext(Dispatchers.Default) {
                val computeContext = currentCoroutineContext()
                GomokuEngine.chooseCpuMove(position) { !computeContext.isActive }
            }
            if (archiveReady && !gameLoading && activity == SecretActivity.GOMOKU && foreground && !sleeping && !gomokuPaused && gomokuMode == GomokuPlayMode.CPU && gomoku == position) {
                move?.let { commitGomoku(GomokuEngine.play(position, it.x, it.y)) }
            }
        }
    }
    BackHandler { if (activity != null) leaveNetworkSafely(::closeToy) else { pauseToys(); onBack() } }

    Box(Modifier.fillMaxSize().background(Color(0xFFE8D1B0))) {
        if (!fullGame) SecretRoomStage(sleeping = sleeping, toysEnabled = archiveReady && !gameLoading, onPet = { rest(!sleeping) },
            onWheel = { openToy(SecretActivity.WHEEL) }, onSnake = { openToy(SecretActivity.SNAKE) },
            onGomoku = { openToy(SecretActivity.BOARD) }, onPaper = { openToy(SecretActivity.PAPER) },
            night = sleeping, sparkleToken = starTaps, bubble = secretBubble,
            onStar = { UiSound.select(context); starTaps++; secretBubble = if (starTaps % 3 == 0) "找到暗号啦：今天可以慢慢来 ♡" else "这颗星星把一点好运藏进你口袋里了 ✦"; bubbleToken++ },
            onLamp = { rest(!sleeping) },
            onPetSecret = { UiSound.select(context); secretBubble = "嘘，阿噜偷偷告诉你：它最喜欢你来坐一会儿。"; bubbleToken++ })
        if (activity == SecretActivity.XIANGQI && xiangqiMode == XiangqiPlayMode.ONLINE && online.sessionActive) {
            onlineSession.transportView?.let { transport ->
                key(transport) {
                    AndroidView(factory = { transport }, modifier = Modifier.size(1.dp).graphicsLayer { alpha = 0f })
                }
            }
        }
        if (activity == SecretActivity.GOMOKU && gomokuMode == GomokuPlayMode.ONLINE && gomokuOnline.sessionActive) {
            gomokuOnlineSession.transportView?.let { transport ->
                key(transport) { AndroidView(factory = { transport }, modifier = Modifier.size(1.dp).graphicsLayer { alpha = 0f }) }
            }
        }
        if (!sleeping) activity?.let { toy ->
        val title = when (toy) {
            SecretActivity.WHEEL -> "阿噜的好运转盘"
            SecretActivity.SNAKE -> "小蛇吃星星"
            SecretActivity.BOARD -> "阿噜的小棋桌"
            SecretActivity.GOMOKU -> "五子棋"
            SecretActivity.XIANGQI -> "阿噜棋桌"
            SecretActivity.PAPER -> "阿噜的秘密纸条"
        }
        val toyContent: @Composable ColumnScope.(androidx.compose.ui.unit.Dp) -> Unit = { boardSize ->
            when (toy) {
                SecretActivity.SNAKE -> SecretSnakeGame(snake, snakeRunning, snakeStarted, boardSize,
                    onDirection = { if (archiveReady && !snake.gameOver && foreground) {
                        snake = SnakeEngine.turn(snake, it); snakeStarted = true; snakeRunning = true; checkpointSnake()
                    } },
                    onToggle = { snakeStarted = true; snakeRunning = !snakeRunning && !snake.gameOver && foreground; checkpointSnake() },
                    onRestart = { snake = SnakeEngine.newGame(); snakeRunning = false; snakeStarted = false; checkpointSnake() })
                SecretActivity.GOMOKU -> SecretGomokuGame(currentGomokuPosition(), gomokuPaused || gameLoading || !archiveReady, boardSize,
                    onMove = { x, y -> if (!helpBusy) playGomokuMove(GridCell(x, y)) },
                    onToggle = { cancelHelp(); gomokuStarted = true; gomokuPaused = !gomokuPaused; checkpointGomoku() },
                    onRestart = { cancelHelp(); when (gomokuMode) {
                        GomokuPlayMode.ONLINE -> gomokuOnlineSession.restart()
                        GomokuPlayMode.NEARBY -> gomokuLanSession.restart()
                        else -> { gomokuHistory = emptyList(); gomoku = GomokuEngine.newGame(); gomokuStarted = true; gomokuPaused = false; checkpointGomoku() }
                    } },
                    mode = gomokuMode, onMode = { value -> leaveNetworkSafely { changeGomokuMode(value) } }, room = visibleGomokuRoom, nearby = nearbyGomoku,
                    onHost = { if (foreground) gomokuOnlineSession.host() },
                    onJoin = { address -> if (foreground) {
                        if (gomokuMode == GomokuPlayMode.ONLINE) gomokuOnlineSession.join(address)
                        else { gomokuDiscovery.stop(); gomokuLanSession.join(address) }
                    } },
                    onDisconnect = { if (activeRoomConnected()) leaveNetworkSafely(::closeToy) else { cancelHelp(); closeNetworkRooms() } },
                    onNearbyRetry = { if (foreground) { gomokuLanSession.host(); gomokuDiscovery.stop(); gomokuDiscovery.start() } },
                    canUndo = when (gomokuMode) {
                        GomokuPlayMode.ONLINE -> gomokuOnline.canUndo
                        GomokuPlayMode.NEARBY -> gomokuLan.canUndo
                        GomokuPlayMode.HOTSEAT -> gomokuHistory.isNotEmpty()
                        else -> LocalChessUndo.gomokuTarget(gomokuHistory) >= 0
                    },
                    onUndo = { cancelHelp(); when (gomokuMode) {
                        GomokuPlayMode.ONLINE -> gomokuOnlineSession.requestUndo()
                        GomokuPlayMode.NEARBY -> gomokuLanSession.requestUndo()
                        GomokuPlayMode.CPU -> undoGomoku()
                        GomokuPlayMode.HOTSEAT -> { gomokuUndoResume = !gomokuPaused; gomokuPaused = true; checkpointGomoku(); gomokuUndoConsent = true }
                    } },
                    onUndoResponse = { accept -> if (gomokuMode == GomokuPlayMode.ONLINE) gomokuOnlineSession.respondToUndo(accept)
                        else if (gomokuMode == GomokuPlayMode.NEARBY) gomokuLanSession.respondToUndo(accept) },
                    helpBusy = helpBusy || gameLoading || !archiveReady, onControlsBottom = { gameControlsBottom = it })
                SecretActivity.BOARD -> {
                    Text("棋盘替你铺好了，今天想下哪一种？", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { openToy(SecretActivity.GOMOKU) }) { Text("五子棋") }
                        OutlinedButton(onClick = { openToy(SecretActivity.XIANGQI) }) { Text("中国象棋") }
                    }
                }
                SecretActivity.XIANGQI -> SecretXiangqiGame(
                    state = when (xiangqiMode) { XiangqiPlayMode.LAN -> lan.game; XiangqiPlayMode.ONLINE -> online.game; else -> xiangqi },
                    mode = xiangqiMode, paused = xiangqiPaused || gameLoading || !archiveReady, boardWidth = boardSize,
                    thinkingClock = xiangqiClock,
                    lan = if (xiangqiMode == XiangqiPlayMode.ONLINE) online else lan,
                    remoteSelection = when (xiangqiMode) {
                        XiangqiPlayMode.ONLINE -> online.remoteSelection
                        XiangqiPlayMode.LAN -> lan.remoteSelection
                        else -> null
                    },
                    onSelectionChanged = { cell ->
                        if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.selectPiece(cell)
                        else if (xiangqiMode == XiangqiPlayMode.LAN) lanSession.selectPiece(cell)
                    },
                    onMode = { value -> leaveNetworkSafely { changeXiangqiMode(value) } },
                    onMove = { move -> if (!helpBusy) playXiangqiMove(move) },
                    onToggle = { cancelHelp(); freezeThinkingClock(); if (!xiangqiStarted) { xiangqiPaused = true; resumeAfterClockSetup = false; clockSetupVisible = true }
                        else xiangqiPaused = !xiangqiPaused
                        checkpointXiangqi() },
                    onRestart = { cancelHelp(); if (xiangqiMode == XiangqiPlayMode.LAN) lanSession.restart()
                        else if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.restart()
                        else { xiangqiHistory = emptyList(); xiangqi = XiangqiEngine.newGame(); xiangqiClock = XiangqiThinkingClock.reset(xiangqi, thinkingSeconds)
                            clockTickAt = 0L; clockEpoch++; xiangqiStarted = false; xiangqiPaused = true; resumeAfterClockSetup = false; clockSetupVisible = true; checkpointXiangqi() } },
                    onHost = { if (foreground) { if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.host() else lanSession.host() } },
                    onJoin = { address -> if (foreground) { if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.join(address)
                        else { xiangqiDiscovery.stop(); lanSession.join(address) } } },
                    nearby = nearbyXiangqi,
                    onNearbyRetry = { if (foreground) { lanSession.host(); xiangqiDiscovery.stop(); xiangqiDiscovery.start() } },
                    onDisconnect = { if (activeRoomConnected()) leaveNetworkSafely(::closeToy) else { cancelHelp(); closeNetworkRooms() } },
                    onPuzzle = { position -> cancelHelp(); xiangqiHistory = emptyList(); xiangqi=position; xiangqiClock=XiangqiThinkingClock.reset(position, thinkingSeconds)
                        clockTickAt = 0L; clockEpoch++; xiangqiStarted=true; xiangqiPaused=false; checkpointXiangqi() },
                    helpBusy = helpBusy || gameLoading || !archiveReady, assistedSelection = assistedSelection,
                    restorationToken = xiangqiRestoreToken,
                    canUndo = when (xiangqiMode) {
                        XiangqiPlayMode.ONLINE -> online.canUndo
                        XiangqiPlayMode.LAN -> lan.canUndo
                        else -> LocalChessUndo.xiangqiTarget(xiangqiHistory, xiangqiMode == XiangqiPlayMode.CPU) >= 0
                    },
                    onUndo = { cancelHelp(); when (xiangqiMode) {
                        XiangqiPlayMode.ONLINE -> onlineSession.requestUndo()
                        XiangqiPlayMode.LAN -> lanSession.requestUndo()
                        XiangqiPlayMode.CPU -> undoXiangqi()
                        XiangqiPlayMode.HOTSEAT -> { undoResume = !xiangqiPaused; freezeThinkingClock(); xiangqiPaused = true; checkpointXiangqi(); undoConfirmVisible = true }
                    } },
                    onUndoResponse = { accept -> if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.respondToUndo(accept)
                        else if (xiangqiMode == XiangqiPlayMode.LAN) lanSession.respondToUndo(accept) },
                    onModalOpened = ::cancelHelp, onControlsBottom = { gameControlsBottom = it })
                SecretActivity.PAPER -> Unit // A fixed-size paper desk owns its own dialog below.
                SecretActivity.WHEEL -> {
                    SecretPrizeWheel(rotation.value, minOf(boardSize, 270.dp))
                    Button(onClick = {
                        UiSound.tap(context)
                        if (!spinning && foreground) spinJob = scope.launch {
                            spinning = true; prize = null
                            try {
                                val winner = Random.nextInt(8)
                                val stop = 360f - (winner * 45f + 22.5f)
                                rotation.animateTo(rotation.value + 1800f + (stop - rotation.value % 360f + 360f) % 360f,
                                    tween(1900, easing = FastOutSlowInEasing))
                                rotation.snapTo(rotation.value % 360f); prize = tinyPrizes[winner]
                            } finally { spinning = false }
                        }
                    }, enabled = !spinning && foreground) { Text(if (spinning) "好运在路上…" else "转一下，交给阿噜") }
                    prize?.let { result ->
                        Text("这次的小任务：$result ♡", color = MaterialTheme.colorScheme.primary)
                        when (result) {
                            "写封未来信" -> TextButton(onClick = { closeToy(); onOpenNotes() }) { Text("去寄一封") }
                            "看一张照片" -> TextButton(onClick = { closeToy(); onOpenMemories() }) { Text("翻翻纪念册") }
                            "听一句悄悄话" -> TextButton(onClick = { openToy(SecretActivity.PAPER) }) { Text("去听悄悄话") }
                        }
                    }
                }
            }
        }
        if (gomokuUndoConsent && activity == SecretActivity.GOMOKU && gomokuMode == GomokuPlayMode.HOTSEAT) {
            com.jiligulu.app.ui.components.GuluDialog("可以退回这一步吗？", {
                gomokuUndoConsent = false; gomokuPaused = !gomokuUndoResume; checkpointGomoku()
            }, compact = true, confirmLabel = "同意", onConfirm = {
                undoGomoku(); gomokuUndoConsent = false; gomokuPaused = !gomokuUndoResume; checkpointGomoku()
            }, dismissLabel = "不同意") {
                Text("把手机交给对方，等对方选择后再继续。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (undoConfirmVisible && activity == SecretActivity.XIANGQI && xiangqiMode == XiangqiPlayMode.HOTSEAT) {
            com.jiligulu.app.ui.components.GuluDialog("可以退回这一步吗？", {
                undoConfirmVisible = false; xiangqiPaused = !undoResume; checkpointXiangqi()
            }, compact = true, confirmLabel = "同意", onConfirm = {
                undoXiangqi(); undoConfirmVisible = false; xiangqiPaused = !undoResume; checkpointXiangqi()
            }, dismissLabel = "不同意") {
                Text("把手机交给对方，等对方选择后再继续。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (fullGame) SecretGamePage(title, { leaveNetworkSafely(::closeToy) },
            boardAspect = if (toy == SecretActivity.XIANGQI) 1.13f else 1f,
            reservedHeight = when (toy) {
                SecretActivity.SNAKE -> 320
                SecretActivity.XIANGQI -> 410
                else -> 270
            },
            headerTrailing = {
                if (toy == SecretActivity.XIANGQI) {
                    val networkMode = xiangqiMode == XiangqiPlayMode.ONLINE || xiangqiMode == XiangqiPlayMode.LAN
                    val game = when (xiangqiMode) {
                        XiangqiPlayMode.ONLINE -> online.game
                        XiangqiPlayMode.LAN -> lan.game
                        else -> xiangqi
                    }
                    SharedThinkingClock(game.turnSide,
                        if (xiangqiClock.ply == game.ply && xiangqiClock.side == game.turnSide) xiangqiClock.secondsRemaining else thinkingSeconds,
                        paused = xiangqiPaused, network = networkMode,
                        onClick = { if (!networkMode) {
                            cancelHelp()
                            freezeThinkingClock()
                            resumeAfterClockSetup = xiangqiStarted && !xiangqiPaused && foreground
                            xiangqiPaused = true; checkpointXiangqi(); clockSetupVisible = true
                        } })
                }
            },
            centeredHeader = toy == SecretActivity.XIANGQI,
            gameDecor = toy == SecretActivity.XIANGQI || toy == SecretActivity.GOMOKU,
            decorEnabled = !helpBusy && when (toy) {
                SecretActivity.XIANGQI -> eligibleXiangqiTurn()
                SecretActivity.GOMOKU -> eligibleGomokuTurn()
                else -> false
            },
            decorResetKey = if (toy == SecretActivity.XIANGQI) helpGeneration to currentXiangqiPosition() else helpGeneration to currentGomokuPosition(),
            controlsBottom = gameControlsBottom,
            onDecorSecret = { if (toy == SecretActivity.XIANGQI) requestXiangqiHelp() else if (toy == SecretActivity.GOMOKU) requestGomokuHelp() },
            content = toyContent)
        else if (toy == SecretActivity.PAPER) SecretPapersDialog(secretNotes, ::closeToy)
        else SecretToyDialog(title, ::closeToy, reservedHeight = 265, content = toyContent)
        }
        if (clockSetupVisible && activity == SecretActivity.XIANGQI &&
            xiangqiMode != XiangqiPlayMode.ONLINE && xiangqiMode != XiangqiPlayMode.LAN) {
            XiangqiThinkingTimeDialog(thinkingSeconds, starting = !xiangqiStarted,
                onDismiss = {
                    clockSetupVisible = false
                    if (resumeAfterClockSetup && xiangqiStarted && foreground && activity == SecretActivity.XIANGQI &&
                        xiangqiMode != XiangqiPlayMode.ONLINE && xiangqiMode != XiangqiPlayMode.LAN) xiangqiPaused = false
                    resumeAfterClockSetup = false
                    checkpointXiangqi()
                }, onConfirm = { seconds ->
                    thinkingSeconds = seconds
                    gamePreferences.edit().putInt("xiangqi_thinking_seconds", seconds).apply()
                    xiangqiClock = XiangqiThinkingClock.reset(xiangqi, seconds)
                    clockTickAt = 0L; clockEpoch++; xiangqiStarted = true; xiangqiPaused = !foreground; clockSetupVisible = false; resumeAfterClockSetup = false; checkpointXiangqi()
                })
        }
        leaveRoomAction?.let { action ->
            com.jiligulu.app.ui.components.GuluDialog("收起这张棋桌吗？", { leaveRoomAction = null },
                confirmLabel = "离开棋桌", onConfirm = { leaveRoomAction = null; action() },
                dismissLabel = "再坐一会儿", compact = true, dense = true, compactWidth = 280.dp) {
                Text("离开会结束当前房间连接；本地棋局会替你留好。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (fullGame && (gameLoading || !archiveReady)) {
            Box(Modifier.matchParentSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            }, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
        }
    }
}

/** Games own the whole destination; the room and its hit targets are removed while playing. */
@Composable
private fun SecretGamePage(title: String, onBack: () -> Unit, boardAspect: Float, reservedHeight: Int,
    headerTrailing: @Composable () -> Unit = {},
    centeredHeader: Boolean = false,
    gameDecor: Boolean = false, decorEnabled: Boolean = false, decorResetKey: Any? = null,
    controlsBottom: Float = 0f,
    onDecorSecret: () -> Unit = {},
    content: @Composable ColumnScope.(androidx.compose.ui.unit.Dp) -> Unit) {
    var stageBottom by remember(title) { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFFFAF7F1)).safeDrawingPadding()
        .onGloballyPositioned { stageBottom = it.boundsInRoot().bottom }) {
        val centeredBoardLimit = if (title == "五子棋") (maxHeight - 400.dp).coerceAtLeast(60.dp) else 560.dp
        val boardSize = minOf((maxWidth - 24.dp).coerceAtLeast(60.dp), centeredBoardLimit,
            ((maxHeight - reservedHeight.dp) / boardAspect).coerceAtLeast(60.dp), 560.dp)
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { UiSound.tap(context); onBack() }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回秘密基地", tint = Color(0xFF665762))
                }
                Text(title, style = if (title == "阿噜棋桌") MaterialTheme.typography.titleLarge.copy(fontFamily = GuluBrandFont, fontSize = 18.sp)
                    else MaterialTheme.typography.titleLarge, color = Color(0xFF514A55))
                Spacer(Modifier.weight(1f))
                if (!centeredHeader) headerTrailing()
            }
            if (centeredHeader) Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) { headerTrailing() }
            content(boardSize)
        }
        val mascotSize = if (maxHeight < 740.dp) 52.dp else 65.dp
        val requiredSpace = with(density) { (mascotSize + 8.dp).toPx() }
        if (gameDecor && controlsBottom > 0f && stageBottom - controlsBottom >= requiredSpace) {
            GameGuluDecoration(enabled = decorEnabled, resetKey = decorResetKey, onSecret = onDecorSecret,
                mascotSize = mascotSize,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 9.dp, bottom = 8.dp))
        }
    }
}

@Composable
private fun GameGuluDecoration(enabled: Boolean, resetKey: Any?, onSecret: () -> Unit,
    mascotSize: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val taps = remember(resetKey, enabled) { HiddenGameHelpTapSequence() }
    val latestSecret by rememberUpdatedState(onSecret)
    Box(modifier.width(126.dp).height(mascotSize).testTag("game-gulu-decor")
        .semantics { contentDescription = "阿噜" }
        .pointerInput(taps, enabled) {
            detectTapGestures { if (taps.tap(SystemClock.elapsedRealtime(), enabled)) latestSecret() }
        }) {
        Canvas(Modifier.matchParentSize().drawWithCache {
            val line = Path().apply {
                moveTo(size.width * .04f, size.height * .88f)
                lineTo(size.width * .29f, size.height * .84f)
                lineTo(size.width * .52f, size.height * .9f)
                lineTo(size.width * .79f, size.height * .85f)
                lineTo(size.width, size.height * .9f)
            }
            onDrawBehind {
                drawPath(line, Color(0xFFAB94B8).copy(alpha = .3f), style = Stroke(1.2.dp.toPx()))
                drawOval(Color(0xFFB29BC6).copy(alpha = .24f), Offset(size.width * .17f, size.height * .67f), Size(8.dp.toPx(), 4.dp.toPx()))
                drawOval(Color(0xFFB29BC6).copy(alpha = .19f), Offset(size.width * .37f, size.height * .91f), Size(7.dp.toPx(), 3.dp.toPx()))
            }
        }) { }
        Image(painterResource(R.drawable.gulu_idle), null,
            Modifier.align(Alignment.BottomEnd).size(mascotSize).graphicsLayer { alpha = .92f })
    }
}

@Composable
private fun SecretToyDialog(title: String, onDismiss: () -> Unit, reservedHeight: Int,
    content: @Composable ColumnScope.(androidx.compose.ui.unit.Dp) -> Unit) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val boardSize = minOf((configuration.screenWidthDp - 64).dp,
        (configuration.screenHeightDp - reservedHeight).coerceAtLeast(130).dp, 350.dp)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        com.jiligulu.app.ui.capture.DialogGlassBackdrop()
        Surface(Modifier.fillMaxWidth(.94f).widthIn(max = 480.dp), shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp, shadowElevation = 12.dp) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), fontFamily = GuluBrandFont, fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { UiSound.tap(context); onDismiss() }) { Text("收好") }
                }
                content(boardSize)
            }
        }
    }
}

@Composable
private fun SecretPrizeWheel(angle: Float, diameter: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val font = remember(context) { context.resources.getFont(R.font.zcool_kuaile) }
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(10.dp).graphicsLayer { rotationZ = angle }) {
            val colors = listOf(Color(0xFFDCCFF1), Color(0xFFF6D5DD), Color(0xFFF8E5BB), Color(0xFFCEE5D6))
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = font; textSize = (size.width / 25f); color = android.graphics.Color.rgb(70, 56, 86)
                textAlign = android.graphics.Paint.Align.CENTER
            }
            repeat(8) { i ->
                drawArc(colors[i % 4], i * 45f - 90f, 45f, true)
                val canvas = drawContext.canvas.nativeCanvas
                val saved = canvas.save()
                canvas.rotate(i * 45f - 67.5f, center.x, center.y)
                canvas.drawText(tinyPrizes[i], center.x + size.width * .27f, center.y + 4.dp.toPx(), paint)
                canvas.restoreToCount(saved)
            }
            drawCircle(Color(0xFFFFFBF4), size.width * .16f)
            drawContext.canvas.nativeCanvas.drawText("阿噜", center.x, center.y + 5.dp.toPx(), paint)
        }
        Text("▼", Modifier.align(Alignment.TopCenter), fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SecretRoomStage(sleeping: Boolean, toysEnabled: Boolean, onPet: () -> Unit,
    onWheel: () -> Unit, onSnake: () -> Unit, onGomoku: () -> Unit, onPaper: () -> Unit,
    night: Boolean, sparkleToken: Int, bubble: String?, onStar: () -> Unit, onLamp: () -> Unit, onPetSecret: () -> Unit) {
    val resources = LocalContext.current.resources
    val resource = R.drawable.world_secret_room_portrait_v3
    val art by produceState<ImageBitmap?>(LittleWorldArtwork.cachedImage(resource), resources, resource) {
        value = withContext(Dispatchers.IO) { LittleWorldArtwork.image(resources, resource) }
    }
    val nightResource = R.drawable.world_secret_room_portrait_v4_night
    val nightArt by produceState<ImageBitmap?>(LittleWorldArtwork.cachedImage(nightResource), resources, nightResource) {
        value = withContext(Dispatchers.IO) { LittleWorldArtwork.image(resources, nightResource) }
    }
    val sparkle = remember { Animatable(0f) }
    val nightBlend = remember { Animatable(if (night) 1f else 0f) }
    LaunchedEffect(night, nightArt) {
        if (!night || nightArt != null) nightBlend.animateTo(if (night) 1f else 0f, tween(800))
    }
    LaunchedEffect(sparkleToken) {
        if (sparkleToken > 0) { sparkle.snapTo(1f); sparkle.animateTo(0f, tween(1100)) }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(if (night) Color(0xFF202033) else Color(0xFFE8D1B0)).clipToBounds()) {
        // Keep first-frame hotspots at the same scale while the portrait decodes on IO.
        val ratio = art?.let { it.width.toFloat() / it.height } ?: (941f / 1672f)
        val naturalWidth = maxOf(maxWidth, maxHeight * ratio)
        val naturalHeight = naturalWidth / ratio
        // Keep the whole toy arrangement reachable even on a narrow phone.
        val sceneWidth = naturalWidth.coerceAtMost(maxWidth / .94f)
        val sceneHeight = naturalHeight.coerceAtMost(maxHeight / .97f)
        val left = (maxWidth - sceneWidth) / 2
        val top = (maxHeight - sceneHeight) / 2
        Canvas(Modifier.matchParentSize()) {
            val sceneOffset = IntOffset(left.toPx().roundToInt(), top.toPx().roundToInt())
            val sceneSize = IntSize(sceneWidth.toPx().roundToInt(), sceneHeight.toPx().roundToInt())
            if (nightBlend.value < 1f && (!night || nightArt != null)) art?.let { bitmap ->
                drawImage(bitmap, dstOffset = sceneOffset, dstSize = sceneSize,
                    filterQuality = FilterQuality.Medium, colorFilter = MutedSceneColorFilter)
            }
            if (nightBlend.value > 0f) nightArt?.let { bitmap ->
                drawImage(bitmap, dstOffset = sceneOffset, dstSize = sceneSize, alpha = nightBlend.value,
                    filterQuality = FilterQuality.Medium, colorFilter = MutedSceneColorFilter)
            }
        }
        SkinSceneDecor(Modifier.matchParentSize(), paintBackground = false)
        // Each plaque sits against its toy; the larger adjoining target includes the painted object.
        fun hotspot(x: Float, y: Float, w: Float, h: Float) = Modifier
            .offset(left + sceneWidth * x, top + sceneHeight * y).size(sceneWidth * w, sceneHeight * h)
        @Composable fun toy(label: String, x: Float, y: Float, w: Float, h: Float,
            tagX: Float, tagY: Float, tagW: Float, tagH: Float, action: () -> Unit) {
            Box(hotspot(x, y, w, h).clickable(enabled = !sleeping && toysEnabled, role = Role.Button, onClickLabel = label, onClick = action)
                .semantics { contentDescription = label })
            Text(label, hotspot(tagX - tagW / 2, tagY - tagH / 2, tagW, tagH)
                .clickable(enabled = !sleeping && toysEnabled, role = Role.Button, onClick = action)
                .wrapContentSize(Alignment.Center), fontFamily = GuluBrandFont,
                fontSize = 10.sp, color = if (night) Color(0xFFCFC1AD) else Color(0xFF66462C), maxLines = 1)
        }
        toy("好运转盘", .15f, .236f, .24f, .168f, .27f, .418f, .107f, .028f, onWheel)
        toy("贪吃蛇", .755f, .29f, .183f, .154f, .846f, .462f, .105f, .028f, onSnake)
        toy("小棋桌", .61f, .541f, .34f, .064f, .854f, .529f, .094f, .027f, onGomoku)
        Box(hotspot(.17f, .488f, .27f, .077f).clickable(enabled = !sleeping && toysEnabled, role = Role.Button,
            onClickLabel = "秘密纸条", onClick = onPaper).semantics { contentDescription = "秘密纸条" })
        Box(hotspot(.30f, .634f, .41f, .172f).combinedClickable(role = Role.Button,
            onClickLabel = if (sleeping) "轻轻叫醒阿噜" else "让阿噜打个盹", onClick = onPet,
            onLongClickLabel = "听阿噜藏起来的小秘密", onLongClick = onPetSecret)
            .semantics { contentDescription = if (sleeping) "轻轻叫醒阿噜" else "让阿噜打个盹" })
        Box(hotspot(.65f, .10f, .18f, .09f).clickable(role = Role.Button, onClickLabel = "摸摸挂着的小星星", onClick = onStar)
            .semantics { contentDescription = "摸摸挂着的小星星" })
        Box(hotspot(.685f, .224f, .10f, .078f).clickable(role = Role.Button,
            onClickLabel = if (night) "开灯叫醒阿噜" else "关灯陪阿噜入睡", onClick = onLamp)
            .semantics { contentDescription = if (night) "开灯叫醒阿噜" else "关灯陪阿噜入睡" })
        Canvas(Modifier.matchParentSize()) {
            val sw = sceneWidth.toPx(); val sh = sceneHeight.toPx()
            if (sparkle.value > 0f) repeat(9) { index ->
                val phase = 1f - sparkle.value
                val angle = index * kotlin.math.PI * 2 / 9
                val center = Offset(left.toPx() + sw * .73f, top.toPx() + sh * .145f)
                val distance = sw * (.05f + phase * .22f)
                val point = center + Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat()) * distance
                drawLine(Color(0xFFFFEEC7).copy(alpha = sparkle.value), point - Offset(4.dp.toPx(), 0f), point + Offset(4.dp.toPx(), 0f), 2.dp.toPx())
                drawLine(Color(0xFFFFEEC7).copy(alpha = sparkle.value), point - Offset(0f, 4.dp.toPx()), point + Offset(0f, 4.dp.toPx()), 2.dp.toPx())
            }
        }
        Text("阿噜的秘密基地", hotspot(.346f, .074f, .314f, .055f).wrapContentSize(Alignment.Center),
            fontFamily = GuluBrandFont, fontSize = 17.sp, color = if (night) Color(0xFFE8D8C3) else Color(0xFF594532))
        if (sleeping) {
            Text("Z z z", hotspot(.63f, .635f, .12f, .04f).wrapContentSize(Alignment.Center),
                fontFamily = GuluBrandFont, fontSize = 27.sp, color = Color(0xFFFFEDD3))
        }
        Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
            shape = RoundedCornerShape(20.dp), color = Color(0xFFFFF3D9).copy(alpha = .93f)) {
            Text(bubble ?: if (sleeping) "嘘，阿噜睡着了。轻轻点它就能叫醒 ♡" else "点点桌上的小玩意儿，坐下来玩一会儿 ♡",
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 11.sp, color = Color(0xFF6B533B))
        }
    }
}
