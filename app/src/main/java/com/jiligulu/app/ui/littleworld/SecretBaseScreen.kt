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
import androidx.compose.ui.graphics.drawscope.clipPath
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
import com.jiligulu.app.core.audio.UiCue
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
private val wheelGroups = listOf(
    "喝口水" to listOf(tinyPrizes[0]), "放松一下" to listOf(tinyPrizes[1],tinyPrizes[5]),
    "寄封信" to listOf(tinyPrizes[2]), "翻照片" to listOf(tinyPrizes[3]),
    "小愿望" to listOf(tinyPrizes[4],tinyPrizes[6]), "悄悄话" to listOf(tinyPrizes[7]))
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

private const val MOVE_SOUND_FOLLOWUP_MS = 150L

/** One room, with toys on the furniture. Opening a toy never starts a background game. */
@Composable
fun SecretBaseScreen(onBack: () -> Unit, onOpenNotes: () -> Unit, onOpenMemories: () -> Unit,
    roomInvite: ChessRoomInvite? = null, onRoomInviteConsumed: () -> Unit = {}) {
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
    var chessName by rememberSaveable { mutableStateOf(gamePreferences.getString("chess_nickname", null)) }
    var chessAvatar by rememberSaveable { mutableStateOf(gamePreferences.getString("chess_avatar", "aru").orEmpty()) }
    val chessProfile = ChessPlayerProfile(chessName ?: nickname, chessAvatar).normalized()
    var editChessProfile by remember { mutableStateOf(false) }
    var hubXiangqi by rememberSaveable { mutableStateOf(true) }
    var activity by rememberSaveable { mutableStateOf<SecretActivity?>(null) }
    var paperFromWheel by rememberSaveable { mutableStateOf(false) }
    var choosingOpponent by rememberSaveable { mutableStateOf(false) }
    val fullGame = activity == SecretActivity.SNAKE || activity == SecretActivity.GOMOKU ||
        activity == SecretActivity.XIANGQI || activity == SecretActivity.WHEEL || activity == SecretActivity.BOARD
    SceneSystemBars(lightIcons = !fullGame)
    val scope = rememberCoroutineScope()
    var snake by rememberSaveable(stateSaver = snakeStateSaver) { mutableStateOf(SnakeEngine.newGame()) }
    var snakeRunning by remember { mutableStateOf(false) }
    var snakeStarted by rememberSaveable { mutableStateOf(false) }
    var gomoku by rememberSaveable(stateSaver = gomokuStateSaver) { mutableStateOf(GomokuEngine.newGame()) }
    var gomokuHistory by remember { mutableStateOf<List<GomokuState>>(emptyList()) }
    var gomokuRestoreToken by remember { mutableIntStateOf(0) }
    var gomokuPaused by remember { mutableStateOf(true) }
    var gomokuStarted by remember { mutableStateOf(false) }
    var gomokuHumanPlayer by rememberSaveable { mutableIntStateOf(1) }
    var gomokuColorAssigned by rememberSaveable{mutableStateOf(false)}
    var gomokuMode by rememberSaveable { mutableStateOf(GomokuPlayMode.CPU) }
    var gomokuRoomEntry by remember{mutableStateOf(false)}
    var xiangqi by rememberSaveable(stateSaver = xiangqiStateSaver) { mutableStateOf(XiangqiEngine.newGame()) }
    var xiangqiHistory by remember { mutableStateOf<List<XiangqiState>>(emptyList()) }
    var xiangqiRestoreToken by remember { mutableIntStateOf(0) }
    var thinkingSeconds by rememberSaveable { mutableIntStateOf(gamePreferences.getInt("xiangqi_thinking_seconds", 120)
        .coerceIn(XiangqiThinkingClock.MIN_SECONDS, XiangqiThinkingClock.MAX_SECONDS)) }
    var xiangqiClock by rememberSaveable(stateSaver = xiangqiClockSaver) { mutableStateOf(XiangqiThinkingClock.reset(xiangqi, thinkingSeconds)) }
    var xiangqiStarted by rememberSaveable { mutableStateOf(false) }
    var xiangqiHumanSide by rememberSaveable { mutableStateOf(XiangqiSide.RED) }
    var xiangqiColorAssigned by rememberSaveable{mutableStateOf(false)}
    var clockSetupVisible by rememberSaveable { mutableStateOf(false) }
    var resumeAfterClockSetup by remember { mutableStateOf(false) }
    var clockEpoch by remember { mutableIntStateOf(0) }
    var clockTickAt by remember { mutableLongStateOf(0L) }
    var xiangqiPaused by remember { mutableStateOf(true) }
    var xiangqiMode by rememberSaveable { mutableStateOf(XiangqiPlayMode.CPU) }
    var xiangqiRoomEntry by remember{mutableStateOf(false)}
    val lanSession = remember { XiangqiLanSession() }
    val lan by lanSession.state.collectAsStateWithLifecycle()
    val onlineSession = remember(context) { XiangqiOnlineSession(context) }
    val online by onlineSession.state.collectAsStateWithLifecycle()
    val gomokuLanSession = remember { GomokuLanSession() }
    val gomokuLan by gomokuLanSession.state.collectAsStateWithLifecycle()
    val gomokuOnlineSession = remember(context) { GomokuOnlineSession(context) }
    val gomokuOnline by gomokuOnlineSession.state.collectAsStateWithLifecycle()
    val xiangqiDiscovery = remember(context, chessProfile.name) { NsdRoomDiscovery(context, NearbyGameKind.XIANGQI, chessProfile.name) }
    val gomokuDiscovery = remember(context, chessProfile.name) { NsdRoomDiscovery(context, NearbyGameKind.GOMOKU, chessProfile.name) }
    val nearbyXiangqi by xiangqiDiscovery.state.collectAsStateWithLifecycle()
    val nearbyGomoku by gomokuDiscovery.state.collectAsStateWithLifecycle()
    var starTaps by rememberSaveable { mutableIntStateOf(0) }
    var night by remember { mutableStateOf(gamePreferences.getBoolean("secret_night", false)) }
    val sleeping = night || petSleeping
    var secretBubble by remember { mutableStateOf<String?>(null) }
    var bubbleToken by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var helpJob by remember { mutableStateOf<Job?>(null) }
    var helpBusy by remember { mutableStateOf(false) }
    var helpGeneration by remember { mutableIntStateOf(0) }
    var assistedSelection by remember { mutableStateOf<GridCell?>(null) }
    var helpXiangqiPosition by remember { mutableStateOf<XiangqiState?>(null) }
    var helpGomokuPosition by remember { mutableStateOf<GomokuState?>(null) }
    var gameControlsBottom by remember { mutableFloatStateOf(0f) }
    val xiangqiSounds = remember { XiangqiSoundQueue() }
    var xiangqiSoundRevision by remember { mutableIntStateOf(0) }
    var xiangqiSoundFollowup by remember { mutableStateOf<Job?>(null) }

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
    fun cancelXiangqiSounds() {
        xiangqiSounds.invalidate()
        xiangqiSoundRevision++
        xiangqiSoundFollowup?.cancel(); xiangqiSoundFollowup = null
    }
    fun canPlayXiangqiSounds(): Boolean {
        if (!foreground || sleeping || activity != SecretActivity.XIANGQI || choosingOpponent ||
            !archiveReady || gameLoading || clockSetupVisible) return false
        return when (xiangqiMode) {
            XiangqiPlayMode.CPU, XiangqiPlayMode.HOTSEAT -> !xiangqiPaused
            XiangqiPlayMode.ONLINE -> onlineSession.state.value.let {
                it.connected && !it.roomEnded && it.pendingUndoRequest == null && it.pendingDrawRequest == null && it.rematchRequestedBy == null
            }
            XiangqiPlayMode.LAN -> lanSession.state.value.let {
                it.connected && !it.roomEnded && it.pendingUndoRequest == null && it.pendingDrawRequest == null && it.rematchRequestedBy == null
            }
        }
    }
    fun registerXiangqiSound(previous: XiangqiState, next: XiangqiState) {
        if (!canPlayXiangqiSounds()) return
        xiangqiSoundFollowup?.cancel(); xiangqiSoundFollowup = null
        xiangqiSounds.register(previous, next, SystemClock.uptimeMillis())
        xiangqiSoundRevision++
    }
    fun settleXiangqiSound(epoch: Int, position: XiangqiState) {
        if (!canPlayXiangqiSounds()) return
        val event = xiangqiSounds.consume(epoch, position, currentXiangqiPosition()) ?: return
        xiangqiSoundRevision++
        if (event.captured) UiSound.capture(context) else UiSound.woodMove(context)
        xiangqiSoundFollowup?.cancel(); xiangqiSoundFollowup = null
        val cue = if (position.outcome != XiangqiOutcome.PLAYING) {
            val human = when (xiangqiMode) {
                XiangqiPlayMode.CPU -> xiangqiHumanSide
                XiangqiPlayMode.HOTSEAT -> null
                XiangqiPlayMode.ONLINE -> onlineSession.state.value.localSide
                XiangqiPlayMode.LAN -> lanSession.state.value.localSide
            }
            if (GameFinishPresenter.xiangqi(position, human)?.mood == FinishMood.LOSE) UiCue.LOSE else UiCue.WIN
        } else if (XiangqiEngine.isInCheck(position, position.turnSide)) UiCue.CHECK else null
        if (cue != null) xiangqiSoundFollowup = scope.launch {
            // Leave room for the finite impact before the check/result cue; every delay revalidates.
            delay(MOVE_SOUND_FOLLOWUP_MS)
            if (event.epoch == xiangqiSounds.epoch && canPlayXiangqiSounds() && currentXiangqiPosition() == position)
                UiSound.play(context, cue)
        }
    }
    fun skipXiangqiPresentation(epoch: Int, position: XiangqiState) {
        if (epoch != xiangqiSounds.epoch || currentXiangqiPosition() != position) return
        xiangqiSoundFollowup?.cancel(); xiangqiSoundFollowup = null
        if (!canPlayXiangqiSounds()) cancelXiangqiSounds()
        else {
            xiangqiSounds.restartLatest(epoch, position, currentXiangqiPosition(), SystemClock.uptimeMillis())
            xiangqiSoundRevision++
        }
    }
    LaunchedEffect(xiangqiSoundRevision, activity, foreground, sleeping, choosingOpponent, archiveReady,
        gameLoading, xiangqiPaused, xiangqiMode, clockSetupVisible, canPlayXiangqiSounds()) {
        if (!canPlayXiangqiSounds()) {
            if (xiangqiSounds.firstPending != null || xiangqiSoundFollowup?.isActive == true) cancelXiangqiSounds()
            return@LaunchedEffect
        }
        val event = xiangqiSounds.firstPending ?: return@LaunchedEffect
        delay((event.fallbackAtMillis - SystemClock.uptimeMillis()).coerceAtLeast(0))
        settleXiangqiSound(event.epoch, event.position)
    }

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
        archive.saveGomoku(LocalGomokuSave(mode, gomoku, gomokuHistory, gomokuStarted, gomokuPaused,humanPlayer=gomokuHumanPlayer,colorAssigned=gomokuColorAssigned))
    }
    fun checkpointXiangqi() {
        if (!archiveReady || gameLoading) return
        val mode = xiangqiLocalMode() ?: return
        archive.saveXiangqi(LocalXiangqiSave(mode, xiangqi, xiangqiHistory, xiangqiClock.forPosition(xiangqi),
            thinkingSeconds, xiangqiStarted, xiangqiPaused,humanSide=xiangqiHumanSide,colorAssigned=xiangqiColorAssigned))
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
        gomokuRestoreToken++
        gomoku = save?.game ?: GomokuEngine.newGame()
        gomokuHistory = save?.undoHistory ?: emptyList()
        gomokuStarted = save?.started ?: false
        gomokuHumanPlayer=save?.humanPlayer?:1
        gomokuColorAssigned=save?.colorAssigned?:false
        gomokuPaused = true
    }
    fun restoreXiangqi(save: LocalXiangqiSave?) {
        cancelXiangqiSounds()
        xiangqiRestoreToken++
        xiangqi = save?.game ?: XiangqiEngine.newGame()
        xiangqiHistory = save?.undoHistory ?: emptyList()
        thinkingSeconds = save?.thinkingSeconds ?: gamePreferences.getInt("xiangqi_thinking_seconds", 120)
            .coerceIn(XiangqiThinkingClock.MIN_SECONDS, XiangqiThinkingClock.MAX_SECONDS)
        xiangqiClock = save?.clock ?: XiangqiThinkingClock.reset(xiangqi, thinkingSeconds)
        xiangqiStarted = save?.started ?: false
        xiangqiHumanSide=save?.humanSide?:XiangqiSide.RED
        xiangqiColorAssigned=save?.colorAssigned?:false
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
        if (foreground && activity == SecretActivity.GOMOKU) UiSound.stoneMove(context)
    }
    fun commitXiangqi(next: XiangqiState) {
        if (next == xiangqi) return
        val previous = xiangqi
        xiangqiHistory = (xiangqiHistory + xiangqi).takeLast(512)
        xiangqi = next
        clockTickAt = 0L; clockEpoch++; xiangqiClock = xiangqiClock.forPosition(next)
        checkpointXiangqi()
        registerXiangqiSound(previous, next)
    }
    fun eligibleGomokuTurn(): Boolean {
        if (!archiveReady || gameLoading || choosingOpponent || !foreground || sleeping || activity != SecretActivity.GOMOKU) return false
        val position = currentGomokuPosition()
        if (position.outcome != GomokuOutcome.PLAYING) return false
        return when (gomokuMode) {
            GomokuPlayMode.CPU -> !gomokuPaused && position.currentPlayer == gomokuHumanPlayer
            GomokuPlayMode.HOTSEAT -> !gomokuPaused
            GomokuPlayMode.NEARBY, GomokuPlayMode.ONLINE -> {
                val room = if (gomokuMode == GomokuPlayMode.ONLINE) gomokuOnlineSession.state.value else gomokuLanSession.state.value
                room.connected && !room.awaitingAck && !room.localBackground && !room.remoteBackground && !room.reconnecting &&
                room.pendingUndoRequest == null && room.pendingDrawRequest == null && position.currentPlayer == room.localPlayer
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
        if (!archiveReady || gameLoading || choosingOpponent || !foreground || sleeping || activity != SecretActivity.XIANGQI || clockSetupVisible) return false
        val position = currentXiangqiPosition()
        if (position.outcome != XiangqiOutcome.PLAYING) return false
        return when (xiangqiMode) {
            XiangqiPlayMode.CPU -> xiangqiStarted && !xiangqiPaused && position.turnSide == xiangqiHumanSide
            XiangqiPlayMode.HOTSEAT -> xiangqiStarted && !xiangqiPaused
            XiangqiPlayMode.ONLINE, XiangqiPlayMode.LAN -> {
                val room = if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.state.value else lanSession.state.value
                room.connected && !room.awaitingAck && !room.localBackground && !room.remoteBackground && !room.reconnecting &&
                    room.pendingUndoRequest == null && room.pendingDrawRequest == null && position.turnSide == room.localSide
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
                    val native = PikafishEngine.chooseMove(context.applicationContext, position, timeBudgetMillis = 3_000) {
                        !computeContext.isActive
                    }
                    if (native != null || !computeContext.isActive) native
                    else XiangqiStrongMoveHelper.chooseMove(position) { !computeContext.isActive }
                } ?: return@launch
                if (helpGeneration != generation || !eligibleXiangqiTurn() || currentXiangqiPosition() != position) return@launch
                assistedSelection = move.from
                // Follow an ordinary selection and move; the hidden action has no special cue.
                UiSound.pieceSelect(context)
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
                    val searchStartedAt = SystemClock.elapsedRealtime()
                    val native = RapfiEngine.chooseMove(context.applicationContext, position, timeBudgetMillis = 5_000) {
                        !computeContext.isActive
                    }
                    if (native != null || !computeContext.isActive) native
                    else GomokuStrongMoveHelper.chooseMove(position,
                        timeBudgetMillis = (5_000 - (SystemClock.elapsedRealtime() - searchStartedAt)).coerceIn(75, 5_000)) {
                        !computeContext.isActive
                    }
                } ?: return@launch
                delay(500)
                if (helpGeneration == generation && eligibleGomokuTurn() && currentGomokuPosition() == position) {
                    playGomokuMove(move)
                }
            } finally {
                if (helpGeneration == generation) { helpBusy = false; helpGomokuPosition = null; helpJob = null }
            }
        }
    }

    fun pauseLocalToys() {
        cancelXiangqiSounds()
        cancelHelp()
        freezeThinkingClock()
        snakeRunning = false
        gomokuPaused = true
        xiangqiPaused = true
        checkpointGomoku(); checkpointXiangqi(); checkpointSnake()
    }
    fun closeNetworkRooms() {
        cancelXiangqiSounds()
        lanSession.close()
        onlineSession.close()
        gomokuLanSession.close(); gomokuOnlineSession.close()
        lanSession.allowNearbyMatching(null);gomokuLanSession.allowNearbyMatching(null)
        xiangqiDiscovery.stop(); gomokuDiscovery.stop()
    }
    fun changeGomokuMode(value: GomokuPlayMode) {
        if (!archiveReady) return
        pauseLocalToys(); cancelModeLoad(); closeNetworkRooms()
        if (value == GomokuPlayMode.NEARBY || value == GomokuPlayMode.ONLINE) {
            gomokuMode = value
            gomokuRoomEntry=value==GomokuPlayMode.ONLINE
            if(value==GomokuPlayMode.NEARBY&&foreground) gomokuLanSession.host(playerName=chessProfile.name,avatarId=chessProfile.avatarId)
            return
        }
        val localMode = if (value == GomokuPlayMode.CPU) LocalGameMode.CPU else LocalGameMode.HOTSEAT
        gomokuRoomEntry=false
        val token = ++modeLoadGeneration
        gameLoading = true
        modeLoadJob = scope.launch {
            try {
                val saved = archive.loadGomoku(localMode)
                if (token != modeLoadGeneration) return@launch
                restoreGomoku(saved); gomokuMode = value
                if(!gomokuColorAssigned){gomokuHumanPlayer=Random.nextInt(1,3);gomokuColorAssigned=true}
                if (saved == null || !saved.started) { gomokuStarted = true; gomokuPaused = !foreground }
                gameLoading = false; checkpointGomoku()
            } finally { if (token == modeLoadGeneration) { gameLoading = false; modeLoadJob = null } }
        }
    }
    fun changeXiangqiMode(value: XiangqiPlayMode) {
        if (!archiveReady) return
        pauseLocalToys(); cancelModeLoad(); closeNetworkRooms()
        clockSetupVisible = false; resumeAfterClockSetup = false
        if (value == XiangqiPlayMode.LAN || value == XiangqiPlayMode.ONLINE) {
            xiangqiMode = value
            xiangqiRoomEntry=value==XiangqiPlayMode.ONLINE
            if(value==XiangqiPlayMode.LAN&&foreground) lanSession.host(playerName=chessProfile.name,avatarId=chessProfile.avatarId)
            return
        }
        val localMode = if (value == XiangqiPlayMode.CPU) LocalGameMode.CPU else LocalGameMode.HOTSEAT
        xiangqiRoomEntry=false
        val token = ++modeLoadGeneration
        gameLoading = true
        modeLoadJob = scope.launch {
            try {
                val saved = archive.loadXiangqi(localMode)
                if (token != modeLoadGeneration) return@launch
                restoreXiangqi(saved); xiangqiMode = value
                if(!xiangqiColorAssigned){xiangqiHumanSide=if(Random.nextBoolean())XiangqiSide.RED else XiangqiSide.BLACK;xiangqiColorAssigned=true}
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
    fun closeToy() { pauseToys(); cancelModeLoad(); leaveRoomAction = null; paperFromWheel = false
        activity = null; choosingOpponent=false; clockSetupVisible = false; resumeAfterClockSetup = false }
    fun returnChessHub() {
        pauseToys(); cancelModeLoad(); leaveRoomAction = null; choosingOpponent = false
        clockSetupVisible = false; resumeAfterClockSetup = false; paperFromWheel = false
        activity = SecretActivity.BOARD; gameControlsBottom = 0f
    }
    fun backFromToy() {
        if (activity == SecretActivity.GOMOKU || activity == SecretActivity.XIANGQI) returnChessHub() else closeToy()
    }
    fun openToy(value: SecretActivity) {
        if (!archiveReady || gameLoading) return
        paperFromWheel = false
        if(value==SecretActivity.PAPER) UiSound.paper(context) else UiSound.navigate(context)
        pauseToys()
        gameControlsBottom = 0f
        if (!sleeping && foreground) {
            activity = value
            choosingOpponent = value==SecretActivity.GOMOKU || value==SecretActivity.XIANGQI
            clockSetupVisible=false; resumeAfterClockSetup=false
        }
    }
    fun rest(value: Boolean) {
        closeToy()
        night = value
        gamePreferences.edit().putBoolean("secret_night", value).apply()
        scope.launch { withContext(NonCancellable) { prefs.setSecretPetSleeping(value) } }
    }

    fun undoXiangqi(requester: XiangqiSide = xiangqiHumanSide) {
        cancelHelp()
        val target = LocalChessUndo.xiangqiTarget(xiangqiHistory, xiangqiMode == XiangqiPlayMode.CPU, requester)
        if (target < 0) return
        cancelXiangqiSounds()
        xiangqi = xiangqiHistory[target]
        xiangqiHistory = xiangqiHistory.take(target)
        xiangqiClock = XiangqiThinkingClock.reset(xiangqi, thinkingSeconds)
        clockTickAt = 0L; clockEpoch++; checkpointXiangqi()
        if (foreground && activity == SecretActivity.XIANGQI) UiSound.undo(context)
    }
    fun undoGomoku(requester: Int = gomokuHumanPlayer) {
        cancelHelp()
        val target = LocalChessUndo.gomokuTarget(gomokuHistory, requester)
        if (target >= 0) {
            gomoku = gomokuHistory[target]; gomokuHistory = gomokuHistory.take(target); checkpointGomoku()
            if (foreground && activity == SecretActivity.GOMOKU) UiSound.undo(context)
        }
    }

    DisposableEffect(xiangqiDiscovery, gomokuDiscovery) {
        onDispose { xiangqiDiscovery.stop(); gomokuDiscovery.stop() }
    }
    LaunchedEffect(activity, foreground, sleeping, archiveReady, gameLoading, choosingOpponent, xiangqiMode, xiangqiDiscovery, lan.connected, lan.hostAddress, lan.sessionActive, lan.error,lan.roomEnded,lan.awaitingMatch) {
        if (archiveReady && !gameLoading && !choosingOpponent && activity == SecretActivity.XIANGQI && foreground && !sleeping && xiangqiMode == XiangqiPlayMode.LAN && !lan.connected) {
            if (!lanSession.state.value.sessionActive && lanSession.state.value.error == null && !lanSession.state.value.roomEnded) lanSession.host(playerName=chessProfile.name,avatarId=chessProfile.avatarId)
            val room = lanSession.state.value
            if (room.sessionActive && room.isHost && !room.awaitingMatch && !room.roomEnded && room.hostAddress.isNotBlank() && room.error == null) {
                xiangqiDiscovery.start();lanSession.allowNearbyMatching(xiangqiDiscovery.localId)
            }
            else xiangqiDiscovery.stop()
        } else {xiangqiDiscovery.stop();lanSession.allowNearbyMatching(null)}
    }
    LaunchedEffect(activity, foreground, sleeping, archiveReady, gameLoading, choosingOpponent, gomokuMode, gomokuDiscovery, gomokuLan.connected, gomokuLan.hostAddress, gomokuLan.sessionActive, gomokuLan.error,gomokuLan.roomEnded,gomokuLan.awaitingMatch) {
        if (archiveReady && !gameLoading && !choosingOpponent && activity == SecretActivity.GOMOKU && foreground && !sleeping && gomokuMode == GomokuPlayMode.NEARBY && !gomokuLan.connected) {
            if (!gomokuLanSession.state.value.sessionActive && gomokuLanSession.state.value.error == null && !gomokuLanSession.state.value.roomEnded) gomokuLanSession.host(playerName=chessProfile.name,avatarId=chessProfile.avatarId)
            val room = gomokuLanSession.state.value
            if (room.sessionActive && room.isHost && !room.awaitingMatch && !room.roomEnded && room.hostAddress.isNotBlank() && room.error == null) {
                gomokuDiscovery.start();gomokuLanSession.allowNearbyMatching(gomokuDiscovery.localId)
            }
            else gomokuDiscovery.stop()
        } else {gomokuDiscovery.stop();gomokuLanSession.allowNearbyMatching(null)}
    }
    val triedNearbyPeers=remember{mutableSetOf<String>()}
    LaunchedEffect(nearbyXiangqi.autoCandidate?.id,nearbyXiangqi.localId,activity,foreground,choosingOpponent,xiangqiMode,lan.connected,lan.awaitingMatch) {
        val peer=nearbyXiangqi.autoCandidate;val own=nearbyXiangqi.localId
        if(peer!=null&&own.isNotBlank()&&foreground&&!sleeping&&!choosingOpponent&&activity==SecretActivity.XIANGQI&&
            xiangqiMode==XiangqiPlayMode.LAN&&!lan.connected&&!lan.awaitingMatch&&!lan.roomEnded&&triedNearbyPeers.add("xq:$own:${peer.id}")) {
            lanSession.allowNearbyMatching(own);xiangqiDiscovery.stop();lanSession.joinNearby(peer,own,chessProfile.name,chessProfile.avatarId)
        }
    }
    LaunchedEffect(nearbyGomoku.autoCandidate?.id,nearbyGomoku.localId,activity,foreground,choosingOpponent,gomokuMode,gomokuLan.connected,gomokuLan.awaitingMatch) {
        val peer=nearbyGomoku.autoCandidate;val own=nearbyGomoku.localId
        if(peer!=null&&own.isNotBlank()&&foreground&&!sleeping&&!choosingOpponent&&activity==SecretActivity.GOMOKU&&
            gomokuMode==GomokuPlayMode.NEARBY&&!gomokuLan.connected&&!gomokuLan.awaitingMatch&&!gomokuLan.roomEnded&&triedNearbyPeers.add("go:$own:${peer.id}")) {
            gomokuLanSession.allowNearbyMatching(own);gomokuDiscovery.stop();gomokuLanSession.joinNearby(peer,own,chessProfile.name,chessProfile.avatarId)
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                resumeAfterClockSetup = false
                pauseLocalToys()
                archive.flushBlocking(350)
                foreground = false
                xiangqiDiscovery.stop(); gomokuDiscovery.stop()
                lanSession.allowNearbyMatching(null);gomokuLanSession.allowNearbyMatching(null)
                // An ordinary app switch is a pause, not a request to destroy the room.
                // Presence is sent before Android can suspend the socket/WebView timers.
                lanSession.setForeground(false); onlineSession.setForeground(false)
                gomokuLanSession.setForeground(false); gomokuOnlineSession.setForeground(false)
            }
            if (event == Lifecycle.Event.ON_START) {
                foreground = true
                lanSession.setForeground(true); onlineSession.setForeground(true)
                gomokuLanSession.setForeground(true); gomokuOnlineSession.setForeground(true)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); pauseLocalToys(); cancelModeLoad(); closeNetworkRooms() }
    }
    LaunchedEffect(sleeping) { if (sleeping) closeToy() }
    val visibleNetwork = if (xiangqiMode == XiangqiPlayMode.ONLINE) online else lan
    var lastNetworkGame by remember(activity, xiangqiMode, foreground, xiangqiSounds.epoch) { mutableStateOf(visibleNetwork.game) }
    var lastNetworkRound by remember(activity, xiangqiMode, foreground) { mutableIntStateOf(visibleNetwork.round) }
    LaunchedEffect(visibleNetwork.revision, visibleNetwork.round, activity, xiangqiMode, foreground) {
        val next = visibleNetwork.game
        val sameRound = visibleNetwork.round == lastNetworkRound
        if (!sameRound) cancelXiangqiSounds()
        if (foreground && !sleeping && activity == SecretActivity.XIANGQI && sameRound &&
            (xiangqiMode == XiangqiPlayMode.ONLINE || xiangqiMode == XiangqiPlayMode.LAN) &&
            visibleNetwork.connected && !visibleNetwork.roomEnded && currentXiangqiPosition() == next) {
            if (next.ply == lastNetworkGame.ply + 1 && next.board != lastNetworkGame.board) {
                registerXiangqiSound(lastNetworkGame, next)
            } else if (next.ply < lastNetworkGame.ply) {
                cancelXiangqiSounds()
                UiSound.undo(context)
            } else if (next != lastNetworkGame) cancelXiangqiSounds()
        }
        lastNetworkGame = next
        lastNetworkRound = visibleNetwork.round
    }
    val visibleGomokuRoom = if (gomokuMode == GomokuPlayMode.ONLINE) gomokuOnline else gomokuLan
    // Agreed draws have no landing animation. Only a fresh foreground agreement speaks.
    var seenAgreedChessDraw by remember(activity, xiangqiMode, foreground, visibleNetwork.round) {
        mutableStateOf(visibleNetwork.agreedDraw)
    }
    LaunchedEffect(visibleNetwork.agreedDraw, activity, xiangqiMode, foreground, visibleNetwork.round) {
        val fresh = !seenAgreedChessDraw && visibleNetwork.agreedDraw
        seenAgreedChessDraw = visibleNetwork.agreedDraw
        if (fresh && foreground && !sleeping && activity == SecretActivity.XIANGQI &&
            (xiangqiMode == XiangqiPlayMode.ONLINE || xiangqiMode == XiangqiPlayMode.LAN)) {
            UiSound.draw(context)
        }
    }
    var lastNetworkGomoku by remember(activity, gomokuMode, foreground, visibleGomokuRoom.round) { mutableStateOf(visibleGomokuRoom.game) }
    LaunchedEffect(visibleGomokuRoom.revision, visibleGomokuRoom.round, activity, gomokuMode, foreground) {
        val next = visibleGomokuRoom.game
        if (foreground && !sleeping && activity == SecretActivity.GOMOKU &&
            (gomokuMode == GomokuPlayMode.ONLINE || gomokuMode == GomokuPlayMode.NEARBY) &&
            visibleGomokuRoom.connected && !visibleGomokuRoom.roomEnded && currentGomokuPosition() == next) {
            val count = next.board.count { it != 0 }
            val previousCount = lastNetworkGomoku.board.count { it != 0 }
            if (count == previousCount + 1 && next.board != lastNetworkGomoku.board) UiSound.stoneMove(context)
            else if (count < previousCount) UiSound.undo(context)
        }
        lastNetworkGomoku = next
    }
    val liveGo=currentGomokuPosition()
    var seenGoOutcome by remember(activity,gomokuMode,gomokuRestoreToken,foreground,visibleGomokuRoom.round){mutableStateOf(liveGo.outcome)}
    LaunchedEffect(liveGo.outcome,activity,gomokuMode,gomokuRestoreToken,foreground,visibleGomokuRoom.round) {
        val fresh=seenGoOutcome==GomokuOutcome.PLAYING&&liveGo.outcome!=GomokuOutcome.PLAYING
        seenGoOutcome=liveGo.outcome
        if(fresh&&foreground&&activity==SecretActivity.GOMOKU&&!choosingOpponent&&archiveReady&&!gameLoading) {
            val snapshot=liveGo
            val human=when(gomokuMode){GomokuPlayMode.CPU->gomokuHumanPlayer;GomokuPlayMode.HOTSEAT->null;else->visibleGomokuRoom.localPlayer}
            val mood=GameFinishPresenter.gomoku(snapshot,human)?.mood
            delay(260)
            if(foreground&&currentGomokuPosition()==snapshot) when(mood){FinishMood.LOSE->UiSound.lose(context)
                FinishMood.DRAW->UiSound.draw(context);FinishMood.WIN,FinishMood.SHARED->UiSound.win(context);null->Unit}
        }
    }
    LaunchedEffect(bubbleToken) { if (secretBubble != null) { delay(3000); secretBubble = null } }
    LaunchedEffect(gomoku, xiangqi, online.game, lan.game, gomokuOnline.game, gomokuLan.game,
        online.connected, lan.connected, online.awaitingAck, lan.awaitingAck, online.pendingUndoRequest, lan.pendingUndoRequest,
        gomokuOnline.pendingUndoRequest, gomokuLan.pendingUndoRequest, online.pendingDrawRequest, lan.pendingDrawRequest,
        gomokuOnline.pendingDrawRequest, gomokuLan.pendingDrawRequest) {
        if (helpBusy && ((helpGomokuPosition != null && (helpGomokuPosition != currentGomokuPosition() || !eligibleGomokuTurn())) ||
            (helpXiangqiPosition != null && (helpXiangqiPosition != currentXiangqiPosition() || !eligibleXiangqiTurn())))) cancelHelp()
    }
    LaunchedEffect(activity, foreground, sleeping, archiveReady, gameLoading, choosingOpponent, xiangqiPaused, xiangqiMode, xiangqiHumanSide, xiangqi.turnSide, xiangqi.outcome) {
        if (archiveReady && !gameLoading && !choosingOpponent && activity == SecretActivity.XIANGQI && foreground && !sleeping && !xiangqiPaused &&
            xiangqiMode == XiangqiPlayMode.CPU && xiangqi.turnSide != xiangqiHumanSide && xiangqi.outcome == XiangqiOutcome.PLAYING) {
            val position = xiangqi
            delay(420)
            val move = withContext(Dispatchers.Default) { val computeContext=currentCoroutineContext()
                XiangqiEngine.chooseCpuMove(position){!computeContext.isActive} }
            // Do not let a fast reply cancel the just-landed move's check sound.
            xiangqiSoundFollowup?.join()
            if (archiveReady && !gameLoading && !choosingOpponent && activity == SecretActivity.XIANGQI && foreground && !sleeping && !xiangqiPaused &&
                xiangqiMode == XiangqiPlayMode.CPU && position.turnSide!=xiangqiHumanSide && xiangqi == position && move != null) commitXiangqi(XiangqiEngine.play(position, move))
        }
    }
    LaunchedEffect(activity, foreground, sleeping, archiveReady, gameLoading, choosingOpponent, xiangqiPaused, xiangqiMode, xiangqi.ply,
        xiangqi.turnSide, xiangqi.outcome, clockEpoch) {
        xiangqiClock = xiangqiClock.forPosition(xiangqi)
        val localMode = xiangqiMode == XiangqiPlayMode.CPU || xiangqiMode == XiangqiPlayMode.HOTSEAT
        if (archiveReady && !gameLoading && !choosingOpponent && activity == SecretActivity.XIANGQI && foreground && !sleeping && !xiangqiPaused &&
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
    LaunchedEffect(activity, foreground, sleeping, archiveReady, gameLoading, choosingOpponent, gomokuMode, gomokuPaused, gomokuHumanPlayer, gomoku.currentPlayer, gomoku.outcome, gomoku.size) {
        if (archiveReady && !gameLoading && !choosingOpponent && activity == SecretActivity.GOMOKU && foreground && !sleeping && !gomokuPaused &&
            gomokuMode == GomokuPlayMode.CPU &&
            gomoku.currentPlayer != gomokuHumanPlayer && gomoku.outcome == GomokuOutcome.PLAYING) {
            val position = gomoku
            delay(420)
            val move = withContext(Dispatchers.Default) {
                val computeContext = currentCoroutineContext()
                val searchStartedAt = SystemClock.elapsedRealtime()
                val native = RapfiEngine.chooseMove(context.applicationContext, position, timeBudgetMillis = 1_200) {
                    !computeContext.isActive
                }
                if (native != null || !computeContext.isActive) native
                else GomokuStrongMoveHelper.chooseMove(position,
                    timeBudgetMillis = (1_200 - (SystemClock.elapsedRealtime() - searchStartedAt)).coerceIn(75, 1_200)) {
                    !computeContext.isActive
                }
            }
            if (archiveReady && !gameLoading && !choosingOpponent && activity == SecretActivity.GOMOKU && foreground && !sleeping && !gomokuPaused && gomokuMode == GomokuPlayMode.CPU && position.currentPlayer!=gomokuHumanPlayer && gomoku == position) {
                move?.let { commitGomoku(GomokuEngine.play(position, it.x, it.y)) }
            }
        }
    }
    LaunchedEffect(roomInvite, archiveReady, foreground, sleeping) {
        val invite = roomInvite ?: return@LaunchedEffect
        if (!archiveReady || !foreground) return@LaunchedEffect
        val currentGame = when {
            activity == SecretActivity.XIANGQI && xiangqiMode == XiangqiPlayMode.ONLINE -> "xiangqi"
            activity == SecretActivity.GOMOKU && gomokuMode == GomokuPlayMode.ONLINE -> "gomoku"
            else -> null
        }
        val currentCode = if (currentGame == "xiangqi") online.hostAddress else gomokuOnline.hostAddress
        val currentActive = if (currentGame == "xiangqi") online.sessionActive && !online.roomEnded && !online.peerLeft
            else currentGame == "gomoku" && gomokuOnline.sessionActive && !gomokuOnline.roomEnded && !gomokuOnline.peerLeft
        if (invite.matchesLiveRoom(currentGame, currentCode, currentActive)) {
            onRoomInviteConsumed()
            return@LaunchedEffect
        }
        if (sleeping) {
            night = false; gamePreferences.edit().putBoolean("secret_night", false).apply()
            prefs.setSecretPetSleeping(false)
            return@LaunchedEffect
        }
        val join = {
            paperFromWheel = false
            hubXiangqi = invite.game == "xiangqi"
            choosingOpponent = false
            activity = if (hubXiangqi) SecretActivity.XIANGQI else SecretActivity.GOMOKU
            if (hubXiangqi) {
                changeXiangqiMode(XiangqiPlayMode.ONLINE)
                onlineSession.join(invite.code, playerName = chessProfile.name, avatarId = chessProfile.avatarId)
                if (onlineSession.state.value.sessionActive) xiangqiRoomEntry = false
            } else {
                changeGomokuMode(GomokuPlayMode.ONLINE)
                gomokuOnlineSession.join(invite.code, playerName = chessProfile.name, avatarId = chessProfile.avatarId)
                if (gomokuOnlineSession.state.value.sessionActive) gomokuRoomEntry = false
            }
        }
        leaveNetworkSafely(join)
        onRoomInviteConsumed()
    }
    if (editChessProfile) ChessProfileEditor(chessProfile, { editChessProfile = false }) { profile ->
        chessName = profile.name; chessAvatar = profile.avatarId; editChessProfile = false
        gamePreferences.edit().putString("chess_nickname", profile.name).putString("chess_avatar", profile.avatarId).apply()
    }
    BackHandler { if (activity != null) leaveNetworkSafely(::backFromToy) else { pauseToys(); onBack() } }

    Box(Modifier.fillMaxSize().background(Color(0xFFE8D1B0))) {
        if (!fullGame) SecretRoomStage(sleeping = sleeping, toysEnabled = archiveReady && !gameLoading, onPet = { UiSound.pet(context);rest(!sleeping) },
            onWheel = { openToy(SecretActivity.WHEEL) }, onSnake = { openToy(SecretActivity.SNAKE) },
            onGomoku = { openToy(SecretActivity.BOARD) }, onPaper = { openToy(SecretActivity.PAPER) },
            night = sleeping, sparkleToken = starTaps, bubble = secretBubble,
            onStar = { UiSound.select(context); starTaps++; secretBubble = if (starTaps % 3 == 0) "找到暗号啦：今天可以慢慢来 ♡" else "这颗星星把一点好运藏进你口袋里了 ✦"; bubbleToken++ },
            onLamp = { UiSound.lamp(context);rest(!sleeping) },
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
            SecretActivity.BOARD -> "棋友会"
            SecretActivity.GOMOKU -> "五子棋"
            SecretActivity.XIANGQI -> "象棋"
            SecretActivity.PAPER -> "阿噜的秘密纸条"
        }
        val toyContent: @Composable ColumnScope.(androidx.compose.ui.unit.Dp) -> Unit = { boardSize ->
            if(toy == SecretActivity.BOARD || choosingOpponent&&(toy==SecretActivity.GOMOKU||toy==SecretActivity.XIANGQI)) {
                ChessOpponentChoicePage(hubXiangqi, chessProfile, onGame = { hubXiangqi = it },
                    onEditProfile = { editChessProfile = true }) { opponent ->
                    activity = if (hubXiangqi) SecretActivity.XIANGQI else SecretActivity.GOMOKU
                    choosingOpponent=false
                    if(!hubXiangqi) changeGomokuMode(when(opponent){ChessOpponent.ARU->GomokuPlayMode.CPU
                        ChessOpponent.NEARBY->GomokuPlayMode.NEARBY;ChessOpponent.ROOM->GomokuPlayMode.ONLINE;ChessOpponent.SAME_PHONE->GomokuPlayMode.HOTSEAT})
                    else changeXiangqiMode(when(opponent){ChessOpponent.ARU->XiangqiPlayMode.CPU;ChessOpponent.NEARBY->XiangqiPlayMode.LAN
                        ChessOpponent.ROOM->XiangqiPlayMode.ONLINE;ChessOpponent.SAME_PHONE->XiangqiPlayMode.HOTSEAT})
                }
            } else
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
                        GomokuPlayMode.ONLINE -> if(gomokuOnline.roomEnded){returnChessHub()}else gomokuOnlineSession.requestRematch()
                        GomokuPlayMode.NEARBY -> if(gomokuLan.roomEnded){returnChessHub()}else gomokuLanSession.requestRematch()
                        else -> { gomokuHumanPlayer=3-gomokuHumanPlayer;gomokuColorAssigned=true;gomokuRestoreToken++;gomokuHistory = emptyList(); gomoku = GomokuEngine.newGame(); gomokuStarted = true; gomokuPaused = false; checkpointGomoku() }
                    } },
                    mode = gomokuMode, onMode = { value -> leaveNetworkSafely { changeGomokuMode(value) } }, room = visibleGomokuRoom, nearby = nearbyGomoku,
                    humanPlayer=gomokuHumanPlayer,restorationToken=gomokuRestoreToken,onExit={leaveNetworkSafely(::returnChessHub)},
                    showRoomEntry=gomokuRoomEntry, playerProfile = chessProfile,
                    onHost = { code -> if (foreground) {gomokuOnlineSession.host(code=code,playerName=chessProfile.name,avatarId=chessProfile.avatarId);
                        if(gomokuOnlineSession.state.value.sessionActive)gomokuRoomEntry=false} },
                    onJoin = { address -> if (foreground) {
                        if (gomokuMode == GomokuPlayMode.ONLINE) {gomokuOnlineSession.join(address,playerName=chessProfile.name,avatarId=chessProfile.avatarId);
                            if(gomokuOnlineSession.state.value.sessionActive)gomokuRoomEntry=false}
                        else { val own=nearbyGomoku.localId;val peer=nearbyGomoku.rooms.firstOrNull{it.address==address}
                            if(own.isNotBlank()&&peer!=null){gomokuLanSession.allowNearbyMatching(own);gomokuDiscovery.stop();gomokuLanSession.joinNearby(peer,own,chessProfile.name,chessProfile.avatarId)} }
                    } },
                    onDisconnect = { leaveNetworkSafely(::returnChessHub) },
                    onNearbyRetry = { if (foreground) { gomokuDiscovery.stop();gomokuLanSession.allowNearbyMatching(null);gomokuLanSession.host(playerName=chessProfile.name,avatarId=chessProfile.avatarId) } },
                    onMatchResponse={accept->if(gomokuMode==GomokuPlayMode.ONLINE)gomokuOnlineSession.respondToMatch(accept)else gomokuLanSession.respondToMatch(accept)},
                    onRematchResponse={accept->if(gomokuMode==GomokuPlayMode.ONLINE)gomokuOnlineSession.respondToRematch(accept)else gomokuLanSession.respondToRematch(accept)},
                    canUndo = when (gomokuMode) {
                        GomokuPlayMode.ONLINE -> gomokuOnline.canUndo
                        GomokuPlayMode.NEARBY -> gomokuLan.canUndo
                        GomokuPlayMode.HOTSEAT -> (1..2).any { LocalChessUndo.gomokuTarget(gomokuHistory, it) >= 0 }
                        else -> LocalChessUndo.gomokuTarget(gomokuHistory,gomokuHumanPlayer) >= 0
                    },
                    onUndo = { cancelHelp(); when (gomokuMode) {
                        GomokuPlayMode.ONLINE -> gomokuOnlineSession.requestUndo()
                        GomokuPlayMode.NEARBY -> gomokuLanSession.requestUndo()
                        GomokuPlayMode.CPU -> undoGomoku()
                        GomokuPlayMode.HOTSEAT -> undoGomoku()
                    } },
                    onUndoResponse = { accept -> if (gomokuMode == GomokuPlayMode.ONLINE) gomokuOnlineSession.respondToUndo(accept)
                        else if (gomokuMode == GomokuPlayMode.NEARBY) gomokuLanSession.respondToUndo(accept) },
                    canUndoBlack = LocalChessUndo.gomokuTarget(gomokuHistory, 1) >= 0,
                    canUndoWhite = LocalChessUndo.gomokuTarget(gomokuHistory, 2) >= 0,
                    onHotseatUndo = { player -> if (gomokuMode == GomokuPlayMode.HOTSEAT) undoGomoku(player) },
                    onResign = { cancelHelp(); if (gomokuMode == GomokuPlayMode.ONLINE) gomokuOnlineSession.resign()
                        else if (gomokuMode == GomokuPlayMode.NEARBY) gomokuLanSession.resign() },
                    onDraw = { cancelHelp(); if (gomokuMode == GomokuPlayMode.ONLINE) gomokuOnlineSession.requestDraw()
                        else if (gomokuMode == GomokuPlayMode.NEARBY) gomokuLanSession.requestDraw() },
                    onDrawResponse = { accept -> if (gomokuMode == GomokuPlayMode.ONLINE) gomokuOnlineSession.respondToDraw(accept)
                        else if (gomokuMode == GomokuPlayMode.NEARBY) gomokuLanSession.respondToDraw(accept) },
                    onCancelDraw = { if (gomokuMode == GomokuPlayMode.ONLINE) gomokuOnlineSession.cancelDraw()
                        else if (gomokuMode == GomokuPlayMode.NEARBY) gomokuLanSession.cancelDraw() },
                    helpBusy = helpBusy || gameLoading || !archiveReady, onControlsBottom = { gameControlsBottom = it })
                SecretActivity.BOARD -> Unit
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
                    onToggle = { cancelXiangqiSounds(); cancelHelp(); freezeThinkingClock(); if (!xiangqiStarted) { xiangqiPaused = true; resumeAfterClockSetup = false; clockSetupVisible = true }
                        else xiangqiPaused = !xiangqiPaused
                        checkpointXiangqi() },
                    onRestart = { cancelXiangqiSounds(); cancelHelp(); if (xiangqiMode == XiangqiPlayMode.LAN) {if(lan.roomEnded){returnChessHub()}else lanSession.requestRematch()}
                        else if (xiangqiMode == XiangqiPlayMode.ONLINE) {if(online.roomEnded){returnChessHub()}else onlineSession.requestRematch()}
                        else { xiangqiHumanSide=xiangqiHumanSide.opponent;xiangqiColorAssigned=true;xiangqiRestoreToken++;xiangqiHistory = emptyList(); xiangqi = XiangqiEngine.newGame(); xiangqiClock = XiangqiThinkingClock.reset(xiangqi, thinkingSeconds)
                            clockTickAt = 0L; clockEpoch++; xiangqiStarted = false; xiangqiPaused = true; resumeAfterClockSetup = false; clockSetupVisible = true; checkpointXiangqi() } },
                    humanSide=xiangqiHumanSide,onExit={leaveNetworkSafely(::returnChessHub)},
                    showRoomEntry=xiangqiRoomEntry, playerProfile = chessProfile,
                    onHost = { code -> if (foreground) { if (xiangqiMode == XiangqiPlayMode.ONLINE) {onlineSession.host(code=code,playerName=chessProfile.name,avatarId=chessProfile.avatarId);
                        if(onlineSession.state.value.sessionActive)xiangqiRoomEntry=false} else lanSession.host(playerName=chessProfile.name,avatarId=chessProfile.avatarId) } },
                    onJoin = { address -> if (foreground) { if (xiangqiMode == XiangqiPlayMode.ONLINE) {onlineSession.join(address,playerName=chessProfile.name,avatarId=chessProfile.avatarId);
                        if(onlineSession.state.value.sessionActive)xiangqiRoomEntry=false}
                        else { val own=nearbyXiangqi.localId;val peer=nearbyXiangqi.rooms.firstOrNull{it.address==address}
                            if(own.isNotBlank()&&peer!=null){lanSession.allowNearbyMatching(own);xiangqiDiscovery.stop();lanSession.joinNearby(peer,own,chessProfile.name,chessProfile.avatarId)} } } },
                    nearby = nearbyXiangqi,
                    onNearbyRetry = { if (foreground) { xiangqiDiscovery.stop();lanSession.allowNearbyMatching(null);lanSession.host(playerName=chessProfile.name,avatarId=chessProfile.avatarId) } },
                    onMatchResponse={accept->if(xiangqiMode==XiangqiPlayMode.ONLINE)onlineSession.respondToMatch(accept)else lanSession.respondToMatch(accept)},
                    onRematchResponse={accept->if(xiangqiMode==XiangqiPlayMode.ONLINE)onlineSession.respondToRematch(accept)else lanSession.respondToRematch(accept)},
                    onDisconnect = { leaveNetworkSafely(::returnChessHub) },
                    onPuzzle = { position -> cancelXiangqiSounds();cancelHelp();xiangqiHumanSide=position.turnSide;xiangqiColorAssigned=true;xiangqiRestoreToken++; xiangqiHistory = emptyList(); xiangqi=position; xiangqiClock=XiangqiThinkingClock.reset(position, thinkingSeconds)
                        clockTickAt = 0L; clockEpoch++; xiangqiStarted=true; xiangqiPaused=false; checkpointXiangqi() },
                    helpBusy = helpBusy || gameLoading || !archiveReady, assistedSelection = assistedSelection,
                    restorationToken = xiangqiRestoreToken,
                    presentationEpoch = xiangqiSounds.epoch,
                    canUndo = when (xiangqiMode) {
                        XiangqiPlayMode.ONLINE -> online.canUndo
                        XiangqiPlayMode.LAN -> lan.canUndo
                        XiangqiPlayMode.HOTSEAT -> XiangqiSide.entries.any { LocalChessUndo.xiangqiTarget(xiangqiHistory, false, it) >= 0 }
                        else -> LocalChessUndo.xiangqiTarget(xiangqiHistory, xiangqiMode == XiangqiPlayMode.CPU,xiangqiHumanSide) >= 0
                    },
                    onUndo = { cancelXiangqiSounds(); cancelHelp(); when (xiangqiMode) {
                        XiangqiPlayMode.ONLINE -> onlineSession.requestUndo()
                        XiangqiPlayMode.LAN -> lanSession.requestUndo()
                        XiangqiPlayMode.CPU -> undoXiangqi()
                        XiangqiPlayMode.HOTSEAT -> { freezeThinkingClock(); undoXiangqi() }
                    } },
                    onUndoResponse = { accept -> if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.respondToUndo(accept)
                        else if (xiangqiMode == XiangqiPlayMode.LAN) lanSession.respondToUndo(accept) },
                    canUndoRed = LocalChessUndo.xiangqiTarget(xiangqiHistory, false, XiangqiSide.RED) >= 0,
                    canUndoBlack = LocalChessUndo.xiangqiTarget(xiangqiHistory, false, XiangqiSide.BLACK) >= 0,
                    onHotseatUndo = { side -> if (xiangqiMode == XiangqiPlayMode.HOTSEAT) {
                        cancelXiangqiSounds(); freezeThinkingClock(); undoXiangqi(side)
                    } },
                    onResign = { cancelXiangqiSounds(); cancelHelp(); if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.resign()
                        else if (xiangqiMode == XiangqiPlayMode.LAN) lanSession.resign() },
                    onDraw = { cancelXiangqiSounds(); cancelHelp(); if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.requestDraw()
                        else if (xiangqiMode == XiangqiPlayMode.LAN) lanSession.requestDraw() },
                    onDrawResponse = { accept -> cancelXiangqiSounds(); if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.respondToDraw(accept)
                        else if (xiangqiMode == XiangqiPlayMode.LAN) lanSession.respondToDraw(accept) },
                    onCancelDraw = { if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.cancelDraw()
                        else if (xiangqiMode == XiangqiPlayMode.LAN) lanSession.cancelDraw() },
                    onModalOpened = ::cancelHelp, onControlsBottom = { gameControlsBottom = it },
                    onMoveSettled = { epoch, position, _ -> settleXiangqiSound(epoch, position) },
                    onPresentationSkipped = ::skipXiangqiPresentation)
                SecretActivity.PAPER -> Unit // A fixed-size paper desk owns its own dialog below.
                SecretActivity.WHEEL -> FortuneWheelGame(boardSize, foreground,
                    onOpenFuture = onOpenNotes, onOpenMemories = onOpenMemories,
                    onOpenPaper = { UiSound.paper(context); paperFromWheel = true })
            }
        }
        val showXiangqiClock = toy == SecretActivity.XIANGQI && !choosingOpponent &&
            (xiangqiMode == XiangqiPlayMode.CPU || xiangqiMode == XiangqiPlayMode.HOTSEAT ||
                visibleNetwork.connected || visibleNetwork.reconnecting)
        if (fullGame) SecretGamePage(title, { leaveNetworkSafely(::backFromToy) },
            backLabel = if (toy == SecretActivity.XIANGQI || toy == SecretActivity.GOMOKU) "返回棋友会" else "返回秘密基地",
            boardAspect = if (toy == SecretActivity.XIANGQI) 1.13f else 1f,
            reservedHeight = when (toy) {
                SecretActivity.SNAKE -> 320
                SecretActivity.XIANGQI -> 410
                else -> 270
            },
            headerTrailing = {
                if (showXiangqiClock) {
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
            centeredHeader = showXiangqiClock,
            gameDecor = !choosingOpponent&&(toy == SecretActivity.XIANGQI || toy == SecretActivity.GOMOKU),
            decorEnabled = !helpBusy && when (toy) {
                SecretActivity.XIANGQI -> eligibleXiangqiTurn()
                SecretActivity.GOMOKU -> eligibleGomokuTurn()
                else -> false
            },
            decorResetKey = if (toy == SecretActivity.XIANGQI) helpGeneration to currentXiangqiPosition() else helpGeneration to currentGomokuPosition(),
            controlsBottom = gameControlsBottom,
            onDecorSecret = { if (toy == SecretActivity.XIANGQI) requestXiangqiHelp() else if (toy == SecretActivity.GOMOKU) requestGomokuHelp() },
            content = toyContent)
        else if (toy == SecretActivity.PAPER) SecretPapersDialog(secretNotes, ::closeToy,
            onOpenReplies = { closeToy(); onOpenNotes() })
        else SecretToyDialog(title, ::closeToy, reservedHeight = 265, content = toyContent)
        }
        // The paper is a child of the wheel, not a replacement destination. Keeping the
        // wheel composed preserves its angle, picked task and tab when the paper closes.
        if (!sleeping && activity == SecretActivity.WHEEL && paperFromWheel) {
            SecretPapersDialog(secretNotes, { paperFromWheel = false },
                onOpenReplies = { paperFromWheel = false; onOpenNotes() })
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
            SecretWoodDialog("离开这盘棋？", { leaveRoomAction = null },
                confirmLabel = "回棋友会", onConfirm = { leaveRoomAction = null; action() },
                dismissLabel = "继续下", compactWidth = 292.dp) { }
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
    backLabel: String = "返回秘密基地",
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
                IconButton(onClick = { UiSound.navigate(context); onBack() }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, backLabel, tint = Color(0xFF665762))
                }
                Text(title, style = MaterialTheme.typography.titleLarge, color = Color(0xFF514A55))
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
    val boardSize = minOf((configuration.screenWidthDp-72).dp,
        (configuration.screenHeightDp-reservedHeight).coerceAtLeast(130).dp,252.dp)
    SecretWoodDialog(title,onDismiss,compactWidth=292.dp) {Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,
        verticalArrangement=Arrangement.spacedBy(8.dp)){content(boardSize)}}
}

@Composable
internal fun SecretPrizeWheel(angle: Float, diameter: androidx.compose.ui.unit.Dp,
    enabled: Boolean, onSpin: () -> Unit) {
    val context = LocalContext.current
    val font = remember(context) { context.resources.getFont(R.font.zcool_kuaile) }
    val labelPaint = remember(font) { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        typeface=font; textAlign=android.graphics.Paint.Align.CENTER; color=android.graphics.Color.rgb(88,79,73)
    } }
    val colors = remember { listOf(Color(0xFFEAE2D0),Color(0xFFD8DFD5),Color(0xFFDDD7E2),
        Color(0xFFE7DAD5),Color(0xFFE5DFD1),Color(0xFFDAD8E2)) }
    val iconInk = Color(0xFF968C7D)
    fun iconPath(cx:Float,cy:Float,scale:Float,points:List<Offset>)=Path().apply {
        points.forEachIndexed { i,p -> if(i==0) moveTo(cx+p.x*scale,cy+p.y*scale) else lineTo(cx+p.x*scale,cy+p.y*scale) };close()
    }
    Box(Modifier.size(diameter).testTag("secret-prize-wheel")
        .semantics { contentDescription = if (enabled) "好运转盘，轻点转盘转一下" else "好运转盘正在旋转" }
        .sceneClickable(enabled = enabled, role = Role.Button, onClick = onSpin), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(10.dp).graphicsLayer { rotationZ=angle }) {
            val radius=size.minDimension*.47f
            val paperRadius=radius*.94f
            val seam=Color(0xFFAAA08E)
            val motifStroke=Stroke(.85.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round,
                join=androidx.compose.ui.graphics.StrokeJoin.Round)
            drawCircle(Color(0xFFE4D8C2),radius)
            drawCircle(Color(0xFFA99980),radius,style=Stroke(.9.dp.toPx()))
            val face=Path().apply {addOval(androidx.compose.ui.geometry.Rect(center-Offset(paperRadius,paperRadius),
                center+Offset(paperRadius,paperRadius))) }
            clipPath(face) {
                colors.forEachIndexed { i,color -> drawArc(color,-90f+i*60f,60f,true,
                    topLeft=center-Offset(paperRadius,paperRadius),size=Size(paperRadius*2,paperRadius*2)) }
                // Quiet pencil grain, kept at one scale across the flat disc.
                repeat(7) { row ->
                    val y=center.y+paperRadius*(row-3)*.26f
                    val grain=Path().apply {moveTo(center.x-paperRadius,y)
                        cubicTo(center.x-paperRadius*.36f,y-1.1.dp.toPx(),center.x+paperRadius*.3f,y+1.4.dp.toPx(),center.x+paperRadius,y)}
                    drawPath(grain,Color(0xFFB1A793).copy(alpha=.09f),style=Stroke(.5.dp.toPx()))
                }
            }
            drawCircle(seam.copy(alpha=.4f),paperRadius,style=Stroke(.6.dp.toPx()))
            repeat(wheelGroups.size) { i ->
                val edgeAngle=Math.toRadians((-90+i*60).toDouble())
                val theta=Math.toRadians((-60+i*60).toDouble())
                drawLine(seam.copy(alpha=.35f),center,center+Offset(kotlin.math.cos(edgeAngle).toFloat(),
                    kotlin.math.sin(edgeAngle).toFloat())*paperRadius,.65.dp.toPx())
                val motif=center+Offset(kotlin.math.cos(theta).toFloat(),kotlin.math.sin(theta).toFloat())*(paperRadius*.53f)
                val d=paperRadius*.135f
                val cream=Color(0xFFF6F1E6)
                when(i) {
                    0 -> {
                        val cloud=Path().apply {
                            moveTo(motif.x-d*.82f,motif.y+d*.28f)
                            cubicTo(motif.x-d*1.25f,motif.y-d*.10f,motif.x-d*.84f,motif.y-d*.72f,motif.x-d*.38f,motif.y-d*.40f)
                            cubicTo(motif.x-d*.24f,motif.y-d*1.12f,motif.x+d*.70f,motif.y-d*1.08f,motif.x+d*.70f,motif.y-d*.34f)
                            cubicTo(motif.x+d*1.25f,motif.y-d*.35f,motif.x+d*1.30f,motif.y+d*.46f,motif.x+d*.74f,motif.y+d*.48f)
                            lineTo(motif.x-d*.62f,motif.y+d*.48f);close()
                        }
                        drawPath(cloud,cream);drawPath(cloud,iconInk.copy(alpha=.72f),style=motifStroke)
                    }
                    1 -> {
                        val moon=Path().apply {
                            moveTo(motif.x+d*.28f,motif.y-d*.87f)
                            cubicTo(motif.x-d*.97f,motif.y-d*.78f,motif.x-d*1.10f,motif.y+d*.81f,motif.x+d*.06f,motif.y+d*.88f)
                            cubicTo(motif.x+d*.62f,motif.y+d*.95f,motif.x+d*.96f,motif.y+d*.44f,motif.x+d*.96f,motif.y+d*.09f)
                            cubicTo(motif.x+d*.13f,motif.y+d*.48f,motif.x-d*.21f,motif.y-d*.21f,motif.x+d*.28f,motif.y-d*.87f);close()
                        }
                        drawPath(moon,Color(0xFFEBDDB8));drawPath(moon,iconInk.copy(alpha=.66f),style=motifStroke)
                    }
                    2 -> {
                        repeat(6) { petal ->
                            val t=Math.toRadians((petal*60).toDouble())
                            val p=motif+Offset(kotlin.math.cos(t).toFloat(),kotlin.math.sin(t).toFloat())*(d*.60f)
                            drawCircle(cream,d*.44f,p);drawCircle(iconInk.copy(alpha=.45f),d*.44f,p,style=motifStroke)
                        }
                        drawCircle(Color(0xFFC5B589),d*.36f,motif)
                    }
                    3,5 -> {
                        val leaf=Path().apply {moveTo(motif.x-d*.65f,motif.y+d*.62f)
                            cubicTo(motif.x-d*1.02f,motif.y-d*.13f,motif.x-d*.13f,motif.y-d*.86f,motif.x+d*.72f,motif.y-d*.67f)
                            cubicTo(motif.x+d*1.0f,motif.y+d*.08f,motif.x+d*.13f,motif.y+d*.84f,motif.x-d*.65f,motif.y+d*.62f);close() }
                        drawPath(leaf,if(i==3) Color(0xFFBECABB) else Color(0xFFCBC5D3))
                        drawPath(leaf,iconInk.copy(alpha=.64f),style=motifStroke)
                        drawLine(iconInk.copy(alpha=.68f),motif+Offset(-d*.88f,d*.82f),motif+Offset(d*.42f,-d*.42f),.8.dp.toPx())
                    }
                    else -> {
                        val points=(0..9).map { n ->val t=Math.toRadians((-90+n*36).toDouble());val r=if(n%2==0) 1f else .47f
                            Offset(kotlin.math.cos(t).toFloat()*r,kotlin.math.sin(t).toFloat()*r) }
                        val star=iconPath(motif.x,motif.y,d,points)
                        drawPath(star,Color(0xFFEADDBB));drawPath(star,iconInk.copy(alpha=.7f),style=motifStroke)
                    }
                }
                val label=center+Offset(kotlin.math.cos(theta).toFloat(),kotlin.math.sin(theta).toFloat())*(paperRadius*.78f)
                val canvas=drawContext.canvas.nativeCanvas;val saved=canvas.save()
                canvas.rotate(i*60f+30f,label.x,label.y)
                labelPaint.textSize=size.width/25f
                canvas.drawText(wheelGroups[i].first,label.x,label.y+labelPaint.textSize*.32f,labelPaint)
                canvas.restoreToCount(saved)
            }
            drawCircle(Color(0xFFD7CBB6),radius*.10f)
            drawCircle(Color(0xFFAD9E84),radius*.10f,style=Stroke(.8.dp.toPx()))
            drawCircle(Color(0xFFAB9A7E),radius*.022f)
        }
        Canvas(Modifier.align(Alignment.TopCenter).padding(top=7.dp).size(22.dp,23.dp)) {
            val needle=Path().apply {moveTo(size.width*.27f,size.height*.10f);lineTo(size.width*.73f,size.height*.10f)
                lineTo(size.width*.50f,size.height*.90f);close()}
            drawPath(needle,Color(0xFF9B8C75))
        }
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
            Box(hotspot(x, y, w, h).sceneClickable(enabled = !sleeping && toysEnabled, role = Role.Button, onClickLabel = label, onClick = action)
                .semantics { contentDescription = label })
            Text(label, hotspot(tagX - tagW / 2, tagY - tagH / 2, tagW, tagH)
                .sceneClickable(enabled = !sleeping && toysEnabled, role = Role.Button, onClick = action)
                .wrapContentSize(Alignment.Center), fontFamily = GuluBrandFont,
                fontSize = 10.sp, color = if (night) Color(0xFFCFC1AD) else Color(0xFF66462C), maxLines = 1)
        }
        toy("好运转盘", .15f, .236f, .24f, .168f, .27f, .418f, .107f, .028f, onWheel)
        toy("贪吃蛇", .755f, .29f, .183f, .154f, .846f, .462f, .105f, .028f, onSnake)
        toy("小棋桌", .61f, .541f, .34f, .064f, .854f, .529f, .094f, .027f, onGomoku)
        Box(hotspot(.17f, .488f, .27f, .077f).sceneClickable(enabled = !sleeping && toysEnabled, role = Role.Button,
            onClickLabel = "秘密纸条", onClick = onPaper).semantics { contentDescription = "秘密纸条" })
        Box(hotspot(.30f, .634f, .41f, .172f).sceneCombinedClickable(role = Role.Button,
            onClickLabel = if (sleeping) "轻轻叫醒阿噜" else "让阿噜打个盹", onClick = onPet,
            onLongClickLabel = "听阿噜藏起来的小秘密", onLongClick = onPetSecret)
            .semantics { contentDescription = if (sleeping) "轻轻叫醒阿噜" else "让阿噜打个盹" })
        Box(hotspot(.65f, .10f, .18f, .09f).sceneClickable(role = Role.Button, onClickLabel = "摸摸挂着的小星星", onClick = onStar)
            .semantics { contentDescription = "摸摸挂着的小星星" })
        Box(hotspot(.685f, .224f, .10f, .078f).sceneClickable(role = Role.Button,
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
