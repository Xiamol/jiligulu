package com.jiligulu.app.ui.littleworld

import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiligulu.app.R
import com.jiligulu.app.core.audio.UiSound
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

enum class XiangqiPlayMode { CPU, ONLINE, LAN, HOTSEAT }

@Composable
internal fun ColumnScope.SecretXiangqiGame(state: XiangqiState, mode: XiangqiPlayMode, paused: Boolean,
    boardWidth: Dp, thinkingClock: XiangqiThinkingClock, lan: XiangqiLanUiState, onMode: (XiangqiPlayMode) -> Unit,
    onMove: (XiangqiMove) -> Unit, onToggle: () -> Unit, onRestart: () -> Unit,
    onHost: (String) -> Unit, onJoin: (String) -> Unit, onDisconnect: () -> Unit, onPuzzle:(XiangqiState)->Unit,
    remoteSelection: GridCell? = null, onSelectionChanged: (GridCell?) -> Unit = {},
    helpBusy: Boolean = false, assistedSelection: GridCell? = null,
    restorationToken: Int = 0,
    humanSide: XiangqiSide = XiangqiSide.RED,
    showRoomEntry: Boolean = false,
    canUndo: Boolean = false, onUndo: () -> Unit = {}, onUndoResponse: (Boolean) -> Unit = {},
    nearby: NearbyRoomsState? = null, onNearbyRetry: () -> Unit = {},
    onMatchResponse: (Boolean) -> Unit = {}, onRematchResponse: (Boolean) -> Unit = {}, onExit: () -> Unit = onDisconnect,
    onModalOpened: () -> Unit = {}, onControlsBottom: (Float) -> Unit = {}) {
    val soundContext = LocalContext.current
    val finished = state.outcome!=XiangqiOutcome.PLAYING
    val networkMode = mode == XiangqiPlayMode.LAN || mode == XiangqiPlayMode.ONLINE
    val finish = remember(state,mode,lan.localSide,humanSide) { GameFinishPresenter.xiangqi(state,
        when(mode){XiangqiPlayMode.CPU->humanSide;XiangqiPlayMode.HOTSEAT->null;else->lan.localSide}) }
    var modeMenu by remember { mutableStateOf(false) }
    val modeNames = remember { mapOf(XiangqiPlayMode.CPU to "和阿噜下", XiangqiPlayMode.ONLINE to "创建房间",
        XiangqiPlayMode.LAN to "附近的人", XiangqiPlayMode.HOTSEAT to "同屏双人") }
    Row(Modifier.width(boardWidth).height(42.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(onClick = { UiSound.select(soundContext); onModalOpened(); modeMenu = true }, enabled=!finished, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(modeNames.getValue(mode), color = Color(0xFF766A7F))
                Icon(Icons.Outlined.ExpandMore, "选择对局方式", Modifier.size(18.dp), tint = Color(0xFF928497))
            }
            if(modeMenu) SecretWoodDialog("和谁下？",{modeMenu=false},confirmLabel="返回棋盘") {
                modeNames.forEach { (value,label)->TextButton(onClick={
                    UiSound.select(soundContext);modeMenu=false;if(mode!=value)onMode(value)
                },modifier=Modifier.fillMaxWidth().height(48.dp),colors=ButtonDefaults.textButtonColors(contentColor=SecretWoodInk)) {
                    Text(if(value==mode) "✓ $label" else label)
                } }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(if (state.outcome != XiangqiOutcome.PLAYING) "本局结束" else if (paused && mode != XiangqiPlayMode.ONLINE && mode != XiangqiPlayMode.LAN)
            "已暂停" else if (state.turnSide == XiangqiSide.RED) "红方回合" else "黑方回合",
            style = MaterialTheme.typography.bodySmall,
            color = if (state.turnSide == XiangqiSide.RED) Color(0xFFAF766A) else Color(0xFF766A7F))
    }
    var matchResponseSent by remember(mode,lan.pendingMatchName) {mutableStateOf(false)}
    if(networkMode && lan.pendingMatchName!=null) {
        SecretWoodDialog("棋友来敲门啦",{if(!matchResponseSent){matchResponseSent=true;onMatchResponse(false)}},
            confirmLabel="一起下",onConfirm={if(!matchResponseSent){matchResponseSent=true;onMatchResponse(true)}},
            dismissLabel="这次先不了",busy=matchResponseSent){Text("${lan.pendingMatchName}想和你下一盘象棋。",style=MaterialTheme.typography.bodyMedium)}
    }
    var rematchResponseSent by remember(mode,lan.round,lan.rematchRequestedBy) {mutableStateOf(false)}
    if(networkMode && lan.rematchRequestedBy!=null && lan.rematchRequestedBy!=lan.localSide && !lan.myRematchRequested) {
        SecretWoodDialog("再摆一盘？",{if(!rematchResponseSent){rematchResponseSent=true;onRematchResponse(false)}},
            confirmLabel="好呀，换边再下",onConfirm={if(!rematchResponseSent){rematchResponseSent=true;onRematchResponse(true)}},
            dismissLabel="这次收桌",busy=rematchResponseSent){Text("棋友想再来一局。这一盘会交换红黑。",style=MaterialTheme.typography.bodySmall)}
    }
    var undoResponseSent by remember(mode, lan.revision, lan.pendingUndoRequest) { mutableStateOf(false) }
    if (networkMode && lan.pendingUndoRequest != null && lan.pendingUndoRequest != lan.localSide) {
        fun respond(accept: Boolean) { if (!undoResponseSent) { undoResponseSent = true; onUndoResponse(accept) } }
        SecretWoodDialog("棋友想退回一步", { respond(false) },
            busy = undoResponseSent, confirmLabel = "同意", onConfirm = { respond(true) }, dismissLabel = "继续这局") {
            Text("同意后，两张棋桌会一起回到上一步。", style = MaterialTheme.typography.bodySmall)
        }
    }
    var choosePuzzle by remember {mutableStateOf(false)}
    var showRules by remember { mutableStateOf(false) }
    if (networkMode && !lan.connected && (!finished||showRoomEntry)) {
        if (mode == XiangqiPlayMode.LAN) NearbyChessLobby(nearby, lan.status, lan.error, onJoin, onNearbyRetry, onControlsBottom)
        else OnlineChessLobby(lan.sessionActive, lan.busy, lan.hostAddress, lan.status, lan.error,
            onHost, onJoin, onDisconnect, onControlsBottom)
        return
    }
    if(choosePuzzle) SecretWoodDialog("一着小残局",{choosePuzzle=false}) {
        Text("红方一步取胜。选一种小棋子，试试它的庆祝方式 ♡",style=MaterialTheme.typography.bodySmall)
        XiangqiPuzzles.all.forEach {puzzle-> TextButton(onClick={UiSound.tap(soundContext);choosePuzzle=false;onPuzzle(puzzle.position)}) {Text(puzzle.title)} }
    }
    if (showRules) SecretWoodDialog("棋桌上的小约定", { showRules = false }) {
        Text("先点棋子，再点落点。小圆点是可走的位置，空心圈表示可以吃子；再点一次已选棋子可以取消。",
            style = MaterialTheme.typography.bodyMedium)
        Text("人机和同屏：开局可以设置本步思考秒数，暂停或离开软件会停表。时间到了也可以继续想，不判负。",
            style = MaterialTheme.typography.bodySmall)
        Text("联机棋桌不限时。双方可以看见对方正在选中的棋子，落子后提示自动收好。",
            style = MaterialTheme.typography.bodySmall)
    }
    val playerName = if (state.turnSide == XiangqiSide.RED) "红方" else "黑方"
    val inCheck = remember(state) { state.outcome == XiangqiOutcome.PLAYING && XiangqiEngine.isInCheck(state, state.turnSide) }
    val status = when (state.outcome) {
        XiangqiOutcome.RED_WON -> "红方胜出"
        XiangqiOutcome.BLACK_WON -> "黑方胜出"
        XiangqiOutcome.PLAYING -> when {
            !networkMode && paused -> "棋局已暂停"
            networkMode && lan.awaitingAck -> "正在等另一张棋桌回应…"
            networkMode && lan.pendingUndoRequest != null -> "等棋友商量这一步…"
            !networkMode && thinkingClock.expired && thinkingClock.side == state.turnSide -> "提醒时间到啦，继续慢慢想也可以 ♡"
            mode == XiangqiPlayMode.CPU && state.turnSide != humanSide -> "阿噜在想下一步…"
            inCheck -> "$playerName 被将军了，先保护将帅"
            else -> "轮到${playerName}落子"
        }
    }
    // 需求③：棋子还在路上时，本地再点棋盘不算一步——拦住重复落子。
    // 状态更新在动画的 LaunchedEffect 里（drawscope 拿不到回调，所以提到上一层）。
    var boardAnimating by remember { mutableStateOf(false) }
    val canMove = !helpBusy && !boardAnimating && state.outcome == XiangqiOutcome.PLAYING && when (mode) {
        XiangqiPlayMode.CPU -> !paused && state.turnSide == humanSide
        XiangqiPlayMode.HOTSEAT -> !paused
        XiangqiPlayMode.LAN, XiangqiPlayMode.ONLINE -> lan.connected && !lan.awaitingAck && lan.pendingUndoRequest == null && state.turnSide == lan.localSide
    }
    Spacer(Modifier.height(10.dp))
    XiangqiBoard(state, boardWidth, canMove,
        flipped = mode==XiangqiPlayMode.CPU && humanSide==XiangqiSide.BLACK || networkMode && lan.localSide == XiangqiSide.BLACK, onMove = onMove,
        remoteSelection = remoteSelection, onSelectionChanged = onSelectionChanged, assistedSelection = assistedSelection,
        restorationToken = restorationToken,
        onAnimatingChange = { boardAnimating = it })
    if(finish!=null) {
        GameFinishPlate(finish,onRestart,onExit,networkMode,lan.roomEnded,lan.resultSecondsLeft,lan.myRematchRequested,
            Modifier.width(boardWidth).padding(top=10.dp).onGloballyPositioned{onControlsBottom(it.boundsInRoot().bottom)})
    } else {
    Text(status, modifier = Modifier.padding(vertical = 10.dp).width(boardWidth), color = Color(0xFF766A7F),
        style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Row(Modifier.width(boardWidth).padding(horizontal = 6.dp, vertical = 4.dp)) {
            GameIconTool(Icons.AutoMirrored.Outlined.Undo, "悔棋", onUndo, Modifier.weight(1f),
                enabled = canUndo && !helpBusy && (!networkMode || !lan.awaitingAck && lan.pendingUndoRequest == null))
            if (networkMode) {
                GameIconTool(Icons.Outlined.Logout, "离开棋桌", onDisconnect, Modifier.weight(1f))
            } else {
                GameIconTool(if (paused) Icons.Outlined.PlayCircleOutline else Icons.Outlined.PauseCircleOutline,
                    if (paused) "继续" else "暂停", onToggle, Modifier.weight(1f), enabled = state.outcome == XiangqiOutcome.PLAYING)
                GameIconTool(Icons.Outlined.Refresh, "重开", onRestart, Modifier.weight(1f))
                GameIconTool(Icons.Outlined.Extension, "残局", { onModalOpened(); if (!paused) onToggle(); choosePuzzle = true }, Modifier.weight(1f))
            }
            GameIconTool(Icons.Outlined.HelpOutline, "规则", { onModalOpened(); if (!networkMode && !paused) onToggle(); showRules = true }, Modifier.weight(1f))
        }
    Column(Modifier.onGloballyPositioned { onControlsBottom(it.boundsInRoot().bottom) }, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(if (networkMode) "你执${if(lan.localSide==XiangqiSide.RED)"红" else "黑"} · 联机不限时"
        else "你执${if(humanSide==XiangqiSide.RED)"红" else "黑"} · 每手 ${thinkingClock.durationMillis / 1000} 秒",
        Modifier.padding(top = 8.dp, bottom = 8.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFF9C8D98))
    lan.error?.takeIf { networkMode }?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    }
    }
}

@Composable
internal fun SharedThinkingClock(side: XiangqiSide, seconds: Int, paused: Boolean, network: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.size(52.dp).testTag("xiangqi-shared-clock").clickable(enabled = !network, role = Role.Button, onClick = { UiSound.tap(context); onClick() })
        .semantics { contentDescription = "${if (side == XiangqiSide.RED) "红方" else "黑方"}本步${if (network) "不限时" else if (paused) "已暂停，剩余${seconds}秒" else "剩余${seconds}秒"}" },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawCircle(Color(0xFF897166), radius = size.width * .46f, style = Stroke(1.5.dp.toPx())) }
        Icon(if (paused && !network) Icons.Outlined.PauseCircleOutline else Icons.Outlined.Schedule,
            null, Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(12.dp), tint = Color(0xFF897166))
        Text(if (network) "∞" else seconds.toString(), modifier = Modifier.padding(top = 9.dp), fontSize = 18.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
            color = if (seconds == 0 && !network) Color(0xFFAD786D) else Color(0xFF796758))
    }
}

@Composable
internal fun XiangqiThinkingTimeDialog(initialSeconds: Int, starting: Boolean, onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit) {
    val context = LocalContext.current
    var value by rememberSaveable(initialSeconds, starting) { mutableStateOf(initialSeconds.toString()) }
    var showError by remember { mutableStateOf(false) }
    val seconds = value.toIntOrNull()
    val valid = seconds != null && seconds in XiangqiThinkingClock.MIN_SECONDS..XiangqiThinkingClock.MAX_SECONDS
    SecretWoodDialog(if (starting) "这局思考几秒？" else "调整本步时间", onDismiss,
        confirmLabel = if (starting) "开始对弈" else "应用", onConfirm = {
            if (valid) onConfirm(requireNotNull(seconds)) else showError = true
        }, dismissLabel = "稍后", compactWidth = 270.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(60, 120, 180).forEach { preset ->
                TextButton(onClick = { UiSound.tap(context); value = preset.toString(); showError = false },
                    modifier = Modifier.weight(1f).background(if (seconds == preset) Color(0xFFEFE9F5) else Color.Transparent,
                        RoundedCornerShape(12.dp)), contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("${preset} 秒", fontSize = 12.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(36.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("每手思考",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=SecretWoodInk)
            androidx.compose.foundation.text.BasicTextField(value,{value=it.filter(Char::isDigit).take(3);showError=false},singleLine=true,
                textStyle=MaterialTheme.typography.titleMedium.copy(color=SecretWoodInk,textAlign=androidx.compose.ui.text.style.TextAlign.Center),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                modifier=Modifier.width(58.dp).testTag("xiangqi-thinking-seconds"))
            Text("秒",Modifier.padding(start=5.dp),style=MaterialTheme.typography.bodySmall,color=SecretWoodInk)
        }
        Text(if (showError && !valid) "填 15～600 秒就好。" else if (starting) "每步到时只提醒，不判负。" else "应用后，这一步会重新计时。",
            style = MaterialTheme.typography.bodySmall,
            color = if (showError && !valid) MaterialTheme.colorScheme.error else Color(0xFF9C8D98))
    }
}

@Composable
internal fun GameIconTool(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier,
    enabled: Boolean = true) {
    val context = LocalContext.current
    Column(modifier.height(53.dp).clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = { UiSound.tap(context); onClick() }).padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = Color(0xFF87748E).copy(alpha = if (enabled) 1f else .3f))
        Text(label, fontSize = 10.sp, color = Color(0xFF87748E).copy(alpha = if (enabled) 1f else .3f))
    }
}

@Composable
private fun XiangqiBoard(state: XiangqiState, width: Dp, canMove: Boolean, flipped: Boolean,
    onMove: (XiangqiMove) -> Unit, remoteSelection: GridCell?, onSelectionChanged: (GridCell?) -> Unit,
    assistedSelection: GridCell?, restorationToken: Int,
    onAnimatingChange: (Boolean) -> Unit = {}, onStepSettled: (mover: Int, captured: Boolean) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val typeface = remember(context) { context.resources.getFont(R.font.zcool_kuaile) }
    val wood = remember { Brush.linearGradient(listOf(Color(0xFFF2DFB9), Color(0xFFE5C79A))) }
    val textPaint = remember(typeface) { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textAlign = Paint.Align.CENTER
    } }
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    val latestSelection by rememberUpdatedState(onSelectionChanged)
    fun updateSelection(value: Int) {
        if (value != selected) {
            selected = value
            if (value >= 0) UiSound.pieceSelect(context)
            latestSelection(if (value < 0) null else GridCell(value % 9, value / 9))
        }
    }
    LaunchedEffect(state.board, state.turnSide, canMove) { updateSelection(-1) }
    DisposableEffect(Unit) { onDispose { if (selected >= 0) latestSelection(null) } }
    val selectedCell = assistedSelection ?: if (selected >= 0) GridCell(selected % 9, selected / 9) else null
    val displaySelection = selectedCell?.let { it.y * 9 + it.x } ?: -1
    val legal = remember(state, selectedCell, canMove, assistedSelection) {
        if ((canMove || assistedSelection != null) && selectedCell != null) XiangqiEngine.legalMoves(state, selectedCell) else emptyList()
    }
    val latestState by rememberUpdatedState(state)
    val latestLegal by rememberUpdatedState(legal)
    val latestMove by rememberUpdatedState(onMove)
    val checkPulse = remember { Animatable(0f) }
    val winPulse = remember { Animatable(0f) }
    val finishProof = remember(state) { XiangqiMateClassifier.classify(state) }
    var animationInitialized by remember(restorationToken) { mutableStateOf(false) }
    // 展示层自己留上一帧：被吃棋子只能从这里取，引擎的 state 里已经没有它了。
    var snapshot by remember(restorationToken) { mutableStateOf(XiangqiViewSnapshot(state.board, state.lastMove)) }
    var playing by remember { mutableStateOf<XiangqiStepAnim?>(null) }
    var trailAlpha by remember { mutableStateOf(1f) }
    val travelProgress = remember { Animatable(1f) }
    val landingFlash = remember { Animatable(0f) }
    val latestBusy by rememberUpdatedState(onAnimatingChange)
    val latestSettled by rememberUpdatedState(onStepSettled)
    LaunchedEffect(state.ply, state.outcome, restorationToken) {
        val animate = animationInitialized
        animationInitialized = true
        checkPulse.snapTo(0f)
        winPulse.snapTo(0f)
        if (animate && state.outcome != XiangqiOutcome.PLAYING) {
            winPulse.snapTo(1f); winPulse.animateTo(0f, tween(3200))
        } else if (animate && XiangqiEngine.isInCheck(state, state.turnSide)) {
            checkPulse.snapTo(1f); checkPulse.animateTo(0f, tween(900))
        }
        // 走子演出：首帧、读档、棋盘没变（悔棋/纠正）→ 不演，避免重播旧棋
        val next = XiangqiViewSnapshot(state.board, state.lastMove)
        val step = if (animate) XiangqiMoveAnimator.stepFrom(snapshot, next) else null
        snapshot = next
        if (step != null) {
            playing = step
            latestBusy(true)
            travelProgress.snapTo(0f)
            travelProgress.animateTo(1f, tween(step.durationMillis, easing = FastOutSlowInEasing))
            // 被吃子留在原地缩没，不要跟着移动中的棋子一起走
            if (step.captured != 0) delay(XiangqiMoveAnimator.CAPTURE_FADE_MS.toLong())
            playing = null
            landingFlash.snapTo(1f)
            landingFlash.animateTo(0f, tween(XiangqiMoveAnimator.LANDING_FLASH_MS))
            latestSettled(step.mover, step.captured != 0)
            latestBusy(false)
        }
    }
    // 轨迹不是永久高亮：清晰留一会儿，之后留一道很淡的记号
    LaunchedEffect(state.ply) {
        trailAlpha = 1f
        delay(XiangqiMoveAnimator.TRAIL_HOLD_MS.toLong())
        trailAlpha = 0.28f
    }
    Canvas(Modifier.size(width, width * 1.13f).shadow(3.dp, RoundedCornerShape(13.dp), clip = false)
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
                drawRoundRect(Color(0xFFBD9966).copy(alpha = .6f), cornerRadius = CornerRadius(13.dp.toPx()),
                    style = Stroke(1.dp.toPx()))
            }
        }
        .semantics { contentDescription = "中国象棋棋盘，${if (state.turnSide == XiangqiSide.RED) "红方" else "黑方"}回合，点棋子再点落点。" }
        .pointerInput(canMove, flipped) {
            if (canMove) detectTapGestures { tap ->
                val padding = size.width * .06f
                val stepX = (size.width - padding * 2) / 8
                val stepY = (size.height - padding * 2) / 9
                val viewX = ((tap.x - padding) / stepX).roundToInt()
                val viewY = ((tap.y - padding) / stepY).roundToInt()
                if (viewX !in 0..8 || viewY !in 0..9) return@detectTapGestures
                val cell = XiangqiBoardCoordinates.model(GridCell(viewX,viewY),flipped)
                val move = latestLegal.firstOrNull { it.to == cell }
                if (move != null) { latestMove(move); updateSelection(-1) }
                else {
                    val piece = latestState.pieceAt(cell.x, cell.y)
                    val owns = if (latestState.turnSide == XiangqiSide.RED) piece > 0 else piece < 0
                    val index = cell.y * 9 + cell.x
                    updateSelection(if (owns && index != selected) index else -1)
                }
            }
        }) {
        val padding = size.width * .06f
        val stepX = (size.width - padding * 2) / 8
        val stepY = (size.height - padding * 2) / 9
        val ink = Color(0xFF967553)
        fun position(cell: GridCell): Offset {
            val shown=XiangqiBoardCoordinates.display(cell,flipped)
            return Offset(padding + shown.x * stepX, padding + shown.y * stepY)
        }
        repeat(10) { y -> drawLine(ink, Offset(padding, padding + y * stepY),
            Offset(size.width - padding, padding + y * stepY), 1.dp.toPx()) }
        repeat(9) { x ->
            val px = padding + x * stepX
            if (x == 0 || x == 8) drawLine(ink, Offset(px, padding), Offset(px, size.height - padding), 1.dp.toPx())
            else {
                drawLine(ink, Offset(px, padding), Offset(px, padding + 4 * stepY), 1.dp.toPx())
                drawLine(ink, Offset(px, padding + 5 * stepY), Offset(px, size.height - padding), 1.dp.toPx())
            }
        }
        listOf(0, 7).forEach { y ->
            drawLine(ink, Offset(padding + 3 * stepX, padding + y * stepY),
                Offset(padding + 5 * stepX, padding + (y + 2) * stepY), 1.dp.toPx())
            drawLine(ink, Offset(padding + 5 * stepX, padding + y * stepY),
                Offset(padding + 3 * stepX, padding + (y + 2) * stepY), 1.dp.toPx())
        }
        textPaint.textSize = stepX * .59f
        textPaint.color = android.graphics.Color.rgb(115, 87, 59)
        drawContext.canvas.nativeCanvas.drawText("楚 河", padding + 2 * stepX, padding + 4.5f * stepY + textPaint.textSize * .35f, textPaint)
        drawContext.canvas.nativeCanvas.drawText("汉 界", padding + 6 * stepX, padding + 4.5f * stepY + textPaint.textSize * .35f, textPaint)
        // 需求③：轨迹清晰留 TRAIL_HOLD_MS，之后只留一道很淡的记号——
        // 既不永久高亮抢注意力，也不突然消失让人找不到刚才那一步。
        state.lastMove?.let { move ->
            if (playing == null) {
                val a = trailAlpha
                drawLine(Color(0xFFC1A57E).copy(alpha = .4f * a), position(move.from), position(move.to), 2.dp.toPx())
                drawCircle(Color(0xFFC8A968).copy(alpha = .65f * a), stepX * .18f, position(move.from), style = Stroke(1.5.dp.toPx()))
                drawCircle(Color(0xFFC8A968).copy(alpha = a), stepX * .47f, position(move.to), style = Stroke(2.dp.toPx()))
            }
        }
        // 落稳那一刻在落点闪一下，提示"刚才那一步落在这儿"
        if (landingFlash.value > 0f && state.lastMove != null) {
            val f = landingFlash.value
            drawCircle(Color(0xFFC8A968).copy(alpha = f * .85f),
                stepX * (.47f + (1f - f) * .22f), position(state.lastMove.to), style = Stroke(2.dp.toPx()))
        }
        legal.forEach { move ->
            val capture = state.pieceAt(move.to.x, move.to.y) != 0
            if (capture) drawCircle(Color(0xFFAC765F).copy(alpha = .85f), stepX * .475f,
                position(move.to), style = Stroke(2.5.dp.toPx()))
            else {
                drawCircle(Color(0xFFFFFBF2).copy(alpha = .75f), stepX * .17f, position(move.to))
                drawCircle(Color(0xFF789783).copy(alpha = .9f), stepX * .12f, position(move.to))
            }
        }
        fun fade(c: Color, mul: Float) = c.copy(alpha = c.alpha * mul)

        /**
         * 画一枚棋子。抽成函数是必须的：**移动中的那一枚必须和落定的棋子长得一模一样**，
         * 否则动画结束的瞬间会像"换了个棋子"。`at` / `scale` / `alpha` 让它能同时服务
         * 静态棋子、移动中的棋子和正在缩没的被吃子。
         */
        fun drawPiece(cell: GridCell, piece: Int, at: Offset? = null,
            scale: Float = 1f, alpha: Float = 1f, highlight: Boolean = false) {
            val center = at ?: position(cell)
            val red = piece > 0
            drawCircle(fade(Color(0xFF7B5841), alpha * .2f), stepX * .435f * scale, center + Offset(0f, 2.dp.toPx()))
            drawCircle(Brush.radialGradient(listOf(fade(Color(0xFFFFFAEA), alpha), fade(Color(0xFFEAD0A3), alpha)),
                center = center - Offset(stepX * .14f, stepX * .18f), radius = stepX * .75f),
                stepX * .425f * scale, center)
            drawCircle(fade(if (red) Color(0xFFB76D58) else Color(0xFF6C5A50), alpha),
                stepX * .37f * scale, center, style = Stroke(1.dp.toPx()))
            // 需求③：移动中的棋子提亮一圈，隔着半个棋盘也能一眼看见对方走了哪枚
            if (highlight) drawCircle(fade(Color(0xFF9F87B0), alpha * .5f), stepX * .49f * scale, center,
                style = Stroke(2.dp.toPx()))
            textPaint.color = if (red) android.graphics.Color.rgb(166, 65, 56) else android.graphics.Color.rgb(68, 58, 58)
            textPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
            textPaint.textSize = stepX * .63f * scale
            val glyph = when (abs(piece)) {
                1 -> if (red) "帅" else "将"
                2 -> if (red) "仕" else "士"
                3 -> if (red) "相" else "象"
                4 -> "马"
                5 -> "车"
                6 -> if (red) "炮" else "砲"
                else -> if (red) "兵" else "卒"
            }
            drawContext.canvas.nativeCanvas.drawText(glyph, center.x, center.y - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
            textPaint.alpha = 255
        }

        val anim = playing
        state.board.forEachIndexed { index, piece ->
            if (piece != 0) {
                val cell = GridCell(index % 9, index / 9)
                // 动画期间，落点上的静态棋子先让位——否则会和新落下的那枚重影
                if (anim != null && cell == anim.to) return@forEachIndexed
                val center = position(cell)
                drawPiece(cell, piece, highlight = anim != null && cell == anim.from)
                if (index == displaySelection) {
                    drawCircle(Color(0xFF9F87B0).copy(alpha = .2f), stepX * .49f, center)
                    drawCircle(Color(0xFF8E70A2), stepX * .46f, center, style = Stroke(2.5.dp.toPx()))
                    val bracket = stepX * .55f
                    listOf(Offset(-1f, -1f), Offset(1f, -1f), Offset(-1f, 1f), Offset(1f, 1f)).forEach { corner ->
                        val edge = center + corner * bracket
                        drawLine(Color(0xFF8E70A2), edge, edge - Offset(corner.x * stepX * .2f, 0f), 2.dp.toPx())
                        drawLine(Color(0xFF8E70A2), edge, edge - Offset(0f, corner.y * stepX * .2f), 2.dp.toPx())
                    }
                }
            }

        // 演出中的那一步：移动中的棋子、被吃子的缩没、起点空心标记与克制的行进箭头
        if (anim != null) {
            val raw = travelProgress.value
            val t = XiangqiMoveAnimator.travel(raw)
            val from = position(anim.from)
            val to = position(anim.to)
            val moving = Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
            // 起点空心标记：让人看见"它是从哪儿出发的"
            drawCircle(fade(Color(0xFF8E70A2), .55f), stepX * .3f, from, style = Stroke(1.5.dp.toPx()))
            if (t > .04f) {
                val delta = moving - from
                val len = kotlin.math.hypot(delta.x, delta.y)
                if (len > stepX * .3f) {
                    drawLine(fade(Color(0xFF8E70A2), .38f), from, moving, 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                    val ux = delta.x / len; val uy = delta.y / len
                    val wing = stepX * .13f
                    val perpX = -uy * wing * .6f; val perpY = ux * wing * .6f
                    drawLine(fade(Color(0xFF8E70A2), .5f), moving,
                        moving - Offset(ux * wing - perpX, uy * wing - perpY), 1.5.dp.toPx())
                    drawLine(fade(Color(0xFF8E70A2), .5f), moving,
                        moving - Offset(ux * wing + perpX, uy * wing + perpY), 1.5.dp.toPx())
                }
            }
            drawPiece(anim.to, anim.mover, at = moving, highlight = true)
            // 被吃子一直停在落点上，直到移动的棋子撞上它才缩没
            if (anim.captured != 0) {
                val f = XiangqiMoveAnimator.captureFade(raw)
                if (f > 0f) drawPiece(anim.to, anim.captured, at = to, scale = 1f - f * .45f, alpha = 1f - f)
            }
        }
        }
        remoteSelection?.takeIf { it.x in 0..8 && it.y in 0..9 }?.let { cell ->
            drawCircle(Color(0xFF688F88), stepX * .48f, position(cell),
                style = Stroke(2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))))
        }
        if (checkPulse.value > 0f) {
            val general = state.board.indexOf(state.turnSide.sign * XiangqiEngine.GENERAL)
            if (general >= 0) drawCircle(Color(0xFFC06056).copy(alpha = checkPulse.value * .7f),
                stepX * (.47f + (1f - checkPulse.value) * .28f), position(GridCell(general % 9, general / 9)),
                style = Stroke(2.5.dp.toPx()))
        }
        if (winPulse.value > 0f && state.lastMove != null) {
            val finalMove = state.lastMove
            val center = position(finalMove.to)
            val piece = abs(state.pieceAt(finalMove.to.x, finalMove.to.y))
            val phase = 1f - winPulse.value
            val gold = Color(0xFFD5A455).copy(alpha = winPulse.value * .8f)
            val proof=finishProof
            if(proof?.family==XiangqiFinishFamily.DOUBLE_CANNON) {
                val cannons=state.board.indices.filter{state.board[it]==proof.winner.sign*XiangqiEngine.CANNON}
                    .map{GridCell(it%9,it/9)}
                cannons.forEach { cell->repeat(2){ring->drawCircle(gold,stepX*(.5f+phase*(ring+1)*.7f),position(cell),style=Stroke(2.dp.toPx()))} }
                if(cannons.size==2)drawLine(gold,position(cannons[0]),position(cannons[1]),3.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(),3.dp.toPx())))
            } else if(proof?.family==XiangqiFinishFamily.SMOTHERED_CANNON||proof?.family==XiangqiFinishFamily.STALEMATE) {
                val king=state.board.indexOf(proof.winner.opponent.sign*XiangqiEngine.GENERAL)
                if(king>=0) {val p=position(GridCell(king%9,king/9));drawRoundRect(gold,p-Offset(stepX*.55f,stepY*.55f),
                    androidx.compose.ui.geometry.Size(stepX*1.1f,stepY*1.1f),CornerRadius(5.dp.toPx()),style=Stroke(2.dp.toPx()))}
                proof.checkingCells.forEach{cell->drawCircle(gold,stepX*(.45f+phase),position(cell),style=Stroke(2.dp.toPx()))}
            }
            when (piece) {
                XiangqiEngine.ROOK -> {
                    drawLine(gold, Offset(padding, center.y), Offset(size.width - padding, center.y), 3.dp.toPx())
                    drawLine(gold, Offset(center.x, padding), Offset(center.x, size.height - padding), 3.dp.toPx())
                }
                XiangqiEngine.CANNON -> repeat(3) { ring ->
                    drawCircle(gold, stepX * (.6f + phase * (ring + 1) * .75f), center, style = Stroke(2.dp.toPx()))
                }
                XiangqiEngine.HORSE -> listOf(Offset(-2f, -1f), Offset(-1f, -2f), Offset(1f, -2f), Offset(2f, -1f),
                    Offset(-2f, 1f), Offset(-1f, 2f), Offset(1f, 2f), Offset(2f, 1f)).forEach { step ->
                    drawCircle(gold, stepX * .14f, center + step * (stepX * (.25f + phase * .8f)))
                }
                XiangqiEngine.PAWN -> repeat(7) { i ->
                    val point = center + Offset((i - 3) * stepX * phase * .42f, -stepY * (phase * 2 + i % 2 * .3f))
                    drawCircle(gold, stepX * .11f, point)
                }
                else -> repeat(8) { i ->
                    val angle = i * Math.PI / 4
                    val point = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (stepX * (.6f + phase * 1.8f))
                    drawLine(gold, point - Offset(3.dp.toPx(), 0f), point + Offset(3.dp.toPx(), 0f), 2.dp.toPx())
                    drawLine(gold, point - Offset(0f, 3.dp.toPx()), point + Offset(0f, 3.dp.toPx()), 2.dp.toPx())
                }
            }
        }
    }
}
