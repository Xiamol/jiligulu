package com.jiligulu.app.ui.littleworld

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GomokuRoomUiState(
    val game: GomokuState = GomokuEngine.newGame(), val localPlayer: Int? = null,
    val hostAddress: String = "", val connected: Boolean = false, val sessionActive: Boolean = false,
    val awaitingAck: Boolean = false, val revision: Int = 0, val status: String = "和伙伴下一局",
    val error: String? = null, val busy: Boolean = false, val canUndo: Boolean = false,
    val pendingUndoRequest: Int? = null,
    val isHost: Boolean = false, val round: Int = 0, val pendingMatchName: String? = null,
    val awaitingMatch: Boolean = false, val rematchRequestedBy: Int? = null, val myRematchRequested: Boolean = false,
    val resultSecondsLeft: Int = 0, val roomEnded: Boolean = false,
)

class GomokuLanSession : GomokuRoomSession(null, false)
class GomokuOnlineSession(context: Context) : GomokuRoomSession(context, true)

/** The host owns move/history revisions for both transports; callbacks arrive on the main looper. */
open class GomokuRoomSession protected constructor(private val context: Context?, private val online: Boolean) {
    private val mutable = MutableStateFlow(GomokuRoomUiState())
    val state: StateFlow<GomokuRoomUiState> = mutable.asStateFlow()
    val transportView: WebView? get() = wire?.transportView
    private val main = Handler(Looper.getMainLooper())
    private var wire: GomokuRoomWire? = null
    internal var wireFactory: ((String, Boolean, GomokuWireEvents) -> GomokuRoomWire)? = null
    internal var firstPlayer: () -> Int = { java.security.SecureRandom().nextInt(2) + 1 }
    private var hosting = false
    private var initialized = false
    private var generation = 0
    private var history = GomokuUndoHistory()
    private var waitingTask: Runnable? = null
    private var ackTask: Runnable? = null
    private var pendingGuestMove: GridCell? = null
    private var undoTask: Runnable? = null
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

    fun host(code: String = "", playerName: String = "棋友") {
        val target = if (online) if(code.isBlank()) RoomRoundRules.randomCode() else RoomRoundRules.code(code) else ""
        if(target==null) {mutable.value=mutable.value.copy(error="房间码用4–12位英文或数字哦");return}
        this.playerName=RoomRoundRules.name(playerName);guestId="";targetId="";start(target,true)
    }
    fun join(address: String, playerName: String = "棋友") {
        val target = if (online) RoomRoundRules.code(address) else address.trim()
        val valid = target!=null && (online || runCatching { GomokuLanWire.privateEndpoint(target) }.isSuccess)
        if (!valid) { mutable.value = mutable.value.copy(error = if (online) "请输入4–12位房间码" else "这个附近房间暂时无法连接"); return }
        this.playerName=RoomRoundRules.name(playerName)
        guestId="";targetId="";start(requireNotNull(target), false)
    }

    private fun start(address: String, host: Boolean) {
        close(); hosting = host
        val token = ++generation
        if (host && !online) handshake.close() // Active nearby radar may wait until the user cancels.
        else handshake.begin(SystemClock.uptimeMillis(), if (host) 120_000 else 35_000)
        mutable.value = GomokuRoomUiState(isHost=host, hostAddress = address,
            sessionActive = true, busy = true, status = if (online) "正在连接互联网房间…" else "正在寻找附近伙伴…")
        val events = GomokuWireEvents(
            waiting = { if (token == generation) mutable.value = mutable.value.copy(hostAddress = it,
                status = if (host) "房间已准备好，等待伙伴加入" else "正在寻找房间…") },
            connected = { if (token == generation && !initialized && !mutable.value.connected && !handshake.waitingForState) {
                clearWaiting(); lastPacket = SystemClock.uptimeMillis()
                handshake.begin(lastPacket,RoomRoundRules.INVITE_MILLIS)
                mutable.value = mutable.value.copy(awaitingMatch=true,busy=true,error=null,status="等待棋友确认对弈…")
                scheduleWaiting(token)
                if(!host) send(GomokuRoomMessage.Control(RoomControl.Hello(this.playerName,guestId,targetId)))
                startHeartbeat(token)
            } },
            data = { if (token == generation) runCatching {
                val message = GomokuRoomProtocol.decode(it); lastPacket = SystemClock.uptimeMillis(); receive(message)
            }.onFailure { fail("收到无效五子棋数据，连接已关闭") } },
            failure = { if (token == generation) fail(it) },
        )
        wire = wireFactory?.invoke(address, host, events) ?: if (online) GomokuOnlineWire(requireNotNull(context), address, host, events) else GomokuLanWire(events).also {
            if (host) it.host() else it.join(address)
        }
        scheduleWaiting(token)
    }

    fun submitMove(cell: GridCell) {
        val current = mutable.value
        if (!initialized || !current.connected || current.awaitingAck || current.pendingUndoRequest != null ||
            current.rematchRequestedBy!=null || current.game.currentPlayer != current.localPlayer) return
        val next = GomokuEngine.play(current.game, cell.x, cell.y)
        if (next === current.game) return
        if (hosting) publish(next) else {
            pendingGuestMove = cell
            mutable.value = current.copy(awaitingAck = true, canUndo = false, error = null, status = "正在落子…")
            send(GomokuRoomMessage.Move(current.revision, cell))
            val token = generation
            ackTask = Runnable { if (token == generation && mutable.value.awaitingAck) fail("落子确认超时，请重新连接") }
                .also { main.postDelayed(it, 8_000) }
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
        require(!online && runCatching {GomokuLanWire.privateEndpoint(room.address,GomokuLanWire.PORT)}.isSuccess)
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
            mutable.value=current.copy(myRematchRequested=accept,rematchRequestedBy=if(accept) current.localPlayer else current.rematchRequestedBy)
            send(GomokuRoomMessage.Control(RoomControl.Vote(round.round,current.revision,accept,localVoteSequence)))
        }
    }
    fun requestUndo() {
        val current = mutable.value
        val player = current.localPlayer ?: return
        if (!initialized || !current.connected || current.awaitingAck || current.pendingUndoRequest != null || current.rematchRequestedBy!=null) return
        val request = history.begin(current.revision, current.game, player) ?: return
        showUndo(request); send(GomokuRoomMessage.UndoRequest(request))
    }
    fun respondToUndo(accept: Boolean) {
        val current = mutable.value
        val player = current.localPlayer ?: return
            val request = history.pending ?: return
        if (!current.connected || request.requester == player || request.revision != current.revision) return
        if (accept && !history.consent(current.revision, current.game, player)) return
        if (hosting) {
            if (accept) commitUndo(request, player) else cancelUndo(request, XiangqiUndoResolution.REJECTED, true)
        } else {
            mutable.value = current.copy(status = "等待伙伴同步悔棋结果…")
            send(GomokuRoomMessage.UndoResponse(request.revision, request.id, request.requester, accept))
        }
    }

    private fun receive(message: GomokuRoomMessage) {
        val current = mutable.value
        when (message) {
            GomokuRoomMessage.Hello -> throw LanProtocolException()
            GomokuRoomMessage.Ping -> send(GomokuRoomMessage.Pong)
            GomokuRoomMessage.Pong -> Unit
            is GomokuRoomMessage.Move -> {
                if (!hosting || !current.connected) throw LanProtocolException()
                val next = if (message.revision == current.revision && current.pendingUndoRequest == null &&
                    current.rematchRequestedBy==null && current.game.currentPlayer == 3-requireNotNull(current.localPlayer)) GomokuEngine.play(current.game, message.cell.x, message.cell.y) else current.game
                if (next === current.game) send(GomokuRoomMessage.Reject) else publish(next)
            }
            is GomokuRoomMessage.Snapshot -> {
                if(hosting) throw LanProtocolException()
                val start=pendingStart
                val valid=if(start!=null) message.revision==start.revision && message.game==GomokuEngine.newGame()
                    else initialized && GomokuRoomProtocol.acceptsSnapshot(current.revision,current.game,true,message,allowRestart=false)
                val advancing = message.revision != current.revision
                if(!valid || start==null && advancing && !(current.game.currentPlayer != current.localPlayer ||
                    pendingGuestMove?.let {GomokuEngine.play(current.game,it.x,it.y)==message.game}==true)) throw LanProtocolException()
                if (message.revision != current.revision && !history.record(current.game, message.game)) throw LanProtocolException()
                if (message.revision != current.revision) { clearUndo(); clearVotes() }
                initialized = true; handshake.validatedState(); clearWaiting()
                if(start!=null || advancing) clearAck()
                if(start!=null) {localVoteSequence=0;guestVoteSequence=0;assignment=start;pendingStart=null;history=GomokuUndoHistory();clearRound();hostReady=false;guestReady=false}
                mutable.value = current.copy(game = message.game, revision = message.revision, connected = true, busy = false,
                    awaitingAck = if(start!=null || advancing) false else current.awaitingAck, error = null, status = turnStatus(message.game),
                    canUndo = history.canUndo && (start!=null || advancing || !current.awaitingAck),
                    pendingUndoRequest = history.pending?.requester,localPlayer=3-requireNotNull(assignment).hostPlayer,
                    round=assignment!!.round,awaitingMatch=false,pendingMatchName=null,roomEnded=false,
                    resultSecondsLeft=if(start!=null) 0 else current.resultSecondsLeft,
                    rematchRequestedBy=mutable.value.rematchRequestedBy,myRematchRequested=mutable.value.myRematchRequested)
                if(message.game.outcome!=GomokuOutcome.PLAYING) enterResult()
            }
            GomokuRoomMessage.Reject -> {
                if (hosting || !current.connected) throw LanProtocolException()
                clearAck(); mutable.value = current.copy(awaitingAck = false, canUndo = history.canUndo,
                    error = "棋局已更新，这一步没有落下", status = turnStatus(current.game))
            }
            is GomokuRoomMessage.UndoRequest -> {
                val player = current.localPlayer ?: return
                if (!current.connected || message.request.requester != 3 - player) throw LanProtocolException()
                when (val offered = history.offer(message.request, current.revision, current.game, player, hosting)) {
                    XiangqiUndoOffer.ACCEPTED -> showUndo(message.request)
                    XiangqiUndoOffer.DUPLICATE -> Unit
                    else -> if (hosting) send(GomokuRoomMessage.UndoResult(message.request, when (offered) {
                        XiangqiUndoOffer.BUSY -> XiangqiUndoResolution.BUSY
                        XiangqiUndoOffer.EMPTY -> XiangqiUndoResolution.EMPTY
                        else -> XiangqiUndoResolution.STALE
                    })) else if (offered != XiangqiUndoOffer.STALE) throw LanProtocolException()
                }
            }
            is GomokuRoomMessage.UndoResponse -> {
                if (!hosting || !current.connected) throw LanProtocolException()
                val request = history.pending ?: return
                if (request.requester != current.localPlayer || request.requester != message.requester || request.revision != message.revision || request.id != message.id) return
                if (message.accept) commitUndo(request, 3-requireNotNull(current.localPlayer)) else cancelUndo(request, XiangqiUndoResolution.REJECTED, true)
            }
            is GomokuRoomMessage.UndoResult -> {
                if (hosting || !current.connected) throw LanProtocolException()
                cancelUndo(message.request, message.resolution, false)
            }
            is GomokuRoomMessage.UndoSnapshot -> {
                if (hosting || !current.connected || !initialized || !history.commitGuest(requireNotNull(current.localPlayer), current.revision, current.game, message)) throw LanProtocolException()
                clearUndo(); clearAck()
                if(message.snapshot.game.outcome==GomokuOutcome.PLAYING) clearRound()
                mutable.value = current.copy(game = message.snapshot.game, revision = message.snapshot.revision, awaitingAck = false,
                    pendingUndoRequest = null, canUndo = history.canUndo, error = null,
                    resultSecondsLeft=mutable.value.resultSecondsLeft,rematchRequestedBy=null,myRematchRequested=false,status = turnStatus(message.snapshot.game))
            }
            is GomokuRoomMessage.Control -> receiveControl(message.value)
        }
    }
    private fun publish(game: GomokuState) {
        val current = mutable.value
        if (current.revision == Int.MAX_VALUE) { fail("对局过长，请重新创建房间"); return }
        history.pending?.let { cancelUndo(it, XiangqiUndoResolution.CANCELLED, true) }
        if (!history.record(current.game, game)) { fail("棋局历史不一致，请重新创建房间"); return }
        clearVotes()
        val revision = current.revision + 1
        mutable.value = current.copy(game = game, revision = revision, error = null, status = turnStatus(game),
            pendingUndoRequest = null, canUndo = history.canUndo,rematchRequestedBy=null,myRematchRequested=false)
        send(GomokuRoomMessage.Snapshot(revision, game))
        if(game.outcome!=GomokuOutcome.PLAYING) enterResult()
    }
    private fun showUndo(request: GomokuUndoRequest) {
        clearUndo()
        mutable.value = mutable.value.copy(pendingUndoRequest = request.requester, canUndo = false, error = null,
            status = if (request.requester == mutable.value.localPlayer) "等待伙伴同意悔棋…" else "伙伴想退回一步，等你回复")
        val token = generation
        undoTask = Runnable { if (token == generation && history.pending == request) cancelUndo(request, XiangqiUndoResolution.TIMEOUT, hosting) }
            .also { main.postDelayed(it, if (hosting) 20_000 else 25_000) }
    }
    private fun cancelUndo(request: GomokuUndoRequest, resolution: XiangqiUndoResolution, notify: Boolean) {
        if (!history.cancel(request)) return
        clearUndo()
        mutable.value = mutable.value.copy(pendingUndoRequest = null, canUndo = mutable.value.connected && !mutable.value.awaitingAck && history.canUndo,
            error = resolution.hint, status = turnStatus(mutable.value.game))
        if (notify) send(GomokuRoomMessage.UndoResult(request, resolution))
    }
    private fun commitUndo(request: GomokuUndoRequest, responder: Int) {
        val current = mutable.value
        val target = history.commitHost(request, responder, current.revision, current.game) ?: return
        clearUndo();clearVotes()
        val revision = current.revision + 1
        mutable.value = current.copy(game = target, revision = revision, canUndo = history.canUndo,
            pendingUndoRequest = null, error = null,rematchRequestedBy=null,myRematchRequested=false,
            resultSecondsLeft=if(target.outcome==GomokuOutcome.PLAYING) 0 else current.resultSecondsLeft,status = turnStatus(target))
        send(GomokuRoomMessage.UndoSnapshot(request, GomokuRoomMessage.Snapshot(revision, target)))
        if(target.outcome==GomokuOutcome.PLAYING) clearRound()
    }
    private fun send(message: GomokuRoomMessage) { wire?.send(GomokuRoomProtocol.encode(message)) }
    private fun turnStatus(game: GomokuState): String = when (game.outcome) {
        GomokuOutcome.HUMAN_WON -> "黑棋获胜"
        GomokuOutcome.CPU_WON -> "白棋获胜"
        GomokuOutcome.DRAW -> "这一局平分秋色"
        GomokuOutcome.PLAYING -> if (game.currentPlayer == mutable.value.localPlayer) "轮到你落子" else "等待伙伴落子"
    }
    private fun startHeartbeat(token: Int) {
        heartbeat = object : Runnable { override fun run() {
            if (token != generation || wire == null) return
            if (SystemClock.uptimeMillis() - lastPacket > 20_000) { fail("伙伴的连接已断开，可以重新创建房间"); return }
            send(GomokuRoomMessage.Ping); main.postDelayed(this, 5_000)
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
    private fun clearUndo() { undoTask?.let(main::removeCallbacks); undoTask = null }
    private fun fail(message: String) { finishRoom(message,null); mutable.value = mutable.value.copy(error = message) }
    fun close() {
        finishRoom("连接已关闭",RoomCloseReason.LEFT)
    }
    private fun finishRoom(message:String,notify:RoomCloseReason?) {
        if(notify!=null && wire!=null) send(GomokuRoomMessage.Control(RoomControl.Close(assignment?.round ?: 0,mutable.value.revision,notify)))
        val notifyPeer = notify != null && (initialized || mutable.value.awaitingMatch)
        val old=wire;wire=null
        generation++; handshake.close(); clearWaiting(); clearAck(); clearUndo(); heartbeat?.let(main::removeCallbacks); heartbeat = null
        clearRound();initialized = false; history = GomokuUndoHistory();localVoteSequence=0;guestVoteSequence=0;pendingStart=null;nearbyId=null
        if(!notifyPeer) old?.close() else main.postDelayed({old?.close()},200)
        mutable.value = mutable.value.copy(localPlayer = if(assignment!=null) mutable.value.localPlayer else null, hostAddress = "", connected = false, sessionActive = false,
            awaitingAck = false, busy = false, canUndo = false, pendingUndoRequest = null, status = message, error = null,
            pendingMatchName=null,awaitingMatch=false,roomEnded=assignment!=null,myRematchRequested=false,rematchRequestedBy=null)
        assignment=null
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
            is RoomControl.Close -> {
                if(control.round < (assignment?.round ?: 0) || control.revision<current.revision) return
                finishRoom(control.reason.hint,null)
            }
        }
    }
    private fun beginRound(next:RoomAssignment) {
        val current=mutable.value
        clearWaiting();handshake.close();clearAck();clearUndo();clearRound()
        history=GomokuUndoHistory();localVoteSequence=0;guestVoteSequence=0;assignment=next;initialized=true;pendingStart=null;nearbyId=null
        mutable.value=current.copy(game=GomokuEngine.newGame(),revision=next.revision,localPlayer=next.hostPlayer,
            round=next.round,isHost=true,connected=true,awaitingMatch=false,pendingMatchName=null,busy=false,awaitingAck=false,
            pendingUndoRequest=null,canUndo=false,rematchRequestedBy=null,myRematchRequested=false,resultSecondsLeft=0,roomEnded=false,error=null,status="棋友已就位，黑棋先行")
        send(GomokuRoomMessage.Control(RoomControl.Start(next)))
        send(GomokuRoomMessage.Snapshot(next.revision,GomokuEngine.newGame()))
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
            if(current.game.outcome!=GomokuOutcome.PLAYING) finishRoom(RoomCloseReason.NO_REMATCH.hint,RoomCloseReason.NO_REMATCH)
            else {clearVotes();showVotes();sendVotes()}
            return
        }
        if(fromHost) hostReady=true else guestReady=true
        showVotes();sendVotes()
        val next=RoomRoundRules.next(round,current.revision,hostReady,guestReady)
        if(next!=null) beginRound(next)
        else if(current.game.outcome==GomokuOutcome.PLAYING && voteTask==null) {
            val token=generation
            voteTask=Runnable {if(token==generation) {clearVotes();showVotes();sendVotes()}}
                .also {main.postDelayed(it,20_000)}
        }
    }
    private fun showVotes() {
        val current=mutable.value;val round=assignment ?: return
        val who=if(hostReady) round.hostPlayer else if(guestReady || !hosting && localRematchIntent) 3-round.hostPlayer else null
        mutable.value=current.copy(rematchRequestedBy=who,myRematchRequested=if(hosting) hostReady else localRematchIntent,
            canUndo=who==null && history.canUndo,status=if(who==null) turnStatus(current.game) else "等棋友同意，再摆一盘")
    }
    private fun sendVotes() {assignment?.let {send(GomokuRoomMessage.Control(RoomControl.Votes(it.round,mutable.value.revision,hostReady,guestReady,guestVoteSequence)))}}
    private fun enterResult() {
        if(resultDeadline!=0L) return
        resultDeadline=SystemClock.uptimeMillis()+RoomRoundRules.RESULT_SECONDS*1_000L
        val token=generation
        roundTask=object:Runnable {override fun run() {
            if(token!=generation || resultDeadline==0L) return
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
}

internal data class GomokuUndoRequest(val revision: Int, val id: Int, val requester: Int)
internal sealed interface GomokuRoomMessage {
    data object Hello : GomokuRoomMessage
    data object Ping : GomokuRoomMessage
    data object Pong : GomokuRoomMessage
    data object Reject : GomokuRoomMessage
    data class Move(val revision: Int, val cell: GridCell) : GomokuRoomMessage
    data class Snapshot(val revision: Int, val game: GomokuState) : GomokuRoomMessage
    data class UndoRequest(val request: GomokuUndoRequest) : GomokuRoomMessage
    data class UndoResponse(val revision: Int, val id: Int, val requester: Int, val accept: Boolean) : GomokuRoomMessage
    data class UndoResult(val request: GomokuUndoRequest, val resolution: XiangqiUndoResolution) : GomokuRoomMessage
    data class UndoSnapshot(val request: GomokuUndoRequest, val snapshot: Snapshot) : GomokuRoomMessage
    data class Control(val value: RoomControl) : GomokuRoomMessage
}

internal object GomokuRoomProtocol {
    fun encode(message: GomokuRoomMessage): String = when (message) {
        GomokuRoomMessage.Hello -> "GO1|HELLO"
        GomokuRoomMessage.Ping -> "GO1|PING"
        GomokuRoomMessage.Pong -> "GO1|PONG"
        GomokuRoomMessage.Reject -> "GO1|REJECT"
        is GomokuRoomMessage.Move -> "GO1|MOVE|${message.revision}|${index(message.cell)}"
        is GomokuRoomMessage.Snapshot -> with(message.game) {
            require(size == 15)
            "GO1|STATE|${message.revision}|$currentPlayer|${outcome.name}|${lastMove?.let(::index) ?: -1}|${board.joinToString(",")}"
        }
        is GomokuRoomMessage.UndoRequest -> with(message.request) { "GO1|UNDO_REQUEST|$revision|$id|$requester" }
        is GomokuRoomMessage.UndoResponse -> "GO1|UNDO_RESPONSE|${message.revision}|${message.id}|${message.requester}|${if (message.accept) 1 else 0}"
        is GomokuRoomMessage.UndoResult -> with(message.request) { "GO1|UNDO_RESULT|$revision|$id|$requester|${message.resolution.name}" }
        is GomokuRoomMessage.UndoSnapshot -> with(message.request) {
            "GO1|UNDO_STATE|$revision|$id|$requester|" + encode(message.snapshot).split('|').drop(2).joinToString("|")
        }
        is GomokuRoomMessage.Control -> "GO1|ROOM|" + RoomControlCodec.encode(message.value)
    }
    fun decode(line: String): GomokuRoomMessage {
        if (line.length > 1_024 || line.any { it.code !in 32..126 }) throw LanProtocolException()
        val p = line.split('|')
        if (p.size < 2 || p[0] != "GO1") throw LanProtocolException()
        return try { when (p[1]) {
            "HELLO" -> { require(p.size == 2); GomokuRoomMessage.Hello }
            "ROOM" -> {require(p.size>=3);GomokuRoomMessage.Control(RoomControlCodec.decode(p.drop(2).joinToString("|")))}
            "PING" -> { require(p.size == 2); GomokuRoomMessage.Ping }
            "PONG" -> { require(p.size == 2); GomokuRoomMessage.Pong }
            "REJECT" -> { require(p.size == 2); GomokuRoomMessage.Reject }
            "MOVE" -> { require(p.size == 4); GomokuRoomMessage.Move(int(p[2], 0..Int.MAX_VALUE), cell(int(p[3], 0..224))) }
            "STATE" -> {
                require(p.size == 7)
                val revision = int(p[2], 0..Int.MAX_VALUE); val player = int(p[3], 1..2)
                val outcome = GomokuOutcome.valueOf(p[4]); val last = int(p[5], -1..224)
                val board = p[6].split(',').map { int(it, 0..2) }; require(board.size == 225)
                val black = board.count { it == 1 }; val white = board.count { it == 2 }
                require(black == white || black == white + 1)
                require(if (black + white == 0) last == -1 else last >= 0)
                when (outcome) {
                    GomokuOutcome.PLAYING -> require(player == if (black == white) 1 else 2)
                    GomokuOutcome.HUMAN_WON -> require(player == 1 && black == white + 1)
                    GomokuOutcome.CPU_WON -> require(player == 2 && black == white)
                    GomokuOutcome.DRAW -> require(black + white == 225 && player == 1)
                }
                if (last >= 0) require(board[last] == if (outcome == GomokuOutcome.PLAYING) 3 - player else player)
                GomokuRoomMessage.Snapshot(revision, GomokuState(board = board, currentPlayer = player, outcome = outcome,
                    lastMove = if (last < 0) null else cell(last)))
            }
            "UNDO_REQUEST" -> { require(p.size == 5); GomokuRoomMessage.UndoRequest(request(p)) }
            "UNDO_RESPONSE" -> { require(p.size == 6); GomokuRoomMessage.UndoResponse(int(p[2], 0..Int.MAX_VALUE), int(p[3], 1..Int.MAX_VALUE), int(p[4], 1..2), int(p[5], 0..1) == 1) }
            "UNDO_RESULT" -> { require(p.size == 6); GomokuRoomMessage.UndoResult(request(p), XiangqiUndoResolution.valueOf(p[5])) }
            "UNDO_STATE" -> {
                require(p.size == 10)
                val request = request(p); val snapshot = decode("GO1|STATE|" + p.drop(5).joinToString("|")) as GomokuRoomMessage.Snapshot
                require(request.revision < Int.MAX_VALUE && snapshot.revision == request.revision + 1)
                GomokuRoomMessage.UndoSnapshot(request, snapshot)
            }
            else -> throw LanProtocolException()
        } } catch (_: IllegalArgumentException) { throw LanProtocolException() }
    }
    fun acceptsSnapshot(revision: Int, game: GomokuState, initialized: Boolean, next: GomokuRoomMessage.Snapshot,allowRestart:Boolean=true): Boolean {
        if (!initialized) return next.revision == 0 && next.game == GomokuEngine.newGame()
        if (next.revision == revision) return next.game == game
        if (revision == Int.MAX_VALUE || next.revision != revision + 1) return false
        return allowRestart && next.game == GomokuEngine.newGame() || next.game.lastMove?.let { GomokuEngine.play(game, it.x, it.y) == next.game } == true
    }
    private fun int(value: String, range: IntRange): Int { require(value.length in 1..11 && value.all { it in '0'..'9' || it == '-' });
        return value.toInt().also { require(it in range) } }
    private fun request(p: List<String>) = GomokuUndoRequest(int(p[2], 0..Int.MAX_VALUE), int(p[3], 1..Int.MAX_VALUE), int(p[4], 1..2))
    private fun index(cell: GridCell): Int { require(cell.x in 0..14 && cell.y in 0..14); return cell.y * 15 + cell.x }
    private fun cell(index: Int) = GridCell(index % 15, index / 15)
}

/** Consent binds an exact revision, unchanged board, requester, and known previous position. */
internal class GomokuUndoHistory {
    private val previous = ArrayDeque<GomokuState>()
    private val seen = IntArray(3)
    private var localId = 0
    private var position: GomokuState? = null
    private var consent = false
    var pending: GomokuUndoRequest? = null
        private set
    val canUndo: Boolean get() = previous.isNotEmpty() && pending == null
    fun record(current: GomokuState, next: GomokuState): Boolean {
        if (next == GomokuEngine.newGame()) previous.clear() else {
            if (next.lastMove?.let { GomokuEngine.play(current, it.x, it.y) == next } != true) return false
            if (previous.size == 128) previous.removeFirst()
            previous.addLast(current)
        }
        clear(); return true
    }
    fun begin(revision: Int, game: GomokuState, player: Int): GomokuUndoRequest? {
        if (!canUndo || player !in 1..2 || revision == Int.MAX_VALUE || localId == Int.MAX_VALUE) return null
        val request = GomokuUndoRequest(revision, ++localId, player); seen[player] = request.id
        bind(request, game); return request
    }
    fun offer(request: GomokuUndoRequest, revision: Int, game: GomokuState, localPlayer: Int, host: Boolean): XiangqiUndoOffer {
        if (request.requester != 3 - localPlayer || request.id <= 0 || request.revision != revision || revision == Int.MAX_VALUE) return XiangqiUndoOffer.STALE
        if (pending == request && position == game) return XiangqiUndoOffer.DUPLICATE
        if (request.id <= seen[request.requester]) return XiangqiUndoOffer.STALE
        seen[request.requester] = request.id
        if (pending != null && (host || pending?.requester != localPlayer)) return XiangqiUndoOffer.BUSY
        if (previous.isEmpty()) return XiangqiUndoOffer.EMPTY
        bind(request, game); return XiangqiUndoOffer.ACCEPTED
    }
    fun consent(revision: Int, game: GomokuState, player: Int): Boolean {
        val request = pending ?: return false
        if (request.requester == player || request.revision != revision || position != game) return false
        consent = true; return true
    }
    fun commitHost(request: GomokuUndoRequest, responder: Int, revision: Int, game: GomokuState): GomokuState? {
        if (pending != request || responder != 3 - request.requester || request.revision != revision ||
            revision == Int.MAX_VALUE || position != game || previous.isEmpty()) return null
        val target = previous.removeLast(); clear(); return target
    }
    fun acceptsGuest(player: Int, revision: Int, game: GomokuState, next: GomokuRoomMessage.UndoSnapshot): Boolean =
        pending == next.request && next.request.revision == revision && revision < Int.MAX_VALUE && position == game &&
            next.snapshot.revision == revision + 1 && previous.isNotEmpty() && previous.last() == next.snapshot.game &&
            (next.request.requester == player || consent)
    fun commitGuest(player: Int, revision: Int, game: GomokuState, next: GomokuRoomMessage.UndoSnapshot): Boolean {
        if (!acceptsGuest(player, revision, game, next)) return false
        previous.removeLast(); clear(); return true
    }
    fun cancel(request: GomokuUndoRequest): Boolean { if (pending != request) return false; clear(); return true }
    private fun bind(request: GomokuUndoRequest, game: GomokuState) { pending = request; position = game; consent = false }
    private fun clear() { pending = null; position = null; consent = false }
}
