package com.jiligulu.app.ui.littleworld

import androidx.activity.compose.BackHandler
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
    val sleeping by prefs.secretPetSleeping.collectAsStateWithLifecycle(false)
    var activity by rememberSaveable { mutableStateOf<SecretActivity?>(null) }
    val fullGame = activity == SecretActivity.SNAKE || activity == SecretActivity.GOMOKU || activity == SecretActivity.XIANGQI
    SceneSystemBars(lightIcons = !fullGame)
    var note by rememberSaveable { mutableIntStateOf(0) }
    var prize by rememberSaveable { mutableStateOf<String?>(null) }
    var spinning by remember { mutableStateOf(false) }
    val rotation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var spinJob by remember { mutableStateOf<Job?>(null) }
    var snake by rememberSaveable(stateSaver = snakeStateSaver) { mutableStateOf(SnakeEngine.newGame()) }
    var snakeRunning by remember { mutableStateOf(false) }
    var snakeStarted by rememberSaveable { mutableStateOf(false) }
    var gomoku by rememberSaveable(stateSaver = gomokuStateSaver) { mutableStateOf(GomokuEngine.newGame()) }
    var gomokuPaused by remember { mutableStateOf(true) }
    var xiangqi by rememberSaveable(stateSaver = xiangqiStateSaver) { mutableStateOf(XiangqiEngine.newGame()) }
    val gamePreferences = remember(context) { context.getSharedPreferences("gulu_secret_games", android.content.Context.MODE_PRIVATE) }
    var thinkingSeconds by rememberSaveable { mutableIntStateOf(gamePreferences.getInt("xiangqi_thinking_seconds", 120)
        .coerceIn(XiangqiThinkingClock.MIN_SECONDS, XiangqiThinkingClock.MAX_SECONDS)) }
    var xiangqiClock by rememberSaveable(stateSaver = xiangqiClockSaver) { mutableStateOf(XiangqiThinkingClock.reset(xiangqi, thinkingSeconds)) }
    var xiangqiStarted by rememberSaveable { mutableStateOf(false) }
    var clockSetupVisible by rememberSaveable { mutableStateOf(false) }
    var resumeAfterClockSetup by remember { mutableStateOf(false) }
    var clockEpoch by remember { mutableIntStateOf(0) }
    var xiangqiPaused by remember { mutableStateOf(true) }
    var xiangqiMode by rememberSaveable { mutableStateOf(XiangqiPlayMode.CPU) }
    val lanSession = remember { XiangqiLanSession() }
    val lan by lanSession.state.collectAsStateWithLifecycle()
    val onlineSession = remember(context) { XiangqiOnlineSession(context) }
    val online by onlineSession.state.collectAsStateWithLifecycle()
    var starTaps by rememberSaveable { mutableIntStateOf(0) }
    var lampLit by rememberSaveable { mutableStateOf(false) }
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

    fun currentXiangqiPosition(): XiangqiState = when (xiangqiMode) {
        XiangqiPlayMode.ONLINE -> onlineSession.state.value.game
        XiangqiPlayMode.LAN -> lanSession.state.value.game
        else -> xiangqi
    }
    fun eligibleXiangqiTurn(): Boolean {
        if (!foreground || sleeping || activity != SecretActivity.XIANGQI || clockSetupVisible) return false
        val position = currentXiangqiPosition()
        if (position.outcome != XiangqiOutcome.PLAYING) return false
        return when (xiangqiMode) {
            XiangqiPlayMode.CPU -> xiangqiStarted && !xiangqiPaused && position.turnSide == XiangqiSide.RED
            XiangqiPlayMode.HOTSEAT -> xiangqiStarted && !xiangqiPaused
            XiangqiPlayMode.ONLINE, XiangqiPlayMode.LAN -> {
                val room = if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.state.value else lanSession.state.value
                room.connected && !room.awaitingAck && position.turnSide == room.localSide
            }
        }
    }
    fun playXiangqiMove(move: XiangqiMove) {
        if (!eligibleXiangqiTurn()) return
        if (xiangqiMode == XiangqiPlayMode.LAN) lanSession.submitMove(move)
        else if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.submitMove(move)
        else xiangqi = XiangqiEngine.play(xiangqi, move)
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
        fun eligible() = foreground && !sleeping && activity == SecretActivity.GOMOKU && !gomokuPaused &&
            gomoku.currentPlayer == 1 && gomoku.outcome == GomokuOutcome.PLAYING
        if (helpBusy || !eligible()) return
        val position = gomoku
        val generation = ++helpGeneration
        helpGomokuPosition = position; helpBusy = true
        helpJob = scope.launch {
            try {
                val move = withContext(Dispatchers.Default) {
                    val computeContext = currentCoroutineContext()
                    GomokuStrongMoveHelper.chooseMove(position) { !computeContext.isActive }
                } ?: return@launch
                delay(500)
                if (helpGeneration == generation && eligible() && gomoku == position)
                    gomoku = GomokuEngine.play(position, move.x, move.y)
            } finally {
                if (helpGeneration == generation) { helpBusy = false; helpGomokuPosition = null; helpJob = null }
            }
        }
    }

    fun pauseLocalToys() {
        cancelHelp()
        snakeRunning = false
        gomokuPaused = true
        xiangqiPaused = true
        spinJob?.cancel()
        spinning = false
    }
    fun closeNetworkRooms() {
        networkGraceJob?.cancel()
        networkGraceJob = null
        lanSession.close()
        onlineSession.close()
    }
    fun pauseToys() { pauseLocalToys(); closeNetworkRooms() }
    fun closeToy() { pauseToys(); activity = null; clockSetupVisible = false; resumeAfterClockSetup = false }
    fun openToy(value: SecretActivity) {
        pauseToys()
        if (!sleeping && foreground) {
            activity = value
            if (value == SecretActivity.GOMOKU && gomoku.board.all { it == 0 }) gomokuPaused = false
            if (value == SecretActivity.XIANGQI && xiangqiMode != XiangqiPlayMode.ONLINE && xiangqiMode != XiangqiPlayMode.LAN && !xiangqiStarted) {
                xiangqiPaused = true
                resumeAfterClockSetup = false
                clockSetupVisible = true
            }
        }
    }
    fun rest(value: Boolean) {
        closeToy()
        scope.launch { withContext(NonCancellable) { prefs.setSecretPetSleeping(value) } }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                foreground = false
                resumeAfterClockSetup = false
                pauseLocalToys()
                networkGraceJob?.cancel()
                val sharingRoom = activity == SecretActivity.XIANGQI && !sleeping &&
                    (xiangqiMode == XiangqiPlayMode.ONLINE || xiangqiMode == XiangqiPlayMode.LAN) &&
                    (lanSession.state.value.sessionActive || onlineSession.state.value.sessionActive)
                if (sharingRoom) networkGraceJob = scope.launch {
                    delay(120_000)
                    lanSession.close(); onlineSession.close()
                    networkGraceJob = null
                } else closeNetworkRooms()
            }
            if (event == Lifecycle.Event.ON_START) {
                networkGraceJob?.cancel(); networkGraceJob = null
                foreground = true
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); spinJob?.cancel(); cancelHelp(); closeNetworkRooms() }
    }
    LaunchedEffect(sleeping) { if (sleeping) closeToy() }
    LaunchedEffect(bubbleToken) { if (secretBubble != null) { delay(3000); secretBubble = null } }
    LaunchedEffect(gomoku, xiangqi, online.game, lan.game, online.connected, lan.connected, online.awaitingAck, lan.awaitingAck) {
        if (helpBusy && ((helpGomokuPosition != null && helpGomokuPosition != gomoku) ||
            (helpXiangqiPosition != null && (helpXiangqiPosition != currentXiangqiPosition() || !eligibleXiangqiTurn())))) cancelHelp()
    }
    LaunchedEffect(activity, foreground, sleeping, xiangqiPaused, xiangqiMode, xiangqi.turnSide, xiangqi.outcome) {
        if (activity == SecretActivity.XIANGQI && foreground && !sleeping && !xiangqiPaused &&
            xiangqiMode == XiangqiPlayMode.CPU && xiangqi.turnSide == XiangqiSide.BLACK && xiangqi.outcome == XiangqiOutcome.PLAYING) {
            val position = xiangqi
            delay(420)
            val move = withContext(Dispatchers.Default) { XiangqiEngine.chooseCpuMove(position) }
            if (activity == SecretActivity.XIANGQI && foreground && !sleeping && !xiangqiPaused &&
                xiangqiMode == XiangqiPlayMode.CPU && xiangqi == position && move != null) xiangqi = XiangqiEngine.play(position, move)
        }
    }
    LaunchedEffect(activity, foreground, sleeping, xiangqiPaused, xiangqiMode, xiangqi.ply,
        xiangqi.turnSide, xiangqi.outcome, clockEpoch) {
        xiangqiClock = xiangqiClock.forPosition(xiangqi)
        val localMode = xiangqiMode == XiangqiPlayMode.CPU || xiangqiMode == XiangqiPlayMode.HOTSEAT
        if (activity == SecretActivity.XIANGQI && foreground && !sleeping && !xiangqiPaused &&
            localMode && xiangqi.outcome == XiangqiOutcome.PLAYING) {
            var lastTick = SystemClock.elapsedRealtime()
            val clockPosition = xiangqi
            val epoch = clockEpoch
            try {
                while (!xiangqiClock.expired) {
                    delay(1000)
                    val now = SystemClock.elapsedRealtime()
                    xiangqiClock = xiangqiClock.elapse(now - lastTick, active = true)
                    lastTick = now
                }
            } finally {
                if (clockEpoch == epoch && xiangqiClock.ply == clockPosition.ply && xiangqiClock.side == clockPosition.turnSide)
                    xiangqiClock = xiangqiClock.elapse(SystemClock.elapsedRealtime() - lastTick, active = true)
            }
        }
    }
    LaunchedEffect(activity, foreground, sleeping, snakeRunning) {
        while (activity == SecretActivity.SNAKE && foreground && !sleeping && snakeRunning && !snake.gameOver) {
            delay((260L - (snake.score / 3) * 8L).coerceAtLeast(140L))
            if (activity == SecretActivity.SNAKE && foreground && !sleeping && snakeRunning) {
                snake = SnakeEngine.tick(snake)
                if (snake.gameOver) snakeRunning = false
            }
        }
    }
    LaunchedEffect(activity, foreground, sleeping, gomokuPaused, gomoku.currentPlayer, gomoku.outcome, gomoku.size) {
        if (activity == SecretActivity.GOMOKU && foreground && !sleeping && !gomokuPaused &&
            gomoku.currentPlayer == 2 && gomoku.outcome == GomokuOutcome.PLAYING) {
            val position = gomoku
            delay(420)
            val move = withContext(Dispatchers.Default) { GomokuEngine.chooseCpuMove(position) }
            if (activity == SecretActivity.GOMOKU && foreground && !sleeping && !gomokuPaused && gomoku == position) {
                move?.let { gomoku = GomokuEngine.play(position, it.x, it.y) }
            }
        }
    }
    BackHandler { if (activity != null) closeToy() else { pauseToys(); onBack() } }

    Box(Modifier.fillMaxSize().background(Color(0xFFE8D1B0))) {
        if (!fullGame) SecretRoomStage(sleeping = sleeping, onBack = { pauseToys(); onBack() }, onPet = { rest(!sleeping) },
            onWheel = { openToy(SecretActivity.WHEEL) }, onSnake = { openToy(SecretActivity.SNAKE) },
            onGomoku = { openToy(SecretActivity.BOARD) }, onPaper = { openToy(SecretActivity.PAPER) },
            lampLit = lampLit, sparkleToken = starTaps, bubble = secretBubble,
            onStar = { starTaps++; secretBubble = if (starTaps % 3 == 0) "找到暗号啦：今天可以慢慢来 ♡" else "这颗星星把一点好运藏进你口袋里了 ✦"; bubbleToken++ },
            onLamp = { lampLit = !lampLit; secretBubble = if (lampLit) "小灯亮一点，阿噜就靠近一点 ♡" else "小灯轻轻歇一会儿，星星还在陪你"; bubbleToken++ },
            onPetSecret = { secretBubble = "嘘，阿噜偷偷告诉你：它最喜欢你来坐一会儿。"; bubbleToken++ })
        if (activity == SecretActivity.XIANGQI && xiangqiMode == XiangqiPlayMode.ONLINE && online.sessionActive) {
            onlineSession.transportView?.let { transport ->
                key(transport) {
                    AndroidView(factory = { transport }, modifier = Modifier.size(1.dp).graphicsLayer { alpha = 0f })
                }
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
                    onDirection = { snake = SnakeEngine.turn(snake, it) },
                    onToggle = { snakeStarted = true; snakeRunning = !snakeRunning && !snake.gameOver && foreground },
                    onRestart = { snake = SnakeEngine.newGame(); snakeRunning = false; snakeStarted = false })
                SecretActivity.GOMOKU -> SecretGomokuGame(gomoku, gomokuPaused, boardSize,
                    onMove = { x, y -> if (!helpBusy && !gomokuPaused && foreground && gomoku.currentPlayer == 1) gomoku = GomokuEngine.play(gomoku, x, y) },
                    onToggle = { cancelHelp(); gomokuPaused = !gomokuPaused },
                    onRestart = { cancelHelp(); gomoku = GomokuEngine.newGame(); gomokuPaused = false },
                    helpBusy = helpBusy, onSecretHelp = ::requestGomokuHelp)
                SecretActivity.BOARD -> {
                    Text("棋盘替你铺好了，今天想下哪一种？", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { openToy(SecretActivity.GOMOKU) }) { Text("五子棋") }
                        OutlinedButton(onClick = { openToy(SecretActivity.XIANGQI) }) { Text("中国象棋") }
                    }
                }
                SecretActivity.XIANGQI -> SecretXiangqiGame(
                    state = when (xiangqiMode) { XiangqiPlayMode.LAN -> lan.game; XiangqiPlayMode.ONLINE -> online.game; else -> xiangqi },
                    mode = xiangqiMode, paused = xiangqiPaused, boardWidth = boardSize,
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
                    onMode = { value ->
                        cancelHelp(); closeNetworkRooms(); xiangqiMode = value; resumeAfterClockSetup = false
                        clockSetupVisible = false
                        if (value == XiangqiPlayMode.CPU || value == XiangqiPlayMode.HOTSEAT) {
                            xiangqiPaused = !xiangqiStarted
                            if (!xiangqiStarted) clockSetupVisible = true
                        }
                    },
                    onMove = { move -> if (!helpBusy) playXiangqiMove(move) },
                    onToggle = { cancelHelp(); if (!xiangqiStarted) { xiangqiPaused = true; resumeAfterClockSetup = false; clockSetupVisible = true }
                        else xiangqiPaused = !xiangqiPaused },
                    onRestart = { cancelHelp(); if (xiangqiMode == XiangqiPlayMode.LAN) lanSession.restart()
                        else if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.restart()
                        else { xiangqi = XiangqiEngine.newGame(); xiangqiClock = XiangqiThinkingClock.reset(xiangqi, thinkingSeconds)
                            clockEpoch++; xiangqiStarted = false; xiangqiPaused = true; resumeAfterClockSetup = false; clockSetupVisible = true } },
                    onHost = { if (foreground) { if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.host() else lanSession.host() } },
                    onJoin = { address -> if (foreground) { if (xiangqiMode == XiangqiPlayMode.ONLINE) onlineSession.join(address) else lanSession.join(address) } },
                    onDisconnect = { cancelHelp(); closeNetworkRooms() },
                    onPuzzle = { position -> cancelHelp(); xiangqi=position; xiangqiClock=XiangqiThinkingClock.reset(position, thinkingSeconds)
                        clockEpoch++; xiangqiStarted=true; xiangqiPaused=false },
                    helpBusy = helpBusy, assistedSelection = assistedSelection,
                    onSecretHelp = ::requestXiangqiHelp, onModalOpened = ::cancelHelp)
                SecretActivity.PAPER -> {
                    AlbumPaperPage { Text(secretNotes[note % secretNotes.size],
                        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 20.dp)) }
                    TextButton(onClick = { note = (note + 1) % secretNotes.size }) { Text("再翻一张小秘密 ♡") }
                }
                SecretActivity.WHEEL -> {
                    SecretPrizeWheel(rotation.value, minOf(boardSize, 270.dp))
                    Button(onClick = {
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
        if (fullGame) SecretGamePage(title, ::closeToy,
            boardAspect = if (toy == SecretActivity.XIANGQI) 1.13f else 1f,
            reservedHeight = when (toy) {
                SecretActivity.SNAKE -> 320
                SecretActivity.XIANGQI -> 340
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
                            resumeAfterClockSetup = xiangqiStarted && !xiangqiPaused && foreground
                            xiangqiPaused = true; clockSetupVisible = true
                        } })
                }
            },
            centeredHeader = toy == SecretActivity.XIANGQI,
            content = toyContent)
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
                }, onConfirm = { seconds ->
                    thinkingSeconds = seconds
                    gamePreferences.edit().putInt("xiangqi_thinking_seconds", seconds).apply()
                    xiangqiClock = XiangqiThinkingClock.reset(xiangqi, seconds)
                    clockEpoch++; xiangqiStarted = true; xiangqiPaused = !foreground; clockSetupVisible = false; resumeAfterClockSetup = false
                })
        }
    }
}

/** Games own the whole destination; the room and its hit targets are removed while playing. */
@Composable
private fun SecretGamePage(title: String, onBack: () -> Unit, boardAspect: Float, reservedHeight: Int,
    headerTrailing: @Composable () -> Unit = {},
    centeredHeader: Boolean = false,
    content: @Composable ColumnScope.(androidx.compose.ui.unit.Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFFFAF7F1)).safeDrawingPadding()) {
        val boardSize = minOf((maxWidth - 24.dp).coerceAtLeast(60.dp),
            ((maxHeight - reservedHeight.dp) / boardAspect).coerceAtLeast(60.dp), 560.dp)
        if (title == "阿噜棋桌" && maxHeight - boardSize * boardAspect - reservedHeight.dp > 145.dp) {
            Image(painterResource(R.drawable.gulu_idle), null,
                Modifier.align(Alignment.BottomEnd).padding(end = 9.dp, bottom = 9.dp).size(65.dp).graphicsLayer { alpha = .82f })
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
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
    }
}

@Composable
private fun SecretToyDialog(title: String, onDismiss: () -> Unit, reservedHeight: Int,
    content: @Composable ColumnScope.(androidx.compose.ui.unit.Dp) -> Unit) {
    val configuration = LocalConfiguration.current
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
                    TextButton(onClick = onDismiss) { Text("收好") }
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
private fun SecretRoomStage(sleeping: Boolean, onBack: () -> Unit, onPet: () -> Unit,
    onWheel: () -> Unit, onSnake: () -> Unit, onGomoku: () -> Unit, onPaper: () -> Unit,
    lampLit: Boolean, sparkleToken: Int, bubble: String?, onStar: () -> Unit, onLamp: () -> Unit, onPetSecret: () -> Unit) {
    val resources = LocalContext.current.resources
    val resource = R.drawable.world_secret_room_portrait_v3
    val art by produceState<ImageBitmap?>(LittleWorldArtwork.cachedImage(resource), resources, resource) {
        value = withContext(Dispatchers.IO) { LittleWorldArtwork.image(resources, resource) }
    }
    val sparkle = remember { Animatable(0f) }
    LaunchedEffect(sparkleToken) {
        if (sparkleToken > 0) { sparkle.snapTo(1f); sparkle.animateTo(0f, tween(1100)) }
    }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val ratio = art?.let { it.width.toFloat() / it.height } ?: .67f
        val naturalWidth = maxOf(maxWidth, maxHeight * ratio)
        val naturalHeight = naturalWidth / ratio
        // Keep the whole toy arrangement reachable even on a narrow phone.
        val sceneWidth = naturalWidth.coerceAtMost(maxWidth / .94f)
        val sceneHeight = naturalHeight.coerceAtMost(maxHeight / .97f)
        val left = (maxWidth - sceneWidth) / 2
        val top = (maxHeight - sceneHeight) / 2
        art?.let { bitmap -> Canvas(Modifier.matchParentSize()) {
            drawImage(bitmap, dstOffset = IntOffset(left.toPx().roundToInt(), top.toPx().roundToInt()),
                dstSize = IntSize(sceneWidth.toPx().roundToInt(), sceneHeight.toPx().roundToInt()),
                filterQuality = FilterQuality.Medium, colorFilter = MutedSceneColorFilter)
        } }
        SkinSceneDecor(Modifier.matchParentSize(), paintBackground = false)
        // Each plaque sits against its toy; the larger adjoining target includes the painted object.
        fun hotspot(x: Float, y: Float, w: Float, h: Float) = Modifier
            .offset(left + sceneWidth * x, top + sceneHeight * y).size(sceneWidth * w, sceneHeight * h)
        @Composable fun toy(label: String, x: Float, y: Float, w: Float, h: Float,
            tagX: Float, tagY: Float, tagW: Float, tagH: Float, action: () -> Unit) {
            Box(hotspot(x, y, w, h).clickable(enabled = !sleeping, role = Role.Button, onClickLabel = label, onClick = action)
                .semantics { contentDescription = label })
            Text(label, hotspot(tagX - tagW / 2, tagY - tagH / 2, tagW, tagH)
                .clickable(enabled = !sleeping, role = Role.Button, onClick = action)
                .wrapContentSize(Alignment.Center), fontFamily = GuluBrandFont,
                fontSize = 10.sp, color = Color(0xFF66462C), maxLines = 1)
        }
        toy("好运转盘", .15f, .236f, .24f, .168f, .27f, .418f, .107f, .028f, onWheel)
        toy("贪吃蛇", .755f, .29f, .183f, .154f, .846f, .462f, .105f, .028f, onSnake)
        toy("小棋桌", .61f, .541f, .34f, .064f, .854f, .529f, .094f, .027f, onGomoku)
        Box(hotspot(.17f, .488f, .27f, .077f).clickable(enabled = !sleeping, role = Role.Button,
            onClickLabel = "秘密纸条", onClick = onPaper).semantics { contentDescription = "秘密纸条" })
        ScenePlaqueButton("秘密纸条", Modifier.offset(left + sceneWidth * .27f - 35.dp, top + sceneHeight * .556f)
            .width(70.dp), enabled = !sleeping, onClick = onPaper)
        Box(hotspot(.30f, .634f, .41f, .172f).combinedClickable(role = Role.Button,
            onClickLabel = if (sleeping) "轻轻叫醒阿噜" else "让阿噜打个盹", onClick = onPet,
            onLongClickLabel = "听阿噜藏起来的小秘密", onLongClick = onPetSecret)
            .semantics { contentDescription = if (sleeping) "轻轻叫醒阿噜" else "让阿噜打个盹" })
        Box(hotspot(.65f, .10f, .18f, .09f).clickable(role = Role.Button, onClickLabel = "摸摸挂着的小星星", onClick = onStar)
            .semantics { contentDescription = "摸摸挂着的小星星" })
        Box(hotspot(.685f, .224f, .10f, .078f).clickable(role = Role.Button, onClickLabel = "拨亮小油灯", onClick = onLamp)
            .semantics { contentDescription = "拨亮小油灯" })
        Canvas(Modifier.matchParentSize()) {
            val sw = sceneWidth.toPx(); val sh = sceneHeight.toPx()
            if (lampLit) drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(Color(0xFFFFD79A).copy(alpha = .30f), Color.Transparent),
                Offset(left.toPx() + sw * .73f, top.toPx() + sh * .268f), sw * .19f), sw * .19f,
                Offset(left.toPx() + sw * .73f, top.toPx() + sh * .268f))
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
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            ScenePlaqueButton("‹ 小窝", onClick = onBack)
            ScenePlaqueButton(if (sleeping) "叫醒" else "打个盹", onClick = onPet)
        }
        Text("阿噜的秘密基地", hotspot(.346f, .074f, .314f, .055f).wrapContentSize(Alignment.Center),
            fontFamily = GuluBrandFont, fontSize = 17.sp, color = Color(0xFF594532))
        if (sleeping) {
            Canvas(Modifier.matchParentSize()) {
                val sceneX = left.toPx(); val sceneY = top.toPx()
                val sw = sceneWidth.toPx(); val sh = sceneHeight.toPx()
                listOf(Offset(.456f, .700f), Offset(.567f, .712f)).forEach { eye ->
                    val center = Offset(sceneX + sw * eye.x, sceneY + sh * eye.y)
                    drawOval(Color(0xFFE8CBFD), center - Offset(sw * .024f, sh * .012f), Size(sw * .048f, sh * .024f))
                    val sleepyEye = Path().apply {
                        moveTo(center.x - sw * .015f, center.y)
                        quadraticBezierTo(center.x, center.y + sh * .006f, center.x + sw * .015f, center.y)
                    }
                    drawPath(sleepyEye, Color(0xFF715293), style = Stroke(2.dp.toPx()))
                }
                drawRect(Color(0xFF292C54).copy(alpha = .22f))
            }
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
