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
    Text(status, modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF887F86))
    Spacer(Modifier.height(12.dp))
    SnakeJoystick(enabled = !state.gameOver, onDirection)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledTonalButton(onClick = { UiSound.tap(context); onToggle() }, enabled = !state.gameOver,
            shape = RoundedCornerShape(14.dp), modifier = Modifier.width(120.dp)) { Text(if (running) "暂停" else if (started) "继续" else "开始") }
        TextButton(onClick = { UiSound.tap(context); onRestart() }) { Text("重新开始") }
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
    mode: GomokuPlayMode = GomokuPlayMode.CPU, onMode: (GomokuPlayMode) -> Unit = {},
    humanPlayer: Int = 1,
    room: GomokuRoomUiState? = null, nearby: NearbyRoomsState? = null,
    onHost: (String) -> Unit = {}, onJoin: (String) -> Unit = {}, onDisconnect: () -> Unit = {},
    onNearbyRetry: () -> Unit = {}, onUndoResponse: (Boolean) -> Unit = {},
    onMatchResponse: (Boolean) -> Unit = {}, onRematchResponse: (Boolean) -> Unit = {},
    onExit: () -> Unit = onDisconnect, restorationToken: Int = 0,
    showRoomEntry: Boolean = false,
    helpBusy: Boolean = false, onControlsBottom: (Float) -> Unit = {}) {
    val context = LocalContext.current
    var modeMenu by remember { mutableStateOf(false) }
    val network = mode == GomokuPlayMode.ONLINE || mode == GomokuPlayMode.NEARBY
    val localPlayer = if (network) room?.localPlayer else humanPlayer
    val finished = state.outcome != GomokuOutcome.PLAYING
    val finish = remember(state,mode,localPlayer) { GameFinishPresenter.gomoku(state,
        if(mode==GomokuPlayMode.HOTSEAT) null else localPlayer, if(mode==GomokuPlayMode.CPU) "阿噜" else "棋友") }
    val winningLine = remember(state) { GameFinishPresenter.gomokuWinningLine(state) }
    val finishGlow = remember { Animatable(0f) }
    var lastOutcome by remember(mode,restorationToken) { mutableStateOf(state.outcome) }
    LaunchedEffect(state.outcome,state.lastMove,restorationToken) {
        finishGlow.snapTo(0f)
        val celebrate = lastOutcome==GomokuOutcome.PLAYING && finished
        lastOutcome=state.outcome
        if(celebrate) { finishGlow.snapTo(1f); finishGlow.animateTo(0f,tween(3200)) }
    }
    val modeNames = remember { mapOf(GomokuPlayMode.CPU to "和阿噜下", GomokuPlayMode.NEARBY to "附近的人",
        GomokuPlayMode.ONLINE to "创建房间", GomokuPlayMode.HOTSEAT to "同屏双人") }
    Row(Modifier.width(boardSize).height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(onClick = { UiSound.select(context); modeMenu = true }, enabled=!finished, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(modeNames.getValue(mode), color = Color(0xFF766A7F), style = MaterialTheme.typography.bodySmall)
                Icon(Icons.Outlined.ExpandMore, "选择对局方式", Modifier.size(16.dp), tint = Color(0xFF928497))
            }
            if(modeMenu) SecretWoodDialog("和谁下？",{modeMenu=false},confirmLabel="返回棋盘") {
                modeNames.forEach { (value,label)->TextButton(onClick={
                    UiSound.select(context);modeMenu=false;if(value!=mode)onMode(value)
                },modifier=Modifier.fillMaxWidth().height(48.dp),colors=ButtonDefaults.textButtonColors(contentColor=SecretWoodInk)) {
                    Text(if(value==mode) "✓ $label" else label)
                } }
            }
        }
    }
    var matchResponseSent by remember(mode,room?.pendingMatchName) { mutableStateOf(false) }
    // 有棋友来敲门：匹配成功的声音（与象棋共用同一条语义）
    LaunchedEffect(room?.pendingMatchName) { if (room?.pendingMatchName != null) UiSound.match(context) }
    if(network && room?.pendingMatchName!=null) {
        SecretWoodDialog("棋友来敲门啦", { if(!matchResponseSent) { matchResponseSent=true;onMatchResponse(false) } },
            confirmLabel="一起下",onConfirm={if(!matchResponseSent){matchResponseSent=true;onMatchResponse(true)}},
            dismissLabel="这次先不了",busy=matchResponseSent) { Text("${room.pendingMatchName}想和你下一盘五子棋。",style=MaterialTheme.typography.bodyMedium) }
    }
    var rematchResponseSent by remember(mode,room?.round,room?.rematchRequestedBy) { mutableStateOf(false) }
    if(network && room?.rematchRequestedBy!=null && room.rematchRequestedBy!=room.localPlayer && !room.myRematchRequested) {
        SecretWoodDialog("再摆一盘？",{if(!rematchResponseSent){rematchResponseSent=true;onRematchResponse(false)}},
            confirmLabel="好呀，换边再下",onConfirm={if(!rematchResponseSent){rematchResponseSent=true;onRematchResponse(true)}},
            dismissLabel="这次收桌",busy=rematchResponseSent) {Text("棋友想再来一局。这一盘会交换黑白。",style=MaterialTheme.typography.bodySmall)}
    }
    if (network && room != null && !room.connected && (!finished||showRoomEntry)) {
        if (mode == GomokuPlayMode.NEARBY) NearbyChessLobby(nearby, room.status, room.error, onJoin, onNearbyRetry, onControlsBottom)
        else OnlineChessLobby(room.sessionActive, room.busy, room.hostAddress, room.status, room.error,
            onHost, onJoin, onDisconnect, onControlsBottom)
        return
    }
    var undoResponseSent by remember(mode, room?.revision, room?.pendingUndoRequest) { mutableStateOf(false) }
    if (network && room?.pendingUndoRequest != null && room.pendingUndoRequest != room.localPlayer) {
        fun respond(accept: Boolean) { if (!undoResponseSent) { undoResponseSent = true; onUndoResponse(accept) } }
        SecretWoodDialog("棋友想退回一步", { respond(false) }, busy = undoResponseSent,
            confirmLabel = "同意", onConfirm = { respond(true) }, dismissLabel = "继续这局") {
            Text("同意后，两张棋桌会一起回到上一步。", style = MaterialTheme.typography.bodySmall)
        }
    }
    val latestMove by rememberUpdatedState(onMove)
    val canMove = !helpBusy && state.outcome == GomokuOutcome.PLAYING && when (mode) {
        GomokuPlayMode.CPU -> !paused && state.currentPlayer == humanPlayer
        GomokuPlayMode.HOTSEAT -> !paused
        else -> room?.connected == true && !room.awaitingAck && room.pendingUndoRequest == null && state.currentPlayer == localPlayer
    }
    val wood = remember { Brush.linearGradient(listOf(Color(0xFFF2DFB9), Color(0xFFE5C79A))) }
    val status = when (state.outcome) {
        GomokuOutcome.HUMAN_WON,GomokuOutcome.CPU_WON -> finish?.headline?:"本局结束"
        GomokuOutcome.DRAW -> "棋盘坐满啦，这一局平手 ♡"
        GomokuOutcome.PLAYING -> when {
            network && room?.pendingUndoRequest != null -> "等棋友商量这一步…"
            network && room?.awaitingAck == true -> "等另一张棋桌落稳…"
            !network && paused -> "棋局已暂停，棋子都替你留着"
            mode == GomokuPlayMode.CPU && state.currentPlayer != humanPlayer -> "阿噜在想下一步…"
            network -> if (state.currentPlayer == localPlayer) "轮到你了" else "轮到棋友了"
            else -> if (state.currentPlayer == 1) "轮到黑方了" else "轮到白方了"
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
    // Header and mode selector take 92 dp; offset half so the board itself centers on the page.
    val boardTop = ((maxHeight - boardSize) / 2 - 46.dp).coerceAtLeast(60.dp)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    Spacer(Modifier.height((boardTop - 60.dp).coerceAtLeast(0.dp)))
    Row(Modifier.width(boardSize).height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        GameSeat(if(localPlayer==1) "你" else if(mode==GomokuPlayMode.CPU) "阿噜" else if(network) "棋友" else "同伴",
            Color(0xFF4C4950), active = state.currentPlayer == 1 && (network || !paused))
        Spacer(Modifier.weight(1f))
        GameSeat(if(localPlayer==2) "你" else if(mode==GomokuPlayMode.CPU) "阿噜" else if(network) "棋友" else "同伴",
            Color(0xFFF9F5EA), active = state.currentPlayer == 2 && (network || !paused))
    }
    Spacer(Modifier.height(24.dp))
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
            GomokuPlayMode.HOTSEAT -> "同屏双人，${if(humanPlayer==1) "你执黑棋" else "你执白棋"}，黑方先行。"
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
        GameFinishPlate(finish,onRestart,onExit,network,room?.roomEnded==true,room?.resultSecondsLeft?:0,
            room?.myRematchRequested==true,Modifier.width(boardSize).padding(top=10.dp)
                .onGloballyPositioned{onControlsBottom(it.boundsInRoot().bottom)})
    } else {
    Text(status, modifier = Modifier.padding(top = 14.dp), color = Color(0xFF766A7F), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(20.dp))
    Row(Modifier.width(boardSize).padding(horizontal = 34.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!network) GameIconTool(if (paused) Icons.Outlined.PlayCircleOutline else Icons.Outlined.PauseCircleOutline,
            if (paused) "继续" else "暂停", onToggle, Modifier.weight(1f), enabled = state.outcome == GomokuOutcome.PLAYING)
        else GameIconTool(androidx.compose.material.icons.Icons.Outlined.Logout, "离开", onDisconnect, Modifier.weight(1f))
        if(!network) GameIconTool(Icons.Outlined.Refresh, "重开", onRestart, Modifier.weight(1f))
        GameIconTool(Icons.AutoMirrored.Outlined.Undo, "悔棋", onUndo, Modifier.weight(1f),
            enabled = canUndo && !helpBusy && (!network || room?.pendingUndoRequest == null),
            cue = com.jiligulu.app.core.audio.UiCue.UNDO)
    }
    Text("黑棋先行 · 连成五子获胜", Modifier.padding(top = 4.dp, bottom = 10.dp)
        .onGloballyPositioned { onControlsBottom(it.boundsInRoot().bottom) },
        style = MaterialTheme.typography.labelSmall, color = Color(0xFF9C8D98))
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
