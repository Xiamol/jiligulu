package com.jiligulu.app.ui.littleworld

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

/** Explicit foreground-only internet rooms: bundled PeerJS, encrypted WebRTC data channel. */
class XiangqiOnlineSession(private val context:Context) {
    private val mutable=MutableStateFlow(XiangqiLanUiState(status="互联网房间：创建后把房间码告诉伙伴"))
    val state:StateFlow<XiangqiLanUiState> = mutable.asStateFlow()
    private val main=Handler(Looper.getMainLooper())
    private var web:WebView?=null
    val transportView:WebView? get()=web
    private var generation=0
    private var hosting=false
    private var waitingDeadline:Runnable?=null
    private var ackDeadline:Runnable?=null
    private var heartbeat:Runnable?=null
    private var lastPacket=0L
    private var initialized=false
    private val selectionHints=XiangqiSelectionHints()
    private var selectionTask:Runnable?=null
    private var undoHistory=XiangqiUndoHistory()
    private var undoDeadline:Runnable?=null
    private val handshake=RoomHandshakeTimeout()
    fun host()=start(UUID.randomUUID().toString().replace("-","").take(12).uppercase(),true)
    fun join(address:String) {
        val code=address.trim().uppercase()
        if(!Regex("[A-F0-9]{12}").matches(code)) {mutable.value=mutable.value.copy(error="请输入伙伴的 12 位房间码");return}
        start(code,false)
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun start(code:String,isHost:Boolean) {
        close();hosting=isHost;initialized=false
        val token=++generation
        handshake.begin(SystemClock.uptimeMillis(),if(isHost) 120_000 else 35_000)
        mutable.value=XiangqiLanUiState(localSide=if(isHost) XiangqiSide.RED else XiangqiSide.BLACK,
            hostAddress=code,busy=true,sessionActive=true,status="正在连接互联网房间…")
        val view=WebView(context)
        web=view
        view.settings.apply {javaScriptEnabled=true;allowFileAccess=false;allowContentAccess=false;
            javaScriptCanOpenWindowsAutomatically=false;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW}
        view.addJavascriptInterface(object {
            @JavascriptInterface fun event(type:String,value:String) {main.post {if(token==generation) receive(type,value)}}
        },"GuluTransport")
        view.webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(v:WebView?,request:WebResourceRequest?)=true
            override fun shouldInterceptRequest(v:WebView?,request:WebResourceRequest?):WebResourceResponse? {
                if(request?.url.toString()=="https://appassets.androidplatform.net/peerjs.min.js")
                    return WebResourceResponse("application/javascript","UTF-8",context.assets.open("chess-online/peerjs.min.js"))
                return null
            }
            override fun onPageFinished(v:WebView?,url:String?) {
                if(token==generation) view.evaluateJavascript("guluStart(${JSONObject.quote(code)},$isHost)",null)
            }
        }
        val html=context.assets.open("chess-online/transport.html").bufferedReader().use {it.readText()}
        view.loadDataWithBaseURL("https://appassets.androidplatform.net/",html,"text/html","UTF-8",null)
        scheduleWaiting(token)
    }
    private fun receive(type:String,value:String) {
        if(!mutable.value.sessionActive) return
        when(type) {
            "ready"->mutable.value=mutable.value.copy(status=if(hosting) "房间已创建，等待伙伴加入" else "正在寻找伙伴…")
            "connected"->{if(mutable.value.connected || handshake.waitingForState) return
                waitingDeadline?.let(main::removeCallbacks);waitingDeadline=null
                lastPacket=SystemClock.uptimeMillis();handshake.linkReady(lastPacket,hosting);startHeartbeat()
                mutable.value=mutable.value.copy(connected=hosting,busy=!hosting,error=null,
                    status=if(hosting) "伙伴已连接，红方先行" else "伙伴已连接，正在同步棋局…")
                if(!hosting) scheduleWaiting(generation)
                if(hosting) send(XiangqiLanMessage.Snapshot(mutable.value.revision,mutable.value.game)) else send(XiangqiLanMessage.Hello)
            }
            "data"->runCatching {val message=XiangqiLanProtocol.decode(value);lastPacket=SystemClock.uptimeMillis();receiveWire(message)}
                .onFailure {fail("收到无效棋局数据，连接已关闭")}
            "closed"->fail("伙伴已离开，可重新创建房间")
            "warning"->mutable.value=mutable.value.copy(status="棋局仍连接，房间信令暂时离线")
            "error"->fail(when(value){"peer-unavailable"->"找不到房间，请确认房间码和伙伴状态";"browser-incompatible"->"系统 WebView 暂不支持远程对局，可用局域网";else->"互联网连接未成功，可用局域网或同机对局"})
        }
    }
    private fun receiveWire(message:XiangqiLanMessage) {
        val current=mutable.value
        when(message) {
            XiangqiLanMessage.Hello->if(hosting) send(XiangqiLanMessage.Snapshot(current.revision,current.game))
            is XiangqiLanMessage.Move->if(hosting) {
                when {
                    message.revision!=current.revision->send(XiangqiLanMessage.Reject(XiangqiLanRejection.STALE))
                    current.pendingUndoRequest!=null->send(XiangqiLanMessage.Reject(XiangqiLanRejection.UNDO_PENDING))
                    current.game.turnSide!=XiangqiSide.BLACK->send(XiangqiLanMessage.Reject(XiangqiLanRejection.TURN))
                    else->{val next=XiangqiEngine.play(current.game,message.move)
                        if(next===current.game) send(XiangqiLanMessage.Reject(XiangqiLanRejection.ILLEGAL)) else publish(next)}
                }
            }
            is XiangqiLanMessage.Snapshot->if(!hosting) {
                if(!XiangqiSnapshotRules.accepts(current.revision,current.game,initialized,message)) {fail("棋局同步顺序不正确，连接已关闭");return}
                initialized=true
                handshake.validatedState();waitingDeadline?.let(main::removeCallbacks);waitingDeadline=null
                ackDeadline?.let(main::removeCallbacks);ackDeadline=null
                val changed=message.revision!=current.revision || message.game!=current.game
                if(changed) {
                    clearSelectionHints();clearUndoDeadline()
                    if(!undoHistory.recordAdvance(current.game,message.game)) {fail("悔棋历史与棋局不一致，连接已关闭");return}
                }
                mutable.value=current.copy(game=message.game,revision=message.revision,connected=true,busy=false,awaitingAck=false,error=null,status="棋局已同步",
                    remoteSelection=if(changed) null else current.remoteSelection,
                    pendingUndoRequest=undoHistory.pending?.requester,canUndo=undoHistory.canUndo)
            }
            is XiangqiLanMessage.Reject->{ackDeadline?.let(main::removeCallbacks);ackDeadline=null
                mutable.value=current.copy(awaitingAck=false,remoteSelection=null,canUndo=undoHistory.canUndo,error=message.reason.hint)}
            is XiangqiLanMessage.Select->if(current.connected &&
                current.pendingUndoRequest==null &&
                XiangqiSelectionRules.accepts(current.revision,current.game,current.localSide,message) && current.remoteSelection!=message.cell) {
                mutable.value=current.copy(remoteSelection=message.cell)
            }
            is XiangqiLanMessage.UndoRequest->{
                val side=current.localSide ?: return
                if(!current.connected || message.request.requester!=side.opponent) throw LanProtocolException()
                val offer=undoHistory.receiveOffer(message.request,current.revision,current.game,side,host=hosting)
                when(offer) {
                    XiangqiUndoOffer.ACCEPTED->showUndoRequest(message.request)
                    XiangqiUndoOffer.DUPLICATE->Unit
                    else->if(hosting) send(XiangqiLanMessage.UndoResult(message.request,when(offer) {
                        XiangqiUndoOffer.BUSY->XiangqiUndoResolution.BUSY
                        XiangqiUndoOffer.EMPTY->XiangqiUndoResolution.EMPTY
                        else->XiangqiUndoResolution.STALE
                    })) else if(offer!=XiangqiUndoOffer.STALE) throw LanProtocolException()
                }
            }
            is XiangqiLanMessage.UndoResponse->{
                if(!hosting || !current.connected) throw LanProtocolException()
                val request=undoHistory.pending ?: return
                if(request.revision!=message.revision || request.id!=message.id || request.requester!=message.requester || request.requester!=XiangqiSide.RED) return
                if(message.accept) commitHostUndo(request,XiangqiSide.BLACK)
                else cancelUndo(request,XiangqiUndoResolution.REJECTED,notify=true)
            }
            is XiangqiLanMessage.UndoResult->{
                if(hosting || !current.connected) throw LanProtocolException()
                cancelUndo(message.request,message.resolution,notify=false)
            }
            is XiangqiLanMessage.UndoSnapshot->{
                if(hosting || !current.connected || !XiangqiSnapshotRules.acceptsUndo(current.revision,current.game,
                        initialized,XiangqiSide.BLACK,undoHistory,message) ||
                    !undoHistory.commitGuestUndo(XiangqiSide.BLACK,current.revision,current.game,message)) throw LanProtocolException()
                clearUndoDeadline();clearSelectionHints()
                ackDeadline?.let(main::removeCallbacks);ackDeadline=null
                mutable.value=current.copy(game=message.snapshot.game,revision=message.snapshot.revision,
                    awaitingAck=false,pendingUndoRequest=null,canUndo=undoHistory.canUndo,remoteSelection=null,
                    error=null,status="双方已同意，退回一步")
            }
            XiangqiLanMessage.Ping->send(XiangqiLanMessage.Pong)
            XiangqiLanMessage.Pong->Unit
        }
    }
    fun submitMove(move:XiangqiMove) {
        val current=mutable.value
        if(!current.connected||current.awaitingAck||current.pendingUndoRequest!=null||current.game.turnSide!=current.localSide) return
        val next=XiangqiEngine.play(current.game,move)
        if(next===current.game) {mutable.value=current.copy(error="这一步不符合象棋规则");return}
        if(hosting) publish(next) else {
            clearSelectionHints()
            mutable.value=current.copy(awaitingAck=true,canUndo=false,remoteSelection=null,error=null,status="等待房主确认落子…")
            send(XiangqiLanMessage.Move(current.revision,move))
            val token=generation
            ackDeadline=Runnable {if(token==generation&&mutable.value.awaitingAck) fail("落子确认超时，请重新连接")}.also {main.postDelayed(it,8_000)}
        }
    }
    /** Latest-only cosmetic hint. The authoritative move/snapshot path remains separate. */
    fun selectPiece(cell:GridCell?) {
        val current=mutable.value
        val side=current.localSide ?: return
        if(!current.connected || current.awaitingAck || current.pendingUndoRequest!=null || !XiangqiSelectionRules.canSelect(current.game,side,cell)) return
        if(!selectionHints.offer(XiangqiLanMessage.Select(current.revision,cell)) || selectionTask!=null) return
        val token=generation
        selectionTask=Runnable {
            selectionTask=null
            if(token!=generation) return@Runnable
            val hint=selectionHints.take() ?: return@Runnable
            val latest=mutable.value
            if(latest.connected && !latest.awaitingAck && latest.pendingUndoRequest==null && latest.revision==hint.revision &&
                latest.localSide?.let {XiangqiSelectionRules.canSelect(latest.game,it,hint.cell)}==true) send(hint)
        }.also {main.postDelayed(it,100)}
    }
    private fun clearSelectionHints() {
        selectionTask?.let(main::removeCallbacks);selectionTask=null;selectionHints.clear()
    }

    fun requestUndo() {
        val current=mutable.value
        val side=current.localSide ?: return
        if(!current.connected || current.awaitingAck || current.pendingUndoRequest!=null) return
        val request=undoHistory.beginLocal(current.revision,current.game,side) ?: return
        showUndoRequest(request)
        send(XiangqiLanMessage.UndoRequest(request))
    }

    fun respondToUndo(accept:Boolean) {
        val current=mutable.value
        val side=current.localSide ?: return
        val request=undoHistory.pending ?: return
        if(!current.connected || request.requester==side || request.revision!=current.revision) return
        if(accept && !undoHistory.consentLocally(current.revision,current.game,side)) return
        if(hosting) {
            if(accept) commitHostUndo(request,side) else cancelUndo(request,XiangqiUndoResolution.REJECTED,notify=true)
        } else {
            mutable.value=current.copy(status="等待房主同步悔棋结果…")
            send(XiangqiLanMessage.UndoResponse(request.revision,request.id,request.requester,accept))
        }
    }

    private fun showUndoRequest(request:XiangqiUndoRequest) {
        clearSelectionHints();clearUndoDeadline()
        mutable.value=mutable.value.copy(pendingUndoRequest=request.requester,canUndo=false,remoteSelection=null,error=null,
            status=if(request.requester==mutable.value.localSide) "等待伙伴同意悔棋…" else "伙伴想退回一步，等你回复")
        val token=generation
        undoDeadline=Runnable {
            if(token==generation && undoHistory.pending==request) cancelUndo(request,XiangqiUndoResolution.TIMEOUT,notify=hosting)
        }.also {main.postDelayed(it,if(hosting) 20_000 else 25_000)}
    }

    private fun clearUndoDeadline() {undoDeadline?.let(main::removeCallbacks);undoDeadline=null}

    private fun cancelUndo(request:XiangqiUndoRequest,resolution:XiangqiUndoResolution,notify:Boolean) {
        if(!undoHistory.cancelIfMatches(request)) return
        clearUndoDeadline()
        mutable.value=mutable.value.copy(pendingUndoRequest=null,
            canUndo=mutable.value.connected && !mutable.value.awaitingAck && undoHistory.canUndo,
            error=resolution.hint,status="棋局已保持原位")
        if(notify) send(XiangqiLanMessage.UndoResult(request,resolution))
    }

    private fun commitHostUndo(request:XiangqiUndoRequest,responder:XiangqiSide) {
        val current=mutable.value
        val target=undoHistory.commitHost(request,responder,current.revision,current.game) ?: return
        clearUndoDeadline();clearSelectionHints()
        val revision=current.revision+1
        mutable.value=current.copy(game=target,revision=revision,pendingUndoRequest=null,
            canUndo=undoHistory.canUndo,remoteSelection=null,error=null,status="双方已同意，退回一步")
        send(XiangqiLanMessage.UndoSnapshot(request,XiangqiLanMessage.Snapshot(revision,target)))
    }
    fun restart() {if(mutable.value.connected&&hosting) publish(XiangqiEngine.newGame())}
    private fun publish(game:XiangqiState) {
        clearSelectionHints()
        if(mutable.value.revision==Int.MAX_VALUE) {fail("对局过长，请重新创建房间");return}
        undoHistory.pending?.let {cancelUndo(it,XiangqiUndoResolution.CANCELLED,notify=true)}
        if(!undoHistory.recordAdvance(mutable.value.game,game)) {fail("悔棋历史与棋局不一致，连接已关闭");return}
        val revision=mutable.value.revision+1
        mutable.value=mutable.value.copy(game=game,revision=revision,remoteSelection=null,error=null,status="棋局已同步",
            pendingUndoRequest=null,canUndo=undoHistory.canUndo)
        send(XiangqiLanMessage.Snapshot(revision,game))
    }
    private fun send(message:XiangqiLanMessage) {
        val line=XiangqiLanProtocol.encode(message)
        web?.evaluateJavascript("guluSend(${JSONObject.quote(line)})",null)
    }
    private fun startHeartbeat() {
        val token=generation
        heartbeat=object:Runnable {override fun run() {
            if(token!=generation||!mutable.value.sessionActive||web==null) return
            if(SystemClock.uptimeMillis()-lastPacket>20_000) {fail("伙伴的网络已断开，可重新创建房间");return}
            send(XiangqiLanMessage.Ping);main.postDelayed(this,5_000)
        }}.also {main.postDelayed(it,5_000)}
    }
    private fun scheduleWaiting(token:Int) {
        waitingDeadline?.let(main::removeCallbacks);waitingDeadline=null
        if(!handshake.active) return
        waitingDeadline=Runnable {
            if(token==generation && handshake.expired(SystemClock.uptimeMillis()))
                fail(if(handshake.waitingForState) "棋局同步超时，请重新连接房间" else "连接超时，可以改用局域网或同机对局")
        }.also {main.postDelayed(it,handshake.remaining(SystemClock.uptimeMillis()))}
    }
    private fun fail(message:String) {close();mutable.value=mutable.value.copy(error=message,status=message)}
    fun close() {
        handshake.close()
        clearUndoDeadline();undoHistory=XiangqiUndoHistory()
        clearSelectionHints()
        generation++;waitingDeadline?.let(main::removeCallbacks);ackDeadline?.let(main::removeCallbacks)
        heartbeat?.let(main::removeCallbacks);heartbeat=null
        waitingDeadline=null;ackDeadline=null
        web?.let {view->runCatching {view.evaluateJavascript("guluStop()",null);view.stopLoading();view.removeJavascriptInterface("GuluTransport");
            (view.parent as?ViewGroup)?.removeView(view);view.destroy()}}
        web=null
        mutable.value=mutable.value.copy(localSide=null,hostAddress="",connected=false,busy=false,awaitingAck=false,sessionActive=false,
            remoteSelection=null,pendingUndoRequest=null,canUndo=false,status="连接已关闭",error=null)
    }
}
