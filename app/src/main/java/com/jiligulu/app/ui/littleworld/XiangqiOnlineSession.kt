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
        waitingDeadline=Runnable {if(token==generation&&!mutable.value.connected) fail("连接超时，可以改用局域网或同机对局")}.also {main.postDelayed(it,if(isHost) 120_000 else 35_000)}
    }
    private fun receive(type:String,value:String) {
        if(!mutable.value.sessionActive) return
        when(type) {
            "ready"->mutable.value=mutable.value.copy(status=if(hosting) "房间已创建，等待伙伴加入" else "正在寻找伙伴…")
            "connected"->{waitingDeadline?.let(main::removeCallbacks);waitingDeadline=null
                lastPacket=SystemClock.uptimeMillis();startHeartbeat()
                mutable.value=mutable.value.copy(connected=true,busy=false,error=null,status="伙伴已连接，红方先行")
                if(hosting) send(XiangqiLanMessage.Snapshot(mutable.value.revision,mutable.value.game)) else send(XiangqiLanMessage.Hello)
            }
            "data"->runCatching {XiangqiLanProtocol.decode(value)}.onSuccess {lastPacket=SystemClock.uptimeMillis();receiveWire(it)}.onFailure {fail("收到无效棋局数据，连接已关闭")}
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
                    current.game.turnSide!=XiangqiSide.BLACK->send(XiangqiLanMessage.Reject(XiangqiLanRejection.TURN))
                    else->{val next=XiangqiEngine.play(current.game,message.move)
                        if(next===current.game) send(XiangqiLanMessage.Reject(XiangqiLanRejection.ILLEGAL)) else publish(next)}
                }
            }
            is XiangqiLanMessage.Snapshot->if(!hosting) {
                if(!XiangqiSnapshotRules.accepts(current.revision,current.game,initialized,message)) {fail("棋局同步顺序不正确，连接已关闭");return}
                initialized=true
                ackDeadline?.let(main::removeCallbacks);ackDeadline=null
                val changed=message.revision!=current.revision || message.game!=current.game
                if(changed) clearSelectionHints()
                mutable.value=current.copy(game=message.game,revision=message.revision,awaitingAck=false,error=null,status="棋局已同步",
                    remoteSelection=if(changed) null else current.remoteSelection)
            }
            is XiangqiLanMessage.Reject->{ackDeadline?.let(main::removeCallbacks);ackDeadline=null
                mutable.value=current.copy(awaitingAck=false,remoteSelection=null,error="这一步未被房主接受，棋局已保持原位")}
            is XiangqiLanMessage.Select->if(current.connected &&
                XiangqiSelectionRules.accepts(current.revision,current.game,current.localSide,message) && current.remoteSelection!=message.cell) {
                mutable.value=current.copy(remoteSelection=message.cell)
            }
            XiangqiLanMessage.Ping->send(XiangqiLanMessage.Pong)
            XiangqiLanMessage.Pong->Unit
        }
    }
    fun submitMove(move:XiangqiMove) {
        val current=mutable.value
        if(!current.connected||current.awaitingAck||current.game.turnSide!=current.localSide) return
        val next=XiangqiEngine.play(current.game,move)
        if(next===current.game) {mutable.value=current.copy(error="这一步不符合象棋规则");return}
        if(hosting) publish(next) else {
            clearSelectionHints()
            mutable.value=current.copy(awaitingAck=true,remoteSelection=null,error=null,status="等待房主确认落子…")
            send(XiangqiLanMessage.Move(current.revision,move))
            val token=generation
            ackDeadline=Runnable {if(token==generation&&mutable.value.awaitingAck) fail("落子确认超时，请重新连接")}.also {main.postDelayed(it,8_000)}
        }
    }
    /** Latest-only cosmetic hint. The authoritative move/snapshot path remains separate. */
    fun selectPiece(cell:GridCell?) {
        val current=mutable.value
        val side=current.localSide ?: return
        if(!current.connected || current.awaitingAck || !XiangqiSelectionRules.canSelect(current.game,side,cell)) return
        if(!selectionHints.offer(XiangqiLanMessage.Select(current.revision,cell)) || selectionTask!=null) return
        val token=generation
        selectionTask=Runnable {
            selectionTask=null
            if(token!=generation) return@Runnable
            val hint=selectionHints.take() ?: return@Runnable
            val latest=mutable.value
            if(latest.connected && !latest.awaitingAck && latest.revision==hint.revision &&
                latest.localSide?.let {XiangqiSelectionRules.canSelect(latest.game,it,hint.cell)}==true) send(hint)
        }.also {main.postDelayed(it,100)}
    }
    private fun clearSelectionHints() {
        selectionTask?.let(main::removeCallbacks);selectionTask=null;selectionHints.clear()
    }
    fun restart() {if(mutable.value.connected&&hosting) publish(XiangqiEngine.newGame())}
    private fun publish(game:XiangqiState) {
        clearSelectionHints()
        if(mutable.value.revision==Int.MAX_VALUE) {fail("对局过长，请重新创建房间");return}
        val revision=mutable.value.revision+1
        mutable.value=mutable.value.copy(game=game,revision=revision,remoteSelection=null,error=null,status="棋局已同步")
        send(XiangqiLanMessage.Snapshot(revision,game))
    }
    private fun send(message:XiangqiLanMessage) {
        val line=XiangqiLanProtocol.encode(message)
        web?.evaluateJavascript("guluSend(${JSONObject.quote(line)})",null)
    }
    private fun startHeartbeat() {
        val token=generation
        heartbeat=object:Runnable {override fun run() {
            if(token!=generation||!mutable.value.connected) return
            if(SystemClock.uptimeMillis()-lastPacket>20_000) {fail("伙伴的网络已断开，可重新创建房间");return}
            send(XiangqiLanMessage.Ping);main.postDelayed(this,5_000)
        }}.also {main.postDelayed(it,5_000)}
    }
    private fun fail(message:String) {close();mutable.value=mutable.value.copy(error=message,status=message)}
    fun close() {
        clearSelectionHints()
        generation++;waitingDeadline?.let(main::removeCallbacks);ackDeadline?.let(main::removeCallbacks)
        heartbeat?.let(main::removeCallbacks);heartbeat=null
        waitingDeadline=null;ackDeadline=null
        web?.let {view->runCatching {view.evaluateJavascript("guluStop()",null);view.stopLoading();view.removeJavascriptInterface("GuluTransport");
            (view.parent as?ViewGroup)?.removeView(view);view.destroy()}}
        web=null
        mutable.value=mutable.value.copy(localSide=null,hostAddress="",connected=false,busy=false,awaitingAck=false,sessionActive=false,remoteSelection=null,status="连接已关闭",error=null)
    }
}
