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
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Handshake
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
import com.jiligulu.app.core.audio.UiCue
import com.jiligulu.app.core.audio.UiSound
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

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
    showRoomEntry: Boolean = false, playerProfile: ChessPlayerProfile = ChessPlayerProfile(),
    canUndo: Boolean = false, onUndo: () -> Unit = {}, onUndoResponse: (Boolean) -> Unit = {},
    canUndoRed: Boolean = false, canUndoBlack: Boolean = false, onHotseatUndo: (XiangqiSide) -> Unit = {},
    nearby: NearbyRoomsState? = null, onNearbyRetry: () -> Unit = {},
    onMatchResponse: (Boolean) -> Unit = {}, onRematchResponse: (Boolean) -> Unit = {}, onExit: () -> Unit = onDisconnect,
    onResign: () -> Unit = {},
    onDraw: () -> Unit = {}, onDrawResponse: (Boolean) -> Unit = {}, onCancelDraw: () -> Unit = {},
    onModalOpened: () -> Unit = {}, onControlsBottom: (Float) -> Unit = {},
    presentationEpoch: Int = 0,
    onPresentationSkipped: (epoch: Int, position: XiangqiState) -> Unit = { _, _ -> },
    onMoveSettled: (epoch: Int, settledState: XiangqiState, captured: Boolean) -> Unit = { _, _, _ -> }) {
    val soundContext = LocalContext.current
    val finished = state.outcome!=XiangqiOutcome.PLAYING
    val networkMode = mode == XiangqiPlayMode.LAN || mode == XiangqiPlayMode.ONLINE
    val finish = remember(state, mode, lan.localSide, humanSide, lan.resignedBy) {
        if(networkMode&&finished&&lan.agreedDraw) {
            GameFinishPresentation("握手言和","这局平手，下次再战 ♡",FinishMood.DRAW,"和棋")
        } else if (networkMode && finished && lan.resignedBy != null) {
            val lost = lan.resignedBy == lan.localSide
            GameFinishPresentation(if (lost) "这局先让一步" else "你赢啦", if (lost) "认输也可以，再下一盘吧" else "棋友认输，这一局收好啦",
                if (lost) FinishMood.LOSE else FinishMood.WIN, if (lost) "认输" else "胜出")
        } else GameFinishPresenter.xiangqi(state,
            when(mode){XiangqiPlayMode.CPU->humanSide;XiangqiPlayMode.HOTSEAT->null;else->lan.localSide})
    }
    val presentationKey = XiangqiPresentationKey(presentationEpoch, restorationToken, mode.ordinal,
        if (networkMode) lan.round else 0, if (networkMode) lan.hostAddress else "",
        networkMode && lan.connected, if (networkMode) lan.localSide else humanSide)
    // Restored terminal positions show their result immediately. Fresh wins are released only by
    // the board's completion event, tied to this exact round and immutable terminal position.
    var revealedFinish by remember(presentationKey) {
        mutableStateOf(state.takeIf { it.outcome != XiangqiOutcome.PLAYING })
    }
    var finishEvent by remember(presentationKey) { mutableIntStateOf(0) }
    var promptRequest by remember(presentationKey) { mutableIntStateOf(0) }
    LaunchedEffect(state, presentationKey, lan.resignedBy) {
        if (state.outcome == XiangqiOutcome.PLAYING) revealedFinish = null
        else if (networkMode && (lan.resignedBy != null || lan.agreedDraw) && revealedFinish != state) {
            // Resigning changes no piece position, so it has no move animation to release the result.
            finishEvent++; revealedFinish = state
        }
    }
    val finishRevealed = revealedFinish == state
    var resignConfirm by remember(mode, lan.round) { mutableStateOf(false) }
    var matchResponseSent by remember(mode,lan.pendingMatchName) {mutableStateOf(false)}
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var wasConnected by remember(mode) { mutableStateOf(lan.connected) }
    LaunchedEffect(mode, lan.connected) {
        val newlyConnected = !wasConnected && lan.connected
        wasConnected = lan.connected
        if (networkMode && newlyConnected && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) UiSound.match(soundContext)
    }
    if(networkMode && lan.pendingMatchName!=null) {
        SecretWoodDialog("棋友来敲门啦",{if(!matchResponseSent){matchResponseSent=true;onMatchResponse(false)}},
            confirmLabel="一起下",onConfirm={if(!matchResponseSent){matchResponseSent=true;onMatchResponse(true)}},
            dismissLabel="这次先不了",busy=matchResponseSent){Text("${lan.pendingMatchName}想和你下一盘象棋。",style=MaterialTheme.typography.bodyMedium)}
    }
    var rematchResponseSent by remember(mode,lan.round,lan.rematchRequestedBy) {mutableStateOf(false)}
    if(networkMode && lan.rematchRequestedBy!=null && lan.rematchRequestedBy!=lan.localSide && !lan.myRematchRequested) {
        SecretWoodDialog("再摆一盘？",{if(!rematchResponseSent){rematchResponseSent=true;onRematchResponse(false)}},
            confirmLabel="换边再下",onConfirm={if(!rematchResponseSent){rematchResponseSent=true;onRematchResponse(true)}},
            dismissLabel="收桌",busy=rematchResponseSent,compactWidth=292.dp){ }
    }
    var undoResponseSent by remember(mode, lan.revision, lan.pendingUndoRequest) { mutableStateOf(false) }
    if (networkMode && lan.pendingUndoRequest != null && lan.pendingUndoRequest != lan.localSide) {
        fun respond(accept: Boolean) { if (!undoResponseSent) { undoResponseSent = true; onUndoResponse(accept) } }
        SecretWoodDialog("棋友想重走这一手", { respond(false) },
            busy = undoResponseSent, confirmLabel = "同意", onConfirm = { respond(true) }, dismissLabel = "继续下", compactWidth = 292.dp) { }
    }
    var choosePuzzle by remember {mutableStateOf(false)}
    var showRules by remember { mutableStateOf(false) }
    if (networkMode && !lan.connected && !lan.reconnecting && !lan.peerLeft && (!finished||showRoomEntry)) {
        if (mode == XiangqiPlayMode.LAN) NearbyChessLobby(nearby, lan.status, lan.error, onJoin, onNearbyRetry, onControlsBottom)
        else OnlineChessLobby(lan.sessionActive, lan.busy, lan.hostAddress, lan.status, lan.error,
            onHost, onJoin, onDisconnect, onControlsBottom, game = "xiangqi")
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
    GameFinishOverlay(
        result = finish.takeIf { finishRevealed }, roundIdentity = presentationKey,
        terminalIdentity = state.takeIf { finishRevealed && finished }, freshEvent = finishEvent,
        promptRequest = promptRequest, onAgain = onRestart, onExit = onExit,
        myRematchRequested = lan.myRematchRequested,
        suppressPrompt = networkMode && lan.rematchRequestedBy != null &&
            lan.rematchRequestedBy != lan.localSide && !lan.myRematchRequested,
    )
    var drawResponseSent by remember(mode,lan.round,lan.revision,lan.pendingDrawRequest,lan.pendingDrawId){mutableStateOf(false)}
    if(networkMode&&lan.pendingDrawRequest!=null&&lan.pendingDrawRequest!=lan.localSide) {
        fun respondDraw(accept:Boolean){if(!drawResponseSent){drawResponseSent=true;onDrawResponse(accept)}}
        SecretWoodDialog("棋友想和棋",{respondDraw(false)},confirmLabel="同意",onConfirm={respondDraw(true)},
            dismissLabel="继续下",busy=drawResponseSent,compactWidth=270.dp) { }
    }
    val playerName = if (state.turnSide == XiangqiSide.RED) "红方" else "黑方"
    val inCheck = remember(state) { state.outcome == XiangqiOutcome.PLAYING && XiangqiEngine.isInCheck(state, state.turnSide) }
    var peerDepartureDismissed by remember(mode, lan.round, lan.hostAddress) { mutableStateOf(false) }
    if (networkMode && lan.peerLeft && !peerDepartureDismissed) SecretWoodDialog("棋友已离开", { peerDepartureDismissed = true },
        confirmLabel = "知道啦", compactWidth = 270.dp) { }
    val openingIdentity = listOf(mode, restorationToken, humanSide, lan.round, if (networkMode) lan.hostAddress else "")
    ChessRoundStart(openingIdentity, ready = if (networkMode) lan.connected else !paused,
        emptyBoard = state.ply == 0, text = if (mode == XiangqiPlayMode.HOTSEAT) "红方先行"
            else if ((if (networkMode) lan.localSide else humanSide) == XiangqiSide.RED) "你先行" else "对方先行")
    val status = when (state.outcome) {
        XiangqiOutcome.RED_WON -> "红方胜出"
        XiangqiOutcome.BLACK_WON -> "黑方胜出"
        XiangqiOutcome.DRAW -> "双方同意和棋"
        XiangqiOutcome.PLAYING -> when {
            networkMode && lan.reconnecting -> "棋局还在，正在重新连接…"
            networkMode && lan.remoteBackground -> "棋友暂时离开，棋局替你们留着"
            !networkMode && paused -> "棋局已暂停"
            networkMode && lan.awaitingAck -> "正在等另一张棋桌回应…"
            networkMode && lan.pendingUndoRequest != null -> "等棋友商量这一步…"
            networkMode && lan.pendingDrawRequest != null -> "等待棋友回应和棋"
            !networkMode && thinkingClock.expired && thinkingClock.side == state.turnSide -> "提醒时间到啦，继续慢慢想也可以 ♡"
            mode == XiangqiPlayMode.CPU && state.turnSide != humanSide -> "阿噜在想下一步…"
            inCheck -> "$playerName 被将军了，先保护将帅"
            else -> "轮到${playerName}落子"
        }
    }
    // 需求③：棋子还在路上时，本地再点棋盘不算一步——拦住重复落子。
    // 状态更新在动画的 LaunchedEffect 里（drawscope 拿不到回调，所以提到上一层）。
    var boardAnimating by remember(presentationKey) { mutableStateOf(false) }
    val roomAvailable = lan.connected && !lan.awaitingAck && !lan.localBackground && !lan.remoteBackground &&
        !lan.reconnecting && lan.pendingUndoRequest == null && lan.pendingDrawRequest==null
    val canMove = !helpBusy && !boardAnimating && state.outcome == XiangqiOutcome.PLAYING && when (mode) {
        XiangqiPlayMode.CPU -> !paused && state.turnSide == humanSide
        XiangqiPlayMode.HOTSEAT -> !paused
        XiangqiPlayMode.LAN, XiangqiPlayMode.ONLINE -> roomAvailable && state.turnSide == lan.localSide
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.width(boardWidth).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        val localSide = if (networkMode) lan.localSide else humanSide
        val opponent = if (mode == XiangqiPlayMode.CPU) ChessPlayerProfile("阿噜", "aru") else ChessPlayerProfile(lan.remoteName, lan.remoteAvatarId)
        fun profile(side: XiangqiSide) = if (mode == XiangqiPlayMode.HOTSEAT)
            ChessPlayerProfile(if (side == XiangqiSide.RED) playerProfile.name else "棋友", if (side == XiangqiSide.RED) playerProfile.avatarId else "cat")
            else if (side == localSide) playerProfile else opponent
        fun seatStatus(side: XiangqiSide) = when { finished -> "结束"; networkMode && lan.peerLeft && side != localSide -> "已离开"
            networkMode && lan.remoteBackground && side != localSide -> "暂离"; !networkMode && paused -> "暂停"
            state.turnSide == side && inCheck -> "被将军"; state.turnSide == side -> if (mode == XiangqiPlayMode.CPU && side != humanSide) "思考中" else "落子中"; else -> "等待" }
        ChessPlayerSeat(profile(XiangqiSide.RED), Color(0xFFAF766A), "红方", state.turnSide == XiangqiSide.RED && !finished,
            seatStatus(XiangqiSide.RED), Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        ChessPlayerSeat(profile(XiangqiSide.BLACK), Color(0xFF514953), "黑方", state.turnSide == XiangqiSide.BLACK && !finished,
            seatStatus(XiangqiSide.BLACK), Modifier.weight(1f), alignEnd = true)
    }
    Spacer(Modifier.height(14.dp))
    XiangqiBoard(state, boardWidth, canMove,
        flipped = mode==XiangqiPlayMode.CPU && humanSide==XiangqiSide.BLACK || networkMode && lan.localSide == XiangqiSide.BLACK, onMove = onMove,
        remoteSelection = remoteSelection, onSelectionChanged = onSelectionChanged, assistedSelection = assistedSelection,
        presentationKey = presentationKey,
        onAnimatingChange = { key, busy -> if (key == presentationKey) boardAnimating = busy },
        onStepSettled = { key, position, captured ->
            if (key == presentationKey) onMoveSettled(key.epoch, position, captured)
        },
        onFinishReady = { key, position, fresh ->
            if (key == presentationKey && position == state) {
                if (fresh && revealedFinish != position) finishEvent++
                revealedFinish = position
            }
        },
        onPresentationSkipped = { key, position ->
            if (key == presentationKey) onPresentationSkipped(key.epoch, position)
        })
    if(finish!=null && finishRevealed) {
        GameFinishActions(finish,{ promptRequest++ },onExit,networkMode,lan.roomEnded,lan.resultSecondsLeft,lan.myRematchRequested,
            Modifier.width(boardWidth).padding(top=10.dp).onGloballyPositioned{onControlsBottom(it.boundsInRoot().bottom)})
    } else {
    Spacer(Modifier.height(16.dp))
        Row(Modifier.width(boardWidth).padding(horizontal = 6.dp, vertical = 4.dp)) {
            if (mode == XiangqiPlayMode.HOTSEAT) {
                GameIconTool(Icons.AutoMirrored.Outlined.Undo, "悔红", { onHotseatUndo(XiangqiSide.RED) }, Modifier.weight(1f),
                    enabled = canUndoRed && !helpBusy, tint = Color(0xFFAF766A))
                GameIconTool(Icons.AutoMirrored.Outlined.Undo, "悔黑", { onHotseatUndo(XiangqiSide.BLACK) }, Modifier.weight(1f),
                    enabled = canUndoBlack && !helpBusy, tint = Color(0xFF625B67))
            } else GameIconTool(Icons.AutoMirrored.Outlined.Undo, "悔棋", onUndo, Modifier.weight(1f),
                enabled = canUndo && !helpBusy && (!networkMode || roomAvailable),
                cue = UiCue.TOUCH)
            if (networkMode) {
                GameIconTool(Icons.Outlined.Handshake, if(lan.myDrawRequested)"取消和棋"else"和棋",
                    if(lan.myDrawRequested)onCancelDraw else onDraw, Modifier.weight(1f),
                    enabled = roomAvailable || lan.myDrawRequested)
                GameIconTool(Icons.Outlined.Logout, "离开", onDisconnect, Modifier.weight(1f))
            } else {
                GameIconTool(if (paused) Icons.Outlined.PlayCircleOutline else Icons.Outlined.PauseCircleOutline,
                    if (paused) "继续" else "暂停", onToggle, Modifier.weight(1f), enabled = state.outcome == XiangqiOutcome.PLAYING)
                GameIconTool(Icons.Outlined.Refresh, "重开", onRestart, Modifier.weight(1f))
                if (mode == XiangqiPlayMode.CPU) GameIconTool(Icons.Outlined.Extension, "残局", {
                    onModalOpened(); if (!paused) onToggle(); choosePuzzle = true }, Modifier.weight(1f))
            }
            GameIconTool(Icons.Outlined.HelpOutline, "规则", { onModalOpened(); if (!networkMode && !paused) onToggle(); showRules = true }, Modifier.weight(1f))
        }
    Column(Modifier.onGloballyPositioned { onControlsBottom(it.boundsInRoot().bottom) }, horizontalAlignment = Alignment.CenterHorizontally) {
    Spacer(Modifier.height(8.dp))
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
    enabled: Boolean = true, cue: UiCue = UiCue.TOUCH, tint: Color = Color(0xFF87748E)) {
    val context = LocalContext.current
    Column(modifier.height(53.dp).clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = { UiSound.play(context, cue); onClick() }).padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint.copy(alpha = if (enabled) 1f else .3f))
        Text(label, fontSize = 11.sp, color = tint.copy(alpha = if (enabled) 1f else .3f))
    }
}

@Composable
internal fun GameResignDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    SecretWoodDialog("这局先认输？", onDismiss, confirmLabel = "认输", onConfirm = onConfirm,
        dismissLabel = "接着下", compactWidth = 270.dp) { }
}

@Composable
private fun XiangqiBoard(state: XiangqiState, width: Dp, canMove: Boolean, flipped: Boolean,
    onMove: (XiangqiMove) -> Unit, remoteSelection: GridCell?, onSelectionChanged: (GridCell?) -> Unit,
    assistedSelection: GridCell?, presentationKey: XiangqiPresentationKey,
    onAnimatingChange: (XiangqiPresentationKey, Boolean) -> Unit,
    onStepSettled: (XiangqiPresentationKey, XiangqiState, Boolean) -> Unit,
    onFinishReady: (XiangqiPresentationKey, XiangqiState, Boolean) -> Unit,
    onPresentationSkipped: (XiangqiPresentationKey, XiangqiState) -> Unit) {
    val context = LocalContext.current
    val typeface = remember(context) { context.resources.getFont(R.font.zcool_kuaile) }
    val wood = remember { Brush.linearGradient(listOf(Color(0xFFF0E3CD), Color(0xFFE8D5B4))) }
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
    val queue = remember(presentationKey) {
        XiangqiMoveQueue(XiangqiViewSnapshot.of(state, presentationKey))
    }
    val wake = remember(queue) { Channel<Unit>(Channel.CONFLATED) }
    var displayed by remember(queue) { mutableStateOf(state) }
    var playing by remember(queue) { mutableStateOf<XiangqiStepAnim?>(null) }
    var landingCell by remember(queue) { mutableStateOf<GridCell?>(null) }
    var checkPosition by remember(queue) { mutableStateOf<XiangqiState?>(null) }
    var finishingPosition by remember(queue) { mutableStateOf<XiangqiState?>(null) }
    var trailAlpha by remember(queue) { mutableStateOf(1f) }
    var motionJob by remember(queue) { mutableStateOf<Job?>(null) }
    var flashJob by remember(queue) { mutableStateOf<Job?>(null) }
    var checkJob by remember(queue) { mutableStateOf<Job?>(null) }
    val travelProgress = remember(queue) { Animatable(1f) }
    val captureProgress = remember(queue) { Animatable(1f) }
    val landingFlash = remember(queue) { Animatable(0f) }
    val checkPulse = remember(queue) { Animatable(0f) }
    val winPulse = remember(queue) { Animatable(0f) }
    val finishProof = remember(displayed) { XiangqiMateClassifier.classify(displayed) }
    val latestIdentity by rememberUpdatedState(presentationKey)
    val latestBusy by rememberUpdatedState(onAnimatingChange)
    val latestSettled by rememberUpdatedState(onStepSettled)
    val latestFinishReady by rememberUpdatedState(onFinishReady)
    val latestSkipped by rememberUpdatedState(onPresentationSkipped)

    DisposableEffect(queue) {
        onDispose {
            queue.invalidate()
            motionJob?.cancel(); flashJob?.cancel(); checkJob?.cancel()
            wake.close()
            latestBusy(presentationKey, false)
        }
    }
    // A new authoritative snapshot only appends to the queue. It does not cancel a legal
    // preceding move in flight; undo/correction/overflow instead resets the whole presentation.
    LaunchedEffect(state, queue) {
        when (queue.offer(XiangqiViewSnapshot.of(state, presentationKey))) {
            XiangqiQueueUpdate.UNCHANGED -> Unit
            XiangqiQueueUpdate.ENQUEUED -> {
                latestBusy(presentationKey, true)
                wake.trySend(Unit)
            }
            XiangqiQueueUpdate.RESET -> {
                motionJob?.cancel(); flashJob?.cancel(); checkJob?.cancel()
                playing = null; landingCell = null; checkPosition = null; finishingPosition = null
                displayed = state
                latestBusy(presentationKey, false)
                if (state.outcome != XiangqiOutcome.PLAYING) latestFinishReady(presentationKey, state, false)
                latestSkipped(presentationKey, state)
            }
        }
    }
    LaunchedEffect(queue) {
        val presentationScope = this
        for (signal in wake) {
            while (true) {
                val entry = queue.take() ?: break
                val step = entry.step
                fun owns() = queue.owns(entry) && latestIdentity == entry.identity
                val job = launch {
                    try {
                        if (!owns()) return@launch
                        flashJob?.cancel(); checkJob?.cancel()
                        landingCell = null; checkPosition = null; finishingPosition = null
                        landingFlash.snapTo(0f); checkPulse.snapTo(0f)
                        winPulse.snapTo(0f)
                        travelProgress.snapTo(0f); captureProgress.snapTo(0f)
                        if (!owns()) return@launch
                        displayed = step.after
                        playing = step
                        latestBusy(entry.identity, true)
                        travelProgress.animateTo(1f, tween(step.durationMillis, easing = FastOutSlowInEasing))
                        if (!owns()) return@launch
                        // Arrival is the sound event. Capture fading and landing light happen later.
                        latestSettled(entry.identity, step.after, step.captured != 0)
                        landingCell = step.to
                        landingFlash.snapTo(1f)
                        val flash = presentationScope.launch {
                            landingFlash.animateTo(0f, tween(XiangqiMoveAnimator.LANDING_FLASH_MS))
                            if (owns() && landingCell == step.to) landingCell = null
                        }
                        flashJob = flash
                        if (step.captured != 0) {
                            captureProgress.animateTo(1f, tween(XiangqiMoveAnimator.CAPTURE_FADE_MS))
                        }
                        if (!owns()) return@launch
                        playing = null
                        latestBusy(entry.identity, queue.hasPending)
                        if (step.after.outcome != XiangqiOutcome.PLAYING) {
                            finishingPosition = step.after
                            // The centered watermark and brief board proof begin together once
                            // the piece has arrived and the victim's 100 ms fade has completed.
                            latestFinishReady(entry.identity, step.after, true)
                            winPulse.snapTo(1f)
                            winPulse.animateTo(0f, tween(GAME_FINISH_BOARD_EFFECT_MS))
                            if (!owns()) return@launch
                            finishingPosition = null
                        } else if (XiangqiEngine.isInCheck(step.after, step.after.turnSide)) {
                            checkPosition = step.after
                            checkJob = presentationScope.launch {
                                checkPulse.snapTo(1f)
                                checkPulse.animateTo(0f, tween(900))
                                if (owns()) checkPosition = null
                            }
                        }
                    } finally {
                        // An invalidated job cannot clear the next generation's busy state or art.
                        if (owns()) {
                            playing = null
                            latestBusy(entry.identity, queue.hasPending)
                        }
                    }
                }
                motionJob = job
                job.join()
                if (motionJob === job) motionJob = null
            }
        }
    }
    LaunchedEffect(displayed, playing, queue) {
        trailAlpha = 1f
        if (playing == null) {
            delay(XiangqiMoveAnimator.TRAIL_HOLD_MS.toLong())
            trailAlpha = 0.28f
        }
    }
    val shown = displayed
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
        .testTag("xiangqi-board")
        .semantics { contentDescription = "中国象棋棋盘，${if (shown.turnSide == XiangqiSide.RED) "红方" else "黑方"}回合，点棋子再点落点。" }
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
        shown.lastMove?.let { move ->
            if (playing == null) {
                val a = trailAlpha
                drawLine(Color(0xFFC1A57E).copy(alpha = .4f * a), position(move.from), position(move.to), 2.dp.toPx())
                drawCircle(Color(0xFFC8A968).copy(alpha = .65f * a), stepX * .18f, position(move.from), style = Stroke(1.5.dp.toPx()))
                drawCircle(Color(0xFFC8A968).copy(alpha = a), stepX * .47f, position(move.to), style = Stroke(2.dp.toPx()))
            }
        }
        // 落稳那一刻在落点闪一下，提示"刚才那一步落在这儿"
        val landing = landingCell
        if (landingFlash.value > 0f && landing != null) {
            val f = landingFlash.value
            drawCircle(Color(0xFFC8A968).copy(alpha = f * .85f),
                stepX * (.47f + (1f - f) * .22f), position(landing), style = Stroke(2.dp.toPx()))
        }
        legal.forEach { move ->
            val capture = shown.pieceAt(move.to.x, move.to.y) != 0
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
            // 红黑各用一套字面。双方都写「车」时，扫一眼棋盘分不清是谁的子——
            // 传统象棋本来就是红方一套、黑方一套，这里沿用：红方简体，黑方繁体。
            val glyph = when (abs(piece)) {
                1 -> if (red) "帅" else "將"
                2 -> if (red) "仕" else "士"
                3 -> if (red) "相" else "象"
                4 -> if (red) "马" else "馬"
                5 -> if (red) "车" else "車"
                6 -> if (red) "炮" else "砲"
                else -> if (red) "兵" else "卒"
            }
            drawContext.canvas.nativeCanvas.drawText(glyph, center.x, center.y - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
            textPaint.alpha = 255
        }

        val anim = playing
        shown.board.forEachIndexed { index, piece ->
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
        }

        // 演出中的那一步：移动中的棋子、被吃子的缩没、起点空心标记与克制的行进箭头
        if (anim != null) {
            val t = travelProgress.value
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
                val f = captureProgress.value
                if (f < 1f) drawPiece(anim.to, anim.captured, at = to, scale = 1f - f * .45f, alpha = 1f - f)
            }
        }
        remoteSelection?.takeIf { it.x in 0..8 && it.y in 0..9 }?.let { cell ->
            drawCircle(Color(0xFF688F88), stepX * .48f, position(cell),
                style = Stroke(2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))))
        }
        if (checkPosition == shown && checkPulse.value > 0f) {
            val general = shown.board.indexOf(shown.turnSide.sign * XiangqiEngine.GENERAL)
            if (general >= 0) drawCircle(Color(0xFFC06056).copy(alpha = checkPulse.value * .7f),
                stepX * (.47f + (1f - checkPulse.value) * .28f), position(GridCell(general % 9, general / 9)),
                style = Stroke(2.5.dp.toPx()))
        }
        if (finishingPosition == shown && winPulse.value > 0f && shown.lastMove != null) {
            val finalMove = shown.lastMove
            val center = position(finalMove.to)
            val piece = abs(shown.pieceAt(finalMove.to.x, finalMove.to.y))
            val phase = 1f - winPulse.value
            val gold = Color(0xFFD5A455).copy(alpha = winPulse.value * .8f)
            val proof=finishProof
            if(proof?.family==XiangqiFinishFamily.DOUBLE_CANNON) {
                val cannons=shown.board.indices.filter{shown.board[it]==proof.winner.sign*XiangqiEngine.CANNON}
                    .map{GridCell(it%9,it/9)}
                cannons.forEach { cell->repeat(2){ring->drawCircle(gold,stepX*(.5f+phase*(ring+1)*.7f),position(cell),style=Stroke(2.dp.toPx()))} }
                if(cannons.size==2)drawLine(gold,position(cannons[0]),position(cannons[1]),3.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(),3.dp.toPx())))
            } else if(proof?.family==XiangqiFinishFamily.SMOTHERED_CANNON||proof?.family==XiangqiFinishFamily.STALEMATE) {
                val king=shown.board.indexOf(proof.winner.opponent.sign*XiangqiEngine.GENERAL)
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
