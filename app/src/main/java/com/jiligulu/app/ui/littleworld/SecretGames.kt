package com.jiligulu.app.ui.littleworld


import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jiligulu.app.core.audio.UiSound
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class GomokuPlayMode { CPU, NEARBY, ONLINE, HOTSEAT }

@Composable
internal fun ColumnScope.SecretSnakeGame(state: SnakeState, running: Boolean, started: Boolean, boardSize: Dp,
    onDirection: (SnakeDirection) -> Unit, onToggle: () -> Unit, onRestart: () -> Unit) {
    val latestDirection by rememberUpdatedState(onDirection)
    val context = LocalContext.current
    val finished = state.gameOver || state.won
    val finish = if (!finished) null else if (state.won)
        GameFinishPresentation("星星全收好啦", "小蛇走满了这片天地", FinishMood.WIN)
    else GameFinishPresentation("这一圈结束啦", "已经收好 ${state.score} 颗星星", FinishMood.LOSE)
    val finishIdentity = listOf("snake", state.width, state.height)
    var lastFinished by remember(finishIdentity) { mutableStateOf(finished) }
    var wasRunning by remember(finishIdentity) { mutableStateOf(running) }
    var finishEvent by remember(finishIdentity) { mutableIntStateOf(0) }
    var promptRequest by remember(finishIdentity) { mutableIntStateOf(0) }
    LaunchedEffect(finished, running, finishIdentity) {
        // An archive can arrive after the initial composition. Only an actually running
        // snake reaching its end is a fresh result, never that asynchronous restore.
        if (finished && !lastFinished && wasRunning) finishEvent++
        lastFinished = finished
        wasRunning = running
    }
    GameFinishOverlay(finish, finishIdentity, state.takeIf { finished }, finishEvent, promptRequest, onRestart)
    val status = when {
        state.won -> "小蛇把星星全收好啦！"
        state.gameOver -> "碰到啦，再陪小蛇走一圈吧"
        running -> "墙的另一边也是这里，小心自己的尾巴"
        started -> "小蛇休息中，继续时会从这里出发"
        else -> "滑一下棋盘或拨动摇杆，小蛇就会出发"
    }
    Row(Modifier.width(boardSize), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text("✦ ${state.score}", color = Color(0xFF96805A), style = MaterialTheme.typography.titleLarge)
        Text(if (running) "小蛇出发啦" else if (state.gameOver) "本局结束" else "休息一下", style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF887F86))
    }
    Spacer(Modifier.height(24.dp))
    Canvas(Modifier.size(boardSize).clip(RoundedCornerShape(14.dp)).background(Color(0xFFF0F1DF))
        .semantics { contentDescription = "贪吃蛇棋盘，收好${state.score}颗星星。可以滑动改变方向。" }
        .pointerInput(Unit) {
            var drag = Offset.Zero
            detectDragGestures(onDragStart = { UiSound.tap(context); drag = Offset.Zero }, onDragCancel = { drag = Offset.Zero }) { change, amount ->
                change.consume(); drag += amount
                if (drag.getDistance() >= 12.dp.toPx()) {
                    latestDirection(if (abs(drag.x) > abs(drag.y)) {
                        if (drag.x > 0) SnakeDirection.RIGHT else SnakeDirection.LEFT
                    } else if (drag.y > 0) SnakeDirection.DOWN else SnakeDirection.UP)
                    drag = Offset.Zero
                }
            }
        }) {
        val cellW = size.width / state.width
        val cellH = size.height / state.height
        repeat(state.width + 1) { x -> drawLine(Color(0xFF638867).copy(alpha = .09f),
            Offset(x * cellW, 0f), Offset(x * cellW, size.height), 1.dp.toPx()) }
        repeat(state.height + 1) { y -> drawLine(Color(0xFF638867).copy(alpha = .09f),
            Offset(0f, y * cellH), Offset(size.width, y * cellH), 1.dp.toPx()) }
        state.food?.let { food ->
            val center = Offset((food.x + .5f) * cellW, (food.y + .5f) * cellH)
            val radius = cellW * .43f
            val star = Path()
            repeat(10) { i ->
                val angle = -Math.PI / 2 + i * Math.PI / 5
                val r = if (i % 2 == 0) radius else radius * .48f
                val point = Offset(center.x + cos(angle).toFloat() * r, center.y + sin(angle).toFloat() * r)
                if (i == 0) star.moveTo(point.x, point.y) else star.lineTo(point.x, point.y)
            }
            star.close(); drawPath(star, Color(0xFFE9AD43))
        }
        state.body.forEachIndexed { index, cell ->
            val inset = if (index == 0) .06f else .11f
            drawRoundRect(if (index == 0) Color(0xFF446D49) else Color(0xFF76A16E),
                Offset((cell.x + inset) * cellW, (cell.y + inset) * cellH),
                Size(cellW * (1 - inset * 2), cellH * (1 - inset * 2)), CornerRadius(cellW * .23f))
        }
        val head = state.body.first()
        val center = Offset((head.x + .5f) * cellW, (head.y + .5f) * cellH)
        val forward = Offset(state.direction.dx.toFloat(), state.direction.dy.toFloat()) * (cellW * .17f)
        val across = Offset(-state.direction.dy.toFloat(), state.direction.dx.toFloat()) * (cellW * .20f)
        listOf(center + forward + across, center + forward - across).forEach { eye ->
            drawCircle(Color(0xFFFFF8DF), cellW * .105f, eye)
            drawCircle(Color(0xFF334039), cellW * .049f, eye + forward * .2f)
        }
    }
    if (finish != null) {
        GameFinishActions(finish, { promptRequest++ }, null, modifier = Modifier.width(boardSize).padding(top = 12.dp))
    } else {
        Text(status, modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF887F86))
        Spacer(Modifier.height(12.dp))
        SnakeJoystick(enabled = !state.gameOver, onDirection)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = { UiSound.tap(context); onToggle() }, enabled = !state.gameOver,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.width(120.dp)) { Text(if (running) "暂停" else if (started) "继续" else "开始") }
            TextButton(onClick = { UiSound.tap(context); onRestart() }) { Text("重新开始") }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SnakeJoystick(enabled: Boolean, onDirection: (SnakeDirection) -> Unit) {
    val latestDirection by rememberUpdatedState(onDirection)
    val context = LocalContext.current
    var thumb by remember { mutableStateOf(Offset.Zero) }
    Canvas(Modifier.size(92.dp).semantics { contentDescription = "小蛇方向摇杆，拖动改变方向" }
        .pointerInput(enabled) {
            if (enabled) detectDragGestures(onDragStart = { p -> UiSound.tap(context); thumb = p - Offset(size.width / 2f, size.height / 2f) },
                onDragEnd = { thumb = Offset.Zero }, onDragCancel = { thumb = Offset.Zero }) { change, amount ->
                change.consume()
                val next = thumb + amount
                val distance = next.getDistance()
                val limit = size.width * .29f
                thumb = if (distance > limit) next * (limit / distance) else next
                if (distance >= 8.dp.toPx()) latestDirection(if (abs(next.x) > abs(next.y)) {
                    if (next.x > 0) SnakeDirection.RIGHT else SnakeDirection.LEFT
                } else if (next.y > 0) SnakeDirection.DOWN else SnakeDirection.UP)
            }
        }) {
        drawCircle(Color(0xFF8A9B86).copy(alpha = .09f), size.width * .46f)
        drawCircle(Color(0xFF849280).copy(alpha = .28f), size.width * .46f, style = Stroke(1.dp.toPx()))
        listOf(Offset(0f,-1f),Offset(1f,0f),Offset(0f,1f),Offset(-1f,0f)).forEach { direction ->
            drawCircle(Color(0xFF879580).copy(alpha = .4f), 1.8.dp.toPx(), center + direction * (size.width * .36f))
        }
        drawCircle(Color(0xFF688065).copy(alpha = .13f), size.width * .19f, center + thumb + Offset(0f,2.dp.toPx()))
        drawCircle(Brush.radialGradient(listOf(Color(0xFFC9D7BD),Color(0xFF9BB88E)), center + thumb, size.width*.19f), size.width*.19f, center + thumb)
        drawCircle(Color.White.copy(alpha = .35f), size.width*.08f, center + thumb - Offset(3.dp.toPx(),3.dp.toPx()))
    }
}

@Composable
internal fun ColumnScope.SecretGomokuGame(state: GomokuState, paused: Boolean, boardSize: Dp,
    onMove: (Int, Int) -> Unit, onToggle: () -> Unit, onRestart: () -> Unit,
    canUndo: Boolean = false, onUndo: () -> Unit = {},
    canUndoBlack: Boolean = false, canUndoWhite: Boolean = false, onHotseatUndo: (Int) -> Unit = {},
    mode: GomokuPlayMode = GomokuPlayMode.CPU, onMode: (GomokuPlayMode) -> Unit = {},
    humanPlayer: Int = 1,
    room: GomokuRoomUiState? = null, nearby: NearbyRoomsState? = null,
    onHost: (String) -> Unit = {}, onJoin: (String) -> Unit = {}, onDisconnect: () -> Unit = {},
    onNearbyRetry: () -> Unit = {}, onUndoResponse: (Boolean) -> Unit = {},
    onMatchResponse: (Boolean) -> Unit = {}, onRematchResponse: (Boolean) -> Unit = {},
    onExit: () -> Unit = onDisconnect, onResign: () -> Unit = {}, restorationToken: Int = 0,
    onDraw: () -> Unit = {}, onDrawResponse: (Boolean) -> Unit = {}, onCancelDraw: () -> Unit = {},
    showRoomEntry: Boolean = false, playerProfile: ChessPlayerProfile = ChessPlayerProfile(),
    helpBusy: Boolean = false, onControlsBottom: (Float) -> Unit = {}) {
    val context = LocalContext.current
    var resignConfirm by remember(mode, room?.round) { mutableStateOf(false) }
    val network = mode == GomokuPlayMode.ONLINE || mode == GomokuPlayMode.NEARBY
    val localPlayer = if (network) room?.localPlayer else humanPlayer
    val finished = state.outcome != GomokuOutcome.PLAYING
    val finish = remember(state, mode, localPlayer, room?.resignedBy) {
        if(network&&finished&&room?.agreedDraw==true) {
            GameFinishPresentation("握手言和","这局平手，下次再战 ♡",FinishMood.DRAW,"和棋")
        } else if (network && finished && room?.resignedBy != null) {
            val lost = room.resignedBy == localPlayer
            GameFinishPresentation(if (lost) "这局先让一步" else "你赢啦", if (lost) "认输也可以，再下一盘吧" else "棋友认输，这一局收好啦",
                if (lost) FinishMood.LOSE else FinishMood.WIN, if (lost) "认输" else "胜出")
        } else GameFinishPresenter.gomoku(state,
            if(mode==GomokuPlayMode.HOTSEAT) null else localPlayer, if(mode==GomokuPlayMode.CPU) "阿噜" else "棋友")
    }
    val winningLine = remember(state) { GameFinishPresenter.gomokuWinningLine(state) }
    val finishIdentity = listOf(mode, restorationToken, localPlayer,
        if (network) room?.round else 0, if (network) room?.hostAddress else "",
        network && room?.connected == true)
    val finishGlow = remember(finishIdentity) { Animatable(0f) }
    var lastOutcome by remember(finishIdentity) { mutableStateOf(state.outcome) }
    var finishEvent by remember(finishIdentity) { mutableIntStateOf(0) }
    var promptRequest by remember(finishIdentity) { mutableIntStateOf(0) }
    LaunchedEffect(state, finishIdentity) {
        finishGlow.snapTo(0f)
        val celebrate = lastOutcome==GomokuOutcome.PLAYING && finished
        lastOutcome=state.outcome
        if(celebrate) {
            finishEvent++
            finishGlow.snapTo(1f)
            finishGlow.animateTo(0f,tween(GAME_FINISH_BOARD_EFFECT_MS))
        }
    }
    var matchResponseSent by remember(mode,room?.pendingMatchName) { mutableStateOf(false) }
    // 到场后才响匹配音；邀请可能被拒绝，恢复已连接的棋局也不重播。
    val matchLifecycle = LocalLifecycleOwner.current.lifecycle
    var wasConnected by remember(mode) { mutableStateOf(network && room?.connected == true) }
    LaunchedEffect(mode, room?.connected) {
        val connected = network && room?.connected == true
        if (connected && !wasConnected && matchLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            UiSound.match(context)
        }
        wasConnected = connected
    }
    if(network && room?.pendingMatchName!=null) {
        SecretWoodDialog("棋友来敲门啦", { if(!matchResponseSent) { matchResponseSent=true;onMatchResponse(false) } },
            confirmLabel="一起下",onConfirm={if(!matchResponseSent){matchResponseSent=true;onMatchResponse(true)}},
            dismissLabel="这次先不了",busy=matchResponseSent) { Text("${room.pendingMatchName}想和你下一盘五子棋。",style=MaterialTheme.typography.bodyMedium) }
    }
    var rematchResponseSent by remember(mode,room?.round,room?.rematchRequestedBy) { mutableStateOf(false) }
    if(network && room?.rematchRequestedBy!=null && room.rematchRequestedBy!=room.localPlayer && !room.myRematchRequested) {
        SecretWoodDialog("再摆一盘？",{if(!rematchResponseSent){rematchResponseSent=true;onRematchResponse(false)}},
            confirmLabel="换边再下",onConfirm={if(!rematchResponseSent){rematchResponseSent=true;onRematchResponse(true)}},
            dismissLabel="收桌",busy=rematchResponseSent,compactWidth=292.dp) { }
    }
    if (network && room != null && !room.connected && !room.reconnecting && !room.peerLeft && (!finished||showRoomEntry)) {
        if (mode == GomokuPlayMode.NEARBY) NearbyChessLobby(nearby, room.status, room.error, onJoin, onNearbyRetry, onControlsBottom)
        else OnlineChessLobby(room.sessionActive, room.busy, room.hostAddress, room.status, room.error,
            onHost, onJoin, onDisconnect, onControlsBottom, game = "gomoku")
        return
    }
    GameFinishOverlay(finish, finishIdentity, state.takeIf { finished }, finishEvent, promptRequest,
        onRestart, onExit, myRematchRequested = room?.myRematchRequested == true,
        suppressPrompt = network && room?.rematchRequestedBy != null &&
            room.rematchRequestedBy != room.localPlayer && !room.myRematchRequested)
    var drawResponseSent by remember(mode,room?.round,room?.revision,room?.pendingDrawRequest,room?.pendingDrawId){mutableStateOf(false)}
    if(network&&room?.pendingDrawRequest!=null&&room.pendingDrawRequest!=room.localPlayer) {
        fun respondDraw(accept:Boolean){if(!drawResponseSent){drawResponseSent=true;onDrawResponse(accept)}}
        SecretWoodDialog("棋友想和棋",{respondDraw(false)},confirmLabel="同意",onConfirm={respondDraw(true)},
            dismissLabel="继续下",busy=drawResponseSent,compactWidth=270.dp) { }
    }
    var undoResponseSent by remember(mode, room?.revision, room?.pendingUndoRequest) { mutableStateOf(false) }
    if (network && room?.pendingUndoRequest != null && room.pendingUndoRequest != room.localPlayer) {
        fun respond(accept: Boolean) { if (!undoResponseSent) { undoResponseSent = true; onUndoResponse(accept) } }
        SecretWoodDialog("棋友想重走这一手", { respond(false) }, busy = undoResponseSent,
            confirmLabel = "同意", onConfirm = { respond(true) }, dismissLabel = "继续下", compactWidth = 292.dp) { }
    }
    val latestMove by rememberUpdatedState(onMove)
    val roomAvailable = room?.connected == true && !room.awaitingAck && !room.localBackground &&
        !room.remoteBackground && !room.reconnecting && room.pendingUndoRequest == null && room.pendingDrawRequest==null
    val canMove = !helpBusy && state.outcome == GomokuOutcome.PLAYING && when (mode) {
        GomokuPlayMode.CPU -> !paused && state.currentPlayer == humanPlayer
        GomokuPlayMode.HOTSEAT -> !paused
        else -> roomAvailable && state.currentPlayer == localPlayer
    }
    val wood = remember { Brush.linearGradient(listOf(Color(0xFFF0E3CD), Color(0xFFE8D5B4))) }
    var peerDepartureDismissed by remember(mode, room?.round, room?.hostAddress) { mutableStateOf(false) }
    if (network && room?.peerLeft == true && !peerDepartureDismissed) SecretWoodDialog("棋友已离开", { peerDepartureDismissed = true },
        confirmLabel = "知道啦", compactWidth = 270.dp) { }
    ChessRoundStart(finishIdentity, ready = if (network) room?.connected == true else !paused,
        emptyBoard = state.board.none { it != 0 }, text = if (mode == GomokuPlayMode.HOTSEAT) "黑方先行"
            else if (localPlayer == 1) "你先行" else "对方先行")
    val status = when (state.outcome) {
        GomokuOutcome.HUMAN_WON,GomokuOutcome.CPU_WON -> finish?.headline?:"本局结束"
        GomokuOutcome.DRAW -> if(room?.agreedDraw==true)"双方同意和棋"else "棋盘坐满啦，这一局平手 ♡"
        GomokuOutcome.PLAYING -> when {
            network && room?.reconnecting == true -> "棋局还在，正在重新连接…"
            network && room?.remoteBackground == true -> "棋友暂时离开，棋局替你们留着"
            network && room?.pendingUndoRequest != null -> "等棋友商量这一步…"
            network && room?.pendingDrawRequest != null -> "等待棋友回应和棋"
            network && room?.awaitingAck == true -> "等另一张棋桌落稳…"
            !network && paused -> "棋局已暂停，棋子都替你留着"
            mode == GomokuPlayMode.CPU && state.currentPlayer != humanPlayer -> "阿噜在想下一步…"
            network -> if (state.currentPlayer == localPlayer) "轮到你了" else "轮到棋友了"
            else -> if (state.currentPlayer == 1) "轮到黑方了" else "轮到白方了"
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
    // Seats sit directly above a centered board; no spacer reserved for the old mode menu.
    val boardTop = ((maxHeight - boardSize) / 2 - 72.dp).coerceAtLeast(0.dp)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    Spacer(Modifier.height(boardTop))
    Row(Modifier.width(boardSize).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        val opponent = if (mode == GomokuPlayMode.CPU) ChessPlayerProfile("阿噜", "aru")
            else ChessPlayerProfile(room?.remoteName ?: "棋友", room?.remoteAvatarId ?: "star")
        fun profile(player: Int) = if (mode == GomokuPlayMode.HOTSEAT) ChessPlayerProfile(if (player == 1) playerProfile.name else "棋友", if (player == 1) playerProfile.avatarId else "cat")
            else if (localPlayer == player) playerProfile else opponent
        fun seatStatus(player: Int) = when { finished -> "结束"; room?.peerLeft == true && player != localPlayer -> "已离开"
            network && room?.remoteBackground == true && player != localPlayer -> "暂离"; !network && paused -> "暂停"
            state.currentPlayer == player -> if (mode == GomokuPlayMode.CPU && player != humanPlayer) "思考中" else "落子中"; else -> "等待" }
        ChessPlayerSeat(profile(1), Color(0xFF4C4950), "黑方", state.currentPlayer == 1 && !finished, seatStatus(1), Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        ChessPlayerSeat(profile(2), Color(0xFFF9F5EA), "白方", state.currentPlayer == 2 && !finished, seatStatus(2), Modifier.weight(1f), alignEnd = true)
    }
    Spacer(Modifier.height(16.dp))
    Canvas(Modifier.size(boardSize).shadow(3.dp, RoundedCornerShape(13.dp), clip = false)
        .clip(RoundedCornerShape(13.dp)).background(wood).drawWithCache {
            val grains = List(24) { band ->
                val x = size.width * (band + .4f) / 24f
                Offset(x, 0f) to Offset(x + 4.dp.toPx(), size.height)
            }
            onDrawBehind {
                grains.forEachIndexed { index, (start, end) ->
                    drawLine(Color(0xFFB79361).copy(alpha = if (index % 3 == 0) .055f else .023f), start, end,
                        (1 + index % 3).dp.toPx())
                }
                drawRoundRect(Color(0xFFBD9966).copy(alpha = .6f), cornerRadius = CornerRadius(13.dp.toPx()), style = Stroke(1.dp.toPx()))
            }
        }
        .semantics { contentDescription = "${state.size}路五子棋棋盘，" + when (mode) {
            GomokuPlayMode.CPU -> if(humanPlayer==1) "你执黑棋，阿噜执白棋。" else "你执白棋，阿噜执黑棋。"
            GomokuPlayMode.HOTSEAT -> "同屏双人，黑白双方轮流落子，黑方先行。"
            else -> if (localPlayer == 1) "你执黑棋，棋友执白棋。" else "你执白棋，棋友执黑棋。"
        } + status }
        .pointerInput(canMove, state.size) {
            if (canMove) detectTapGestures { offset ->
                val padding = size.width * .047f
                val step = (size.width - padding * 2) / (state.size - 1)
                val x = ((offset.x - padding) / step).roundToInt()
                val y = ((offset.y - padding) / step).roundToInt()
                if (x in 0 until state.size && y in 0 until state.size) latestMove(x, y)
            }
        }) {
        val padding = size.width * .047f
        val step = (size.width - padding * 2) / (state.size - 1)
        val edge = size.width - padding
        repeat(state.size) { line ->
            val p = padding + line * step
            drawLine(Color(0xFF886844), Offset(padding, p), Offset(edge, p), 1.dp.toPx())
            drawLine(Color(0xFF886844), Offset(p, padding), Offset(p, edge), 1.dp.toPx())
        }
        val farStar = state.size - 4
        listOf(GridCell(3, 3), GridCell(state.size / 2, state.size / 2), GridCell(farStar, 3), GridCell(3, farStar), GridCell(farStar, farStar))
            .filter { it.x < state.size && it.y < state.size }.forEach { dot ->
                drawCircle(Color(0xFF886844), step * .09f, Offset(padding + dot.x * step, padding + dot.y * step))
            }
        state.board.forEachIndexed { i, stone ->
            if (stone != 0) {
                val center = Offset(padding + (i % state.size) * step, padding + (i / state.size) * step)
                drawCircle(Color(0xFF715034).copy(alpha = .25f), step * .44f, center + Offset(1.dp.toPx(), 2.dp.toPx()))
                drawCircle(if (stone == 1) Color(0xFF3D3C43) else Color(0xFFFFFBEF), step * .43f, center)
                drawCircle(if (stone == 1) Color.White.copy(alpha = .20f) else Color.White, step * .12f,
                    center - Offset(step * .14f, step * .14f))
            }
        }
        state.lastMove?.let { move ->
            drawCircle(Color(0xFFE6A149), step * .16f, Offset(padding + move.x * step, padding + move.y * step), style = Stroke(1.5.dp.toPx()))
        }
        if(winningLine.size>=5) {
            val first=winningLine.first();val last=winningLine.last()
            val start=Offset(padding+first.x*step,padding+first.y*step)
            val end=Offset(padding+last.x*step,padding+last.y*step)
            drawLine(Color(0xFFFFE0A0).copy(alpha=.30f+finishGlow.value*.45f),start,end,step*.20f,
                cap=androidx.compose.ui.graphics.StrokeCap.Round)
            if(finishGlow.value>0f) winningLine.forEachIndexed { i,cell ->
                val c=Offset(padding+cell.x*step,padding+cell.y*step)
                val phase=1f-finishGlow.value
                drawCircle(Color(0xFFC8944C).copy(alpha=finishGlow.value),step*(.52f+phase*.55f),c,style=Stroke(1.5.dp.toPx()))
                val p=c+Offset(sin((i+phase)*4).toFloat(),-1f)*(step*(.2f+phase*.9f))
                drawLine(Color(0xFFFFEBC4).copy(alpha=finishGlow.value),p-Offset(2.dp.toPx(),0f),p+Offset(2.dp.toPx(),0f),1.5.dp.toPx())
                drawLine(Color(0xFFFFEBC4).copy(alpha=finishGlow.value),p-Offset(0f,2.dp.toPx()),p+Offset(0f,2.dp.toPx()),1.5.dp.toPx())
            }
        }
    }
    if(finish!=null) {
        GameFinishActions(finish,{ promptRequest++ },onExit,network,room?.roomEnded==true,room?.resultSecondsLeft?:0,
            room?.myRematchRequested==true,Modifier.width(boardSize).padding(top=10.dp)
                .onGloballyPositioned{onControlsBottom(it.boundsInRoot().bottom)})
    } else {
    Spacer(Modifier.height(24.dp))
    Row(Modifier.width(boardSize).padding(horizontal = if (mode == GomokuPlayMode.HOTSEAT) 6.dp else 26.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!network) GameIconTool(if (paused) Icons.Outlined.PlayCircleOutline else Icons.Outlined.PauseCircleOutline,
            if (paused) "继续" else "暂停", onToggle, Modifier.weight(1f), enabled = state.outcome == GomokuOutcome.PLAYING)
        else GameIconTool(Icons.Outlined.Handshake, if(room?.myDrawRequested==true)"取消和棋"else"和棋",
            if(room?.myDrawRequested==true)onCancelDraw else onDraw, Modifier.weight(1f),
            enabled = roomAvailable || room?.myDrawRequested==true)
        if(!network) GameIconTool(Icons.Outlined.Refresh, "重开", onRestart, Modifier.weight(1f))
        if (mode == GomokuPlayMode.HOTSEAT) {
            GameIconTool(Icons.AutoMirrored.Outlined.Undo, "悔黑", { onHotseatUndo(1) }, Modifier.weight(1f),
                enabled = canUndoBlack && !helpBusy, tint = Color(0xFF625B67))
            GameIconTool(Icons.AutoMirrored.Outlined.Undo, "悔白", { onHotseatUndo(2) }, Modifier.weight(1f),
                enabled = canUndoWhite && !helpBusy, tint = Color(0xFF9C8B78))
        } else GameIconTool(Icons.AutoMirrored.Outlined.Undo, "悔棋", onUndo, Modifier.weight(1f),
            enabled = canUndo && !helpBusy && (!network || roomAvailable), cue = com.jiligulu.app.core.audio.UiCue.TOUCH)
        if (network) GameIconTool(Icons.Outlined.Logout, "离开", onDisconnect, Modifier.weight(1f))
    }
    Spacer(Modifier.height(10.dp).onGloballyPositioned { onControlsBottom(it.boundsInRoot().bottom) })
    }
    }
    }
}

@Composable
internal fun GameSeat(name: String, stoneColor: Color, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Canvas(Modifier.size(17.dp)) {
            drawCircle(Color(0xFFAE9B88).copy(alpha = .16f), center = center + Offset(0f, 1.dp.toPx()))
            drawCircle(stoneColor, radius = size.width * .44f)
            drawCircle(Color(0xFFA58E74).copy(alpha = .3f), radius = size.width * .44f, style = Stroke(.7.dp.toPx()))
        }
        Text(name, style = MaterialTheme.typography.labelLarge, color = if (active) Color(0xFF655171) else Color(0xFF9B8F97))
        if (active) Canvas(Modifier.size(4.dp)) { drawCircle(Color(0xFFB29BC4)) }
    }
}
