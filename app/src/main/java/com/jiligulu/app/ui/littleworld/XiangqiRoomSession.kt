package com.jiligulu.app.ui.littleworld

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
open class XiangqiRoomSession protected constructor(private val context: Context?, private val online: Boolean) {
    private val mutable = MutableStateFlow(XiangqiLanUiState())
    val state: StateFlow<XiangqiLanUiState> = mutable.asStateFlow()
    val transportView: WebView? get() = wire?.transportView
    private val main = Handler(Looper.getMainLooper())
    private var wire: GomokuRoomWire? = null
    internal var wireFactory: ((String, Boolean, GomokuWireEvents) -> GomokuRoomWire)? = null
    internal var firstPlayer: () -> Int = { java.security.SecureRandom().nextInt(2) + 1 }
    private var hosting = false
    private var initialized = false
    private var generation = 0
    private var history = XiangqiUndoHistory()
    private var waitingTask: Runnable? = null
    private var ackTask: Runnable? = null
    private var pendingGuestMove: XiangqiMove? = null
    private var undoTask: Runnable? = null
    private var undoDeadline: RoomNegotiationDeadline? = null
    private var heartbeat: Runnable? = null
    private var lastPacket = 0L
    private val handshake = RoomHandshakeTimeout()
    private var assignment: RoomAssignment? = null
    private var pendingStart: RoomAssignment? = null
    private var hostReady = false
    private var guestReady = false
    private var localRematchIntent = false
    private var localVoteSequence = 0
    private var guestVoteSequence = 0
    private var resultDeadline = 0L
    private var roundTask: Runnable? = null
    private var voteTask: Runnable? = null
    private var playerName = "棋友"
    private var nearbyId: String? = null
    private var guestId = ""
    private var targetId = ""
    private var foreground = true
    private var localResignationIntent = false
    private fun canLocalUndo(): Boolean = mutable.value.localSide?.let { history.canUndo(it) } == true && mutable.value.resignedBy == null

    fun host(code: String = "", playerName: String = "棋友") {
        val target = if (online) if(code.isBlank()) RoomRoundRules.randomCode() else RoomRoundRules.code(code) else ""
        if(target==null) {mutable.value=mutable.value.copy(error="房间码用4–12位英文或数字哦");return}
        this.playerName=RoomRoundRules.name(playerName);guestId="";targetId="";start(target,true)
    }
    fun join(address: String, playerName: String = "棋友") {
        val target = if (online) RoomRoundRules.code(address) else address.trim()
        val valid = target!=null && (online || runCatching { GomokuLanWire.privateEndpoint(target,XiangqiLanSession.PORT) }.isSuccess)
        if (!valid) { mutable.value = mutable.value.copy(error = if (online) "请输入4–12位房间码" else "这个附近房间暂时无法连接"); return }
        this.playerName=RoomRoundRules.name(playerName)
        guestId="";targetId="";start(requireNotNull(target), false)
    }

    private fun start(address: String, host: Boolean) {
        close(); hosting = host
        val token = ++generation
        if (host && !online) handshake.close() // Active nearby radar may wait until the user cancels.
        else handshake.begin(SystemClock.uptimeMillis(), if (host) RoomRoundRules.WAITING_MILLIS else 45_000)
        mutable.value = XiangqiLanUiState(isHost=host, hostAddress = address,
            sessionActive = true, busy = true, localBackground = !foreground, status = if (online) "正在连接互联网房间…" else "正在寻找附近伙伴…")
        val events = GomokuWireEvents(
            waiting = { if (token == generation && !initialized) mutable.value = mutable.value.copy(hostAddress = it,
                status = if (host) "房间已准备好，等待伙伴加入" else "正在寻找房间…") },
            connected = { if (token == generation && !initialized && !mutable.value.connected && !handshake.waitingForState) {
                clearWaiting(); lastPacket = SystemClock.uptimeMillis()
                handshake.begin(lastPacket,RoomRoundRules.INVITE_MILLIS)
                mutable.value = mutable.value.copy(awaitingMatch=true,busy=true,error=null,status="等待棋友确认对弈…")
                scheduleWaiting(token)
                if(!host) send(XiangqiLanMessage.Control(RoomControl.Hello(this.playerName,guestId,targetId)))
                startHeartbeat(token)
            } },
            data = { if (token == generation) runCatching {
                val message = XiangqiLanProtocol.decode(it); refreshUndoDeadline(); lastPacket = SystemClock.uptimeMillis();
                if (mutable.value.reconnecting) mutable.value = mutable.value.copy(reconnecting = false, error = null, status = turnStatus(mutable.value.game))
                receive(message)
                refreshUndoDeadline()
            }.onFailure { fail("收到无效象棋数据，连接已关闭") } },
            recovering = { if (token == generation && initialized) { refreshUndoDeadline(); mutable.value = mutable.value.copy(reconnecting = true, status = "正在恢复连接，棋局为你留着…"); refreshUndoDeadline() } },
            recovered = { if (token == generation) { refreshUndoDeadline(); lastPacket = SystemClock.uptimeMillis(); mutable.value = mutable.value.copy(reconnecting = false, error = null, status = turnStatus(mutable.value.game)); refreshUndoDeadline(); sendPresence() } },
            failure = { if (token == generation) fail(it) },
        )
        wire = wireFactory?.invoke(address, host, events) ?: if (online) GomokuOnlineWire(requireNotNull(context), address, host, events,prefix="gulu-xq-") else GomokuLanWire(events,XiangqiLanSession.PORT).also {
            if (host) it.host() else it.join(address)
        }
        scheduleWaiting(token)
    }

    fun submitMove(move: XiangqiMove) {
        val current = mutable.value
        if (!initialized || !current.connected || current.localBackground || current.remoteBackground || current.reconnecting || current.awaitingAck || current.pendingUndoRequest != null ||
            current.rematchRequestedBy!=null || current.game.turnSide != current.localSide) return
        val next = XiangqiEngine.play(current.game, move)
        if (next === current.game) return
        if (hosting) publish(next) else {
            pendingGuestMove = move
            mutable.value = current.copy(awaitingAck = true, canUndo = false, error = null, status = "正在落子…")
            send(XiangqiLanMessage.Move(current.revision, move))
            val token = generation
            ackTask = object : Runnable { override fun run() {
                if (token != generation || !mutable.value.awaitingAck) return
                if (SystemClock.uptimeMillis() - lastPacket >= RoomRoundRules.RECONNECT_GRACE_MILLIS) fail("连接暂时未恢复，棋盘已保留")
                else { mutable.value = mutable.value.copy(status = "等待伙伴回到棋桌，落子会继续同步…"); main.postDelayed(this, 5_000) }
            }}.also { main.postDelayed(it, 8_000) }
        }
    }

    fun restart() = requestRematch()
    fun respondToMatch(accept:Boolean) {
        if(!hosting || mutable.value.pendingMatchName==null || !mutable.value.awaitingMatch) return
        if(accept) beginRound(RoomRoundRules.initial(firstPlayer()))
        else finishRoom(RoomCloseReason.DECLINED.hint,RoomCloseReason.DECLINED)
    }
    fun allowNearbyMatching(localId:String?) { if(!online && assignment==null) nearbyId=localId?.takeIf {it.matches(Regex("[a-f0-9]{12}"))} }
    fun joinNearby(room:NearbyGameRoom,localId:String,playerName:String="棋友") {
        require(localId.matches(Regex("[a-f0-9]{12}")) && room.id.matches(Regex("[a-f0-9]{12}")) && localId!=room.id)
        require(!online && runCatching {GomokuLanWire.privateEndpoint(room.address,XiangqiLanSession.PORT)}.isSuccess)
        this.playerName=RoomRoundRules.name(playerName);guestId=localId;targetId=room.id;start(room.address,false)
    }
    fun requestRematch() = respondToRematch(true)
    fun respondToRematch(accept:Boolean) {
        val round=assignment ?: return;val current=mutable.value
        if(!current.connected || current.pendingUndoRequest!=null || current.awaitingAck || accept && current.myRematchRequested) return
        if(hosting) receiveVote(RoomControl.Vote(round.round,current.revision,accept),fromHost=true)
        else {
            if(localVoteSequence==Int.MAX_VALUE) {fail("协商次数过多，请重新创建房间");return}
            localRematchIntent=accept;localVoteSequence++
            mutable.value=current.copy(myRematchRequested=accept,rematchRequestedBy=if(accept) current.localSide else current.rematchRequestedBy)
            send(XiangqiLanMessage.Control(RoomControl.Vote(round.round,current.revision,accept,localVoteSequence)))
        }
    }
    fun requestUndo() {
        val current = mutable.value
        val player = current.localSide ?: return
        if (!initialized || !current.connected || current.localBackground || current.remoteBackground || current.reconnecting || current.awaitingAck || current.pendingUndoRequest != null || current.rematchRequestedBy!=null) return
        if (current.resignedBy != null) return
        val request = history.beginLocal(current.revision, current.game, player) ?: return
        showUndo(request); send(XiangqiLanMessage.UndoRequest(request))
    }
    fun respondToUndo(accept: Boolean) {
        val current = mutable.value
        val player = current.localSide ?: return
            val request = history.pending ?: return
        if (!current.connected || request.requester == player || request.revision != current.revision) return
        if (accept && !history.consentLocally(current.revision, current.game, player)) return
        if (hosting) {
            if (accept) commitUndo(request, player) else cancelUndo(request, XiangqiUndoResolution.REJECTED, true)
        } else {
            mutable.value = current.copy(status = "等待伙伴同步悔棋结果…")
            send(XiangqiLanMessage.UndoResponse(request.revision, request.id, request.requester, accept))
        }
    }

    private fun receive(message: XiangqiLanMessage) {
        val current = mutable.value
        when (message) {
            XiangqiLanMessage.Hello -> throw LanProtocolException()
            XiangqiLanMessage.Ping -> send(XiangqiLanMessage.Pong)
            XiangqiLanMessage.Pong -> Unit
            is XiangqiLanMessage.Move -> {
                if (!hosting || !current.connected) throw LanProtocolException()
                val next = if (message.revision == current.revision && current.pendingUndoRequest == null &&
                    current.rematchRequestedBy==null && current.game.turnSide == requireNotNull(current.localSide).opponent) XiangqiEngine.play(current.game, message.move) else current.game
                if (next === current.game) send(XiangqiLanMessage.Reject(XiangqiLanRejection.ILLEGAL)) else publish(next)
            }
            is XiangqiLanMessage.Snapshot -> {
                if(hosting) throw LanProtocolException()
                val start=pendingStart
                val valid=if(start!=null) message.revision==start.revision && message.game==XiangqiEngine.newGame()
                    else initialized && XiangqiSnapshotRules.accepts(current.revision,current.game,true,message,allowRestart=false)
                val advancing = message.revision != current.revision
                if(!valid || start==null && advancing && !(current.game.turnSide != current.localSide ||
                    pendingGuestMove?.let {XiangqiEngine.play(current.game,it)==message.game}==true)) throw LanProtocolException()
                if (message.revision != current.revision && !history.recordAdvance(current.game, message.game)) throw LanProtocolException()
                if (message.revision != current.revision) { clearUndo(); clearVotes() }
                initialized = true; handshake.validatedState(); clearWaiting()
                if(start!=null || advancing) clearAck()
                if(start!=null) {localVoteSequence=0;guestVoteSequence=0;assignment=start;pendingStart=null;history=XiangqiUndoHistory();clearRound();hostReady=false;guestReady=false;localResignationIntent=false}
                mutable.value = current.copy(game = message.game, revision = message.revision, connected = true, busy = false,
                    awaitingAck = if(start!=null || advancing) false else current.awaitingAck, error = null, status = turnStatus(message.game),
                    canUndo = canLocalUndo() && (start!=null || advancing || !current.awaitingAck),
                    pendingUndoRequest = history.pending?.requester,localSide=side(3-requireNotNull(assignment).hostPlayer),remoteSelection=null,
                    round=assignment!!.round,resignedBy=if(start!=null) null else current.resignedBy,awaitingMatch=false,pendingMatchName=null,roomEnded=false,
                    resultSecondsLeft=if(start!=null) 0 else current.resultSecondsLeft,
                    rematchRequestedBy=mutable.value.rematchRequestedBy,myRematchRequested=mutable.value.myRematchRequested)
                if(start!=null) sendPresence()
                if(message.game.outcome!=XiangqiOutcome.PLAYING) enterResult()
            }
            is XiangqiLanMessage.Reject -> {
                if (hosting || !current.connected) throw LanProtocolException()
                clearAck(); mutable.value = current.copy(awaitingAck = false, canUndo = canLocalUndo(),
                    error = "棋局已更新，这一步没有落下", status = turnStatus(current.game))
            }
            is XiangqiLanMessage.UndoRequest -> {
                val player = current.localSide ?: return
                if (!current.connected || message.request.requester != player.opponent) throw LanProtocolException()
                when (val offered = history.receiveOffer(message.request, current.revision, current.game, player, hosting)) {
                    XiangqiUndoOffer.ACCEPTED -> showUndo(message.request)
                    XiangqiUndoOffer.DUPLICATE -> Unit
                    else -> if (hosting) send(XiangqiLanMessage.UndoResult(message.request, when (offered) {
                        XiangqiUndoOffer.BUSY -> XiangqiUndoResolution.BUSY
                        XiangqiUndoOffer.EMPTY -> XiangqiUndoResolution.EMPTY
                        else -> XiangqiUndoResolution.STALE
                    })) else if (offered != XiangqiUndoOffer.STALE) throw LanProtocolException()
                }
            }
            is XiangqiLanMessage.UndoResponse -> {
                if (!hosting || !current.connected) throw LanProtocolException()
                val request = history.pending ?: return
                if (request.requester != current.localSide || request.requester != message.requester || request.revision != message.revision || request.id != message.id) return
                if (message.accept) commitUndo(request, requireNotNull(current.localSide).opponent) else cancelUndo(request, XiangqiUndoResolution.REJECTED, true)
            }
            is XiangqiLanMessage.UndoResult -> {
                if (hosting || !current.connected) throw LanProtocolException()
                cancelUndo(message.request, message.resolution, false)
            }
            is XiangqiLanMessage.UndoSnapshot -> {
                if (hosting || !current.connected || !initialized || !history.commitGuestUndo(requireNotNull(current.localSide), current.revision, current.game, message)) throw LanProtocolException()
                clearUndo(); clearAck()
                if(message.snapshot.game.outcome==XiangqiOutcome.PLAYING) clearRound()
                mutable.value = current.copy(game = message.snapshot.game, revision = message.snapshot.revision, awaitingAck = false,
                    pendingUndoRequest = null, canUndo = canLocalUndo(), error = null,
                    resultSecondsLeft=mutable.value.resultSecondsLeft,rematchRequestedBy=null,myRematchRequested=false,status = turnStatus(message.snapshot.game))
            }
            is XiangqiLanMessage.Control -> receiveControl(message.value)
            is XiangqiLanMessage.Select -> if(current.connected && current.pendingUndoRequest==null && current.rematchRequestedBy==null &&
                XiangqiSelectionRules.accepts(current.revision,current.game,current.localSide,message)) mutable.value=current.copy(remoteSelection=message.cell)
        }
    }
    private fun publish(game: XiangqiState) {
        val current = mutable.value
        if (current.revision == Int.MAX_VALUE) { fail("对局过长，请重新创建房间"); return }
        history.pending?.let { cancelUndo(it, XiangqiUndoResolution.CANCELLED, true) }
        if (!history.recordAdvance(current.game, game)) { fail("棋局历史不一致，请重新创建房间"); return }
        clearVotes()
        val revision = current.revision + 1
        mutable.value = current.copy(game = game, revision = revision, error = null, status = turnStatus(game),
            pendingUndoRequest = null, canUndo = canLocalUndo(),remoteSelection=null,rematchRequestedBy=null,myRematchRequested=false)
        send(XiangqiLanMessage.Snapshot(revision, game))
        if(game.outcome!=XiangqiOutcome.PLAYING) enterResult()
    }
    private fun showUndo(request: XiangqiUndoRequest) {
        clearUndo()
        mutable.value = mutable.value.copy(pendingUndoRequest = request.requester, canUndo = false, error = null,
            status = if (request.requester == mutable.value.localSide) "等待伙伴同意悔棋…" else "伙伴想退回一步，等你回复")
        val token = generation
        undoDeadline = RoomNegotiationDeadline(if(hosting) 20_000 else 25_000, SystemClock.uptimeMillis(), negotiationSuspended())
        undoTask = object : Runnable { override fun run() {
            if (token != generation || history.pending != request) return
            val paused = negotiationSuspended()
            val remaining = undoDeadline?.tick(SystemClock.uptimeMillis(), paused) ?: return
            if (remaining == 0L && !paused) cancelUndo(request, XiangqiUndoResolution.TIMEOUT, hosting)
            else main.postDelayed(this, if(paused) 1_000 else remaining.coerceAtMost(1_000).coerceAtLeast(1))
        }}.also { main.postDelayed(it, 1_000) }
    }
    private fun negotiationSuspended(): Boolean = mutable.value.localBackground || mutable.value.remoteBackground ||
        mutable.value.reconnecting || SystemClock.uptimeMillis() - lastPacket >= RoomRoundRules.CONNECTION_QUIET_MILLIS
    private fun refreshUndoDeadline() { undoDeadline?.tick(SystemClock.uptimeMillis(), negotiationSuspended()) }
    private fun cancelUndo(request: XiangqiUndoRequest, resolution: XiangqiUndoResolution, notify: Boolean) {
        if (!history.cancelIfMatches(request)) return
        clearUndo()
        mutable.value = mutable.value.copy(pendingUndoRequest = null, canUndo = mutable.value.connected && !mutable.value.awaitingAck && canLocalUndo(),
            error = resolution.hint, status = turnStatus(mutable.value.game))
        if (notify) send(XiangqiLanMessage.UndoResult(request, resolution))
    }
    private fun commitUndo(request: XiangqiUndoRequest, responder: XiangqiSide) {
        val current = mutable.value
        val target = history.commitHost(request, responder, current.revision, current.game) ?: return
        clearUndo();clearVotes()
        val revision = current.revision + 1
        mutable.value = current.copy(game = target, revision = revision, canUndo = canLocalUndo(),
            pendingUndoRequest = null, error = null,rematchRequestedBy=null,myRematchRequested=false,
            resultSecondsLeft=if(target.outcome==XiangqiOutcome.PLAYING) 0 else current.resultSecondsLeft,status = turnStatus(target))
        send(XiangqiLanMessage.UndoSnapshot(request, XiangqiLanMessage.Snapshot(revision, target)))
        if(target.outcome==XiangqiOutcome.PLAYING) clearRound()
    }
    private fun send(message: XiangqiLanMessage) { wire?.send(XiangqiLanProtocol.encode(message)) }
    private fun turnStatus(game: XiangqiState): String = when (game.outcome) {
        XiangqiOutcome.RED_WON -> "红方获胜"
        XiangqiOutcome.BLACK_WON -> "黑方获胜"
        XiangqiOutcome.PLAYING -> if (game.turnSide == mutable.value.localSide) "轮到你落子" else "等待伙伴落子"
    }
    /** A short trip to another app pauses both desks without declaring a loser. */
    fun setForeground(value: Boolean) {
        refreshUndoDeadline()
        foreground = value
        wire?.foreground(value)
        val current = mutable.value
        if (!current.sessionActive) return
        lastPacket = SystemClock.uptimeMillis()
        mutable.value = current.copy(localBackground = !value, status = if(!value) "棋局暂存，回来继续下" else turnStatus(current.game))
        refreshUndoDeadline()
        sendPresence()
    }
    private fun sendPresence() {
        val current = mutable.value
        val player = current.localSide?.let { if(it == XiangqiSide.RED) 1 else 2 } ?: return
        assignment?.let { send(XiangqiLanMessage.Control(RoomControl.Presence(it.round, current.revision, player, !foreground))) }
    }
    fun resign() {
        val current = mutable.value; val player = current.localSide?.let { if(it == XiangqiSide.RED) 1 else 2 } ?: return
        val round = assignment ?: return
        if (!current.connected || current.reconnecting || current.awaitingAck || current.game.outcome != XiangqiOutcome.PLAYING) return
        if (hosting) commitResignation(player)
        else { localResignationIntent = true; send(XiangqiLanMessage.Control(RoomControl.Resign(round.round, current.revision, player))) }
    }
    private fun commitResignation(player: Int) {
        val current = mutable.value; val round = assignment ?: return
        if (current.game.outcome != XiangqiOutcome.PLAYING || current.revision == Int.MAX_VALUE) return
        val revision = current.revision + 1
        clearUndo(); clearAck(); clearVotes()
        history.cancelPending()
        val game = current.game.copy(outcome = if(player==1) XiangqiOutcome.BLACK_WON else XiangqiOutcome.RED_WON)
        mutable.value = current.copy(game = game, revision = revision, resignedBy = side(player), pendingUndoRequest = null,
            canUndo = false, awaitingAck = false, error = null, rematchRequestedBy = null, myRematchRequested = false,
            status = if (player == 1) "红方已认输" else "黑方已认输")
        send(XiangqiLanMessage.Control(RoomControl.Resigned(round.round, revision, player)))
        enterResult()
    }
    private fun startHeartbeat(token: Int) {
        heartbeat = object : Runnable { override fun run() {
            if (token != generation || wire == null) return
            val quiet = SystemClock.uptimeMillis() - lastPacket
            if (quiet >= RoomRoundRules.RECONNECT_GRACE_MILLIS && foreground) { fail("连接暂时未恢复，棋盘已保留，可以重新约一局"); return }
            if (quiet > RoomRoundRules.CONNECTION_QUIET_MILLIS && !mutable.value.localBackground && !mutable.value.remoteBackground)
                mutable.value = mutable.value.copy(reconnecting = true, status = "正在恢复连接，棋局为你留着…")
            send(XiangqiLanMessage.Ping); main.postDelayed(this, 5_000)
        } }.also { main.postDelayed(it, 5_000) }
    }
    private fun clearWaiting() { waitingTask?.let(main::removeCallbacks); waitingTask = null }
    private fun scheduleWaiting(token: Int) {
        clearWaiting()
        if (!handshake.active) return
        waitingTask = Runnable {
            if (token == generation && handshake.expired(SystemClock.uptimeMillis())) {
                if (mutable.value.awaitingMatch && !handshake.waitingForState) finishRoom(RoomCloseReason.INVITE_TIMEOUT.hint,RoomCloseReason.INVITE_TIMEOUT)
                else fail(if (handshake.waitingForState) "棋局同步超时，请重新连接房间" else "房间连接超时，可以重新进入或换一种方式")
            }
        }.also { main.postDelayed(it, handshake.remaining(SystemClock.uptimeMillis())) }
    }
    private fun clearAck() { ackTask?.let(main::removeCallbacks); ackTask = null;pendingGuestMove=null }
    private fun clearUndo() { undoTask?.let(main::removeCallbacks); undoTask = null; undoDeadline = null }
    private fun fail(message: String) { finishRoom(message,null); mutable.value = mutable.value.copy(error = message) }
    fun close() {
        finishRoom("连接已关闭",RoomCloseReason.LEFT)
    }
    private fun finishRoom(message:String,notify:RoomCloseReason?) {
        if(notify!=null && wire!=null) send(XiangqiLanMessage.Control(RoomControl.Close(assignment?.round ?: 0,mutable.value.revision,notify)))
        val notifyPeer = notify != null && (initialized || mutable.value.awaitingMatch)
        val old=wire;wire=null
        generation++; handshake.close(); clearWaiting(); clearAck(); clearUndo(); clearSelection(); heartbeat?.let(main::removeCallbacks); heartbeat = null
        clearRound();initialized = false; history = XiangqiUndoHistory();localVoteSequence=0;guestVoteSequence=0;pendingStart=null;nearbyId=null
        if(!notifyPeer) old?.close() else main.postDelayed({old?.close()},200)
        mutable.value = mutable.value.copy(localSide = if(assignment!=null) mutable.value.localSide else null, hostAddress = "", connected = false, sessionActive = false,
            awaitingAck = false, busy = false, canUndo = false, pendingUndoRequest = null, status = message, error = null,
            pendingMatchName=null,awaitingMatch=false,roomEnded=assignment!=null,myRematchRequested=false,rematchRequestedBy=null,localBackground=false,remoteBackground=false,reconnecting=false)
        assignment=null; localResignationIntent=false
    }
    private fun receiveControl(control:RoomControl) {
        val current=mutable.value
        when(control) {
            is RoomControl.Hello -> {
                if(!hosting || assignment!=null || current.pendingMatchName!=null) throw LanProtocolException()
                mutable.value=current.copy(pendingMatchName=control.name,awaitingMatch=true,busy=false,status="棋友想和你下一局")
                if (online) respondToMatch(true)
                else if (control.guestId.isNotEmpty()) {
                    if (RoomRoundRules.willingNearbyHost(nearbyId, control)) respondToMatch(true)
                    else finishRoom("这个附近邀请已过期，重新找找吧", RoomCloseReason.DECLINED)
                }
            }
            is RoomControl.Start -> {
                if(hosting || !RoomRoundRules.accepts(assignment,current.revision,control.assignment,current.myRematchRequested,hostReady&&guestReady)) throw LanProtocolException()
                pendingStart=control.assignment;clearWaiting();handshake.begin(SystemClock.uptimeMillis(),8_000)
                handshake.linkReady(SystemClock.uptimeMillis(),false);scheduleWaiting(generation)
                mutable.value=current.copy(awaitingAck=true,busy=true,status="正在摆好这一局的棋子…")
            }
            is RoomControl.Vote -> {if(!hosting) throw LanProtocolException();receiveVote(control,false)}
            is RoomControl.Votes -> {
                if(hosting) throw LanProtocolException()
                val round=assignment ?: return
                if(control.round!=round.round || control.revision!=current.revision) return
                if(control.guestSequence>localVoteSequence) throw LanProtocolException()
                hostReady=control.hostReady
                if(control.guestSequence==localVoteSequence) {
                    if(control.guestReady && !localRematchIntent) throw LanProtocolException()
                    guestReady=control.guestReady;localRematchIntent=control.guestReady
                }
                showVotes()
            }
            is RoomControl.Presence -> {
                val round = assignment ?: return; val player = current.localSide?.let { if(it == XiangqiSide.RED) 1 else 2 } ?: return
                if (control.round != round.round || control.revision > current.revision) return
                if (control.player != 3 - player) throw LanProtocolException()
                mutable.value = current.copy(remoteBackground = control.background, status = if(control.background) "伙伴暂时离开棋桌，回来继续下" else turnStatus(current.game))
                refreshUndoDeadline()
            }
            is RoomControl.Resign -> {
                val round = assignment ?: return; val player = current.localSide?.let { if(it == XiangqiSide.RED) 1 else 2 } ?: return
                if(control.round != round.round) return
                if (!hosting || control.player != 3 - player) throw LanProtocolException()
                if(control.revision <= current.revision) commitResignation(control.player)
            }
            is RoomControl.Resigned -> {
                val round = assignment ?: return; val player = current.localSide?.let { if(it == XiangqiSide.RED) 1 else 2 } ?: return
                if(control.round != round.round) return
                if (hosting || (control.player == player && !localResignationIntent)) throw LanProtocolException()
                if (control.round != round.round || current.revision == Int.MAX_VALUE || control.revision != current.revision + 1 || current.game.outcome != XiangqiOutcome.PLAYING) return
                clearUndo(); clearAck(); clearVotes(); history.cancelPending()
                val resignedPlayer = control.player
                mutable.value = current.copy(game = current.game.copy(outcome = if(resignedPlayer==1) XiangqiOutcome.BLACK_WON else XiangqiOutcome.RED_WON), revision = control.revision,
                    resignedBy = side(resignedPlayer), pendingUndoRequest = null, canUndo = false, awaitingAck = false,
                    rematchRequestedBy = null, myRematchRequested = false, error = null, status = if(control.player == 1) "红方已认输" else "黑方已认输")
                localResignationIntent = false; enterResult()
            }
            is RoomControl.Close -> {
                if(control.round < (assignment?.round ?: 0) || control.revision<current.revision) return
                finishRoom(control.reason.hint,null)
            }
        }
    }
    private fun beginRound(next:RoomAssignment) {
        val current=mutable.value
        clearWaiting();handshake.close();clearAck();clearUndo();clearRound();clearSelection()
        history=XiangqiUndoHistory();localVoteSequence=0;guestVoteSequence=0;assignment=next;initialized=true;pendingStart=null;nearbyId=null;localResignationIntent=false
        mutable.value=current.copy(game=XiangqiEngine.newGame(),revision=next.revision,localSide=side(next.hostPlayer),remoteSelection=null,
            round=next.round,isHost=true,connected=true,awaitingMatch=false,pendingMatchName=null,busy=false,awaitingAck=false,
            pendingUndoRequest=null,canUndo=false,rematchRequestedBy=null,myRematchRequested=false,resultSecondsLeft=0,roomEnded=false,resignedBy=null,error=null,status="棋友已就位，红方先行")
        send(XiangqiLanMessage.Control(RoomControl.Start(next)))
        send(XiangqiLanMessage.Snapshot(next.revision,XiangqiEngine.newGame()))
        sendPresence()
    }
    private fun receiveVote(vote:RoomControl.Vote,fromHost:Boolean) {
        val round=assignment ?: return;val current=mutable.value
        if(vote.round!=round.round || vote.revision!=current.revision) {sendVotes();return}
        if(!fromHost) {
            if(vote.sequence<=guestVoteSequence) {sendVotes();return}
            guestVoteSequence=vote.sequence
        }
        if(current.pendingUndoRequest!=null) {sendVotes();return}
        if(!vote.accept) {
            if(current.game.outcome!=XiangqiOutcome.PLAYING) finishRoom(RoomCloseReason.NO_REMATCH.hint,RoomCloseReason.NO_REMATCH)
            else {clearVotes();showVotes();sendVotes()}
            return
        }
        if(fromHost) hostReady=true else guestReady=true
        showVotes();sendVotes()
        val next=RoomRoundRules.next(round,current.revision,hostReady,guestReady)
        if(next!=null) beginRound(next)
        else if(current.game.outcome==XiangqiOutcome.PLAYING && voteTask==null) {
            val token=generation
            voteTask=Runnable {if(token==generation) {clearVotes();showVotes();sendVotes()}}
                .also {main.postDelayed(it,20_000)}
        }
    }
    private fun showVotes() {
        val current=mutable.value;val round=assignment ?: return
        val who=if(hostReady) round.hostPlayer else if(guestReady || !hosting && localRematchIntent) 3-round.hostPlayer else null
        mutable.value=current.copy(rematchRequestedBy=who?.let(::side),myRematchRequested=if(hosting) hostReady else localRematchIntent,
            canUndo=who==null && canLocalUndo(),status=if(who==null) turnStatus(current.game) else "等棋友同意，再摆一盘")
    }
    private fun sendVotes() {assignment?.let {send(XiangqiLanMessage.Control(RoomControl.Votes(it.round,mutable.value.revision,hostReady,guestReady,guestVoteSequence)))}}
    private fun enterResult() {
        if(resultDeadline!=0L) return
        resultDeadline=SystemClock.uptimeMillis()+RoomRoundRules.RESULT_SECONDS*1_000L
        val token=generation
        roundTask=object:Runnable {override fun run() {
            if(token!=generation || resultDeadline==0L) return
            if (mutable.value.localBackground || mutable.value.remoteBackground || mutable.value.reconnecting) { resultDeadline += 1_000; main.postDelayed(this,1_000); return }
            val left=((resultDeadline-SystemClock.uptimeMillis()+999)/1000).coerceAtLeast(0).toInt()
            mutable.value=mutable.value.copy(resultSecondsLeft=left)
            if(left==0) finishRoom(RoomCloseReason.RESULT_TIMEOUT.hint,RoomCloseReason.RESULT_TIMEOUT)
            else main.postDelayed(this,1_000)
        }}.also {it.run()}
    }
    private fun clearVotes() {
        voteTask?.let(main::removeCallbacks);voteTask=null;hostReady=false;guestReady=false;localRematchIntent=false
        mutable.value=mutable.value.copy(rematchRequestedBy=null,myRematchRequested=false)
    }
    private fun clearRound() {
        roundTask?.let(main::removeCallbacks);roundTask=null;voteTask?.let(main::removeCallbacks);voteTask=null
        resultDeadline=0;hostReady=false;guestReady=false;localRematchIntent=false
        mutable.value=mutable.value.copy(resultSecondsLeft=0,rematchRequestedBy=null,myRematchRequested=false)
    }
    private fun side(player:Int)=if(player==1) XiangqiSide.RED else XiangqiSide.BLACK
    private fun clearSelection() {selectionTask?.let(main::removeCallbacks);selectionTask=null;selection=null}
    private var selectionTask:Runnable?=null
    private var selection:GridCell?=null
    fun selectPiece(cell:GridCell?) {
        val current=mutable.value;val local=current.localSide ?: return
        if(!current.connected || current.awaitingAck || current.pendingUndoRequest!=null || current.rematchRequestedBy!=null ||
            !XiangqiSelectionRules.canSelect(current.game,local,cell)) return
        selection=cell
        if(selectionTask!=null) return
        val token=generation;val revision=current.revision
        selectionTask=Runnable {
            selectionTask=null
            val latest=mutable.value
            if(token==generation && latest.connected && latest.revision==revision && latest.pendingUndoRequest==null &&
                latest.rematchRequestedBy==null && latest.localSide?.let {XiangqiSelectionRules.canSelect(latest.game,it,selection)}==true)
                send(XiangqiLanMessage.Select(revision,selection))
        }.also {main.postDelayed(it,100)}
    }
}
