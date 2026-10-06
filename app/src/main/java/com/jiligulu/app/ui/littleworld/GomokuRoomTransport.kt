package com.jiligulu.app.ui.littleworld

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

internal interface GomokuRoomWire {
    val transportView: WebView? get() = null
    fun send(line: String)
    fun close()
}

internal data class GomokuWireEvents(
    val waiting: (String) -> Unit,
    val connected: () -> Unit,
    val data: (String) -> Unit,
    val failure: (String) -> Unit,
)

/** Reliable local byte transport; all UI/game callbacks are serialized on the main looper. */
internal class GomokuLanWire(private val events: GomokuWireEvents) : GomokuRoomWire {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outgoing = Channel<String>(16)
    private val lock = Any()
    @Volatile private var closed = false
    private var server: ServerSocket? = null
    private var socket: Socket? = null

    fun host() { scope.launch {
        try {
            val address = Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }.asSequence()
                .flatMap { Collections.list(it.inetAddresses).asSequence() }.firstOrNull(::privateAddress)
                ?.hostAddress ?: error("请先连接 Wi-Fi 再寻找附近伙伴")
            val listener = ServerSocket()
            synchronized(lock) { if (closed) { listener.close(); return@launch }; server = listener }
            listener.reuseAddress = true; listener.bind(InetSocketAddress(PORT)); listener.soTimeout = 1_000
            post { events.waiting(address) }
            var peer: Socket? = null
            while (isActive && !closed && peer == null) try { peer = listener.accept() } catch (_: SocketTimeoutException) { }
            listener.close()
            if (peer != null && privateAddress(peer.inetAddress)) connect(peer) else {
                runCatching { peer?.close() }
                if (!closed) error("仅支持同一 Wi-Fi 的伙伴")
            }
        } catch (failure: Exception) { if (!closed) post { events.failure(failure.message ?: "附近连接未成功，请重新进入") } }
    } }

    fun join(address: String) { scope.launch {
        try {
            val endpoint = privateEndpoint(address)
            val peer = Socket()
            synchronized(lock) { if (closed) { peer.close(); return@launch }; socket = peer }
            peer.connect(endpoint, 5_000)
            connect(peer)
        } catch (_: Exception) { if (!closed) post { events.failure("附近连接未成功，请让伙伴重新进入房间") } }
    } }

    private fun connect(peer: Socket) {
        synchronized(lock) { if (closed) { peer.close(); return }; socket = peer }
        peer.soTimeout = 1_000; peer.tcpNoDelay = true; peer.keepAlive = true
        val reader = BoundedLanLineReader(BufferedInputStream(peer.getInputStream()))
        val output = peer.getOutputStream()
        scope.launch {
            try {
                for (line in outgoing) { output.write((line + "\n").toByteArray(Charsets.US_ASCII)); output.flush() }
            } catch (_: Exception) { if (!closed) post { events.failure("附近连接已断开") } }
        }
        post(events.connected)
        while (scope.isActive && !closed) {
            val line = try { reader.readLine() ?: error("伙伴已离开") } catch (_: SocketTimeoutException) { continue }
            post { events.data(line) }
        }
    }

    override fun send(line: String) {
        if (closed) return
        if (line.length > XiangqiLanProtocol.MAX_LINE_BYTES || outgoing.trySend(line).isFailure)
            post { events.failure("附近连接繁忙，请重新进入") }
    }
    override fun close() {
        synchronized(lock) { closed = true; runCatching { socket?.close() }; runCatching { server?.close() } }
        outgoing.close(); scope.cancel()
    }
    private fun post(action: () -> Unit) { main.post { if (!closed) action() } }
    companion object {
        const val PORT = 49762
        private fun privateAddress(address: InetAddress): Boolean = address is Inet4Address &&
            address.isSiteLocalAddress && !address.isLoopbackAddress && !address.isAnyLocalAddress
        internal fun privateEndpoint(value: String): InetSocketAddress {
            val parts = value.trim().split(':')
            require(parts.size in 1..2 && (parts.size == 1 || parts[1] == PORT.toString()))
            val octets = parts[0].split('.')
            require(octets.size == 4)
            val bytes = octets.map { require(it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit));
                it.toInt().also { number -> require(number in 0..255) }.toByte() }.toByteArray()
            val address = InetAddress.getByAddress(bytes)
            require(privateAddress(address))
            return InetSocketAddress(address, PORT)
        }
    }
}

/** Bundled PeerJS transport with a separate game namespace and no page/file navigation. */
@SuppressLint("SetJavaScriptEnabled")
internal class GomokuOnlineWire(context: Context, code: String, hosting: Boolean, private val events: GomokuWireEvents) : GomokuRoomWire {
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private val view = WebView(context)
    override val transportView: WebView get() = view
    init {
        view.settings.apply { javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false; mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW }
        view.addJavascriptInterface(object {
            @JavascriptInterface fun event(type: String, value: String) { main.post {
                if (closed) return@post
                when (type) {
                    "ready" -> events.waiting(code)
                    "connected" -> events.connected()
                    "data" -> if (value.length <= XiangqiLanProtocol.MAX_LINE_BYTES) events.data(value)
                        else events.failure("收到无效五子棋数据")
                    "closed" -> events.failure("伙伴已离开房间")
                    "error" -> events.failure(if (value == "peer-unavailable") "找不到房间，请确认伙伴仍在等待" else "互联网连接未成功，可找附近伙伴或同机对局")
                }
            } }
        }, "GuluTransport")
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?) = true
            override fun shouldInterceptRequest(v: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                if (request?.url.toString() == "https://appassets.androidplatform.net/peerjs.min.js")
                    WebResourceResponse("application/javascript", "UTF-8", context.assets.open("chess-online/peerjs.min.js")) else null
            override fun onPageFinished(v: WebView?, url: String?) {
                if (!closed) view.evaluateJavascript("guluStart(${JSONObject.quote(code)},$hosting)", null)
            }
        }
        // The audited static transport uses the same signaling infrastructure, with isolated peers/metadata.
        val html = context.assets.open("chess-online/transport.html").bufferedReader().use { it.readText() }
            .replace("gulu-xq-", "gulu-go-")
        view.loadDataWithBaseURL("https://appassets.androidplatform.net/", html, "text/html", "UTF-8", null)
    }
    override fun send(line: String) { if (!closed) view.evaluateJavascript("guluSend(${JSONObject.quote(line)})", null) }
    override fun close() {
        if (closed) return
        closed = true
        runCatching { view.evaluateJavascript("guluStop()", null); view.stopLoading(); view.removeJavascriptInterface("GuluTransport")
            (view.parent as? ViewGroup)?.removeView(view); view.destroy() }
    }
}
