package com.jiligulu.app.ui.littleworld

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.BindException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

data class XiangqiLanUiState(
    val game: XiangqiState = XiangqiEngine.newGame(),
    val localSide: XiangqiSide? = null,
    val hostAddress: String = "",
    val connected: Boolean = false,
    val status: String = "同一 Wi-Fi 下创建或加入房间",
    val error: String? = null,
    val busy: Boolean = false,
    val awaitingAck: Boolean = false,
    val sessionActive: Boolean = false,
    val revision: Int = 0,
    val remoteSelection: GridCell? = null,
)

/** One foreground-only, two-player TCP room. The host is the sole board authority. */
class XiangqiLanSession {
    private val lock = Any()
    private val mutableState = MutableStateFlow(XiangqiLanUiState())
    val state: StateFlow<XiangqiLanUiState> = mutableState.asStateFlow()
    private var session: Session? = null

    fun host() {
        val room = start(XiangqiSide.RED, "正在创建房间…")
        room.scope.launch {
            try {
                val address = privateIpv4Address()
                    ?: throw LanConnectionException("没有可用的局域网地址，请先连接 Wi-Fi")
                val server = ServerSocket()
                if (!attachServer(room, server)) return@launch
                server.reuseAddress = true
                server.bind(InetSocketAddress(PORT))
                server.soTimeout = READ_TIMEOUT_MS
                synchronized(lock) {
                    if (session === room) mutableState.value = mutableState.value.copy(
                        hostAddress = "$address:$PORT",
                        status = "等待同一 Wi-Fi 的伙伴加入",
                    )
                }
                val deadline = System.nanoTime() + WAIT_FOR_GUEST_MS * NANOS_PER_MS
                var guest: Socket? = null
                while (isActive && isCurrent(room) && guest == null) {
                    if (System.nanoTime() >= deadline) {
                        throw LanConnectionException("等待超时，请重新创建房间")
                    }
                    try {
                        guest = server.accept()
                    } catch (_: SocketTimeoutException) {
                        // Closing the screen closes the server immediately; this timeout also bounds waiting.
                    }
                }
                if (guest == null || !attachSocket(room, guest)) return@launch
                server.close()
                if (!isPrivateIpv4(guest.inetAddress)) {
                    throw LanConnectionException("仅支持同一局域网的伙伴加入")
                }
                exchange(room, guest, host = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fail(room, friendlyFailure(failure))
            }
        }
    }

    fun join(address: String) {
        val endpoint = try {
            XiangqiLanProtocol.parseAddress(address)
        } catch (_: IllegalArgumentException) {
            synchronized(lock) {
                if (session == null) mutableState.value = mutableState.value.copy(
                    error = "请输入房主的局域网 IPv4 地址，如 192.168.1.8:$PORT",
                )
            }
            return
        }
        val room = start(XiangqiSide.BLACK, "正在连接房主…", address.trim())
        room.scope.launch {
            try {
                val socket = Socket()
                // Register before connect, so close() also interrupts an in-progress connection.
                if (!attachSocket(room, socket)) return@launch
                socket.connect(endpoint, CONNECT_TIMEOUT_MS)
                exchange(room, socket, host = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fail(room, friendlyFailure(failure))
            }
        }
    }

    fun submitMove(move: XiangqiMove) {
        synchronized(lock) {
            val room = session ?: return
            val current = mutableState.value
            if (!current.connected || current.awaitingAck) return
            if (current.game.turnSide != current.localSide) {
                mutableState.value = current.copy(error = "请等待对方走棋")
                return
            }
            val next = XiangqiEngine.play(current.game, move)
            if (next === current.game) {
                mutableState.value = current.copy(error = "这一步不符合象棋规则")
                return
            }
            if (current.localSide == XiangqiSide.RED) {
                publishHostGame(room, next)
            } else {
                mutableState.value = current.copy(
                    awaitingAck = true,
                    remoteSelection = null,
                    error = null,
                    status = "等待房主确认落子…",
                )
                room.ackStarted.set(System.nanoTime())
                clearSelectionHints(room)
                enqueue(room, XiangqiLanMessage.Move(current.revision, move))
            }
        }
    }

    /** A bounded, unacknowledged visual hint; only the current player's own piece is sent. */
    fun selectPiece(cell: GridCell?) {
        synchronized(lock) {
            val room = session ?: return
            val current = mutableState.value
            val side = current.localSide ?: return
            if (!current.connected || current.awaitingAck ||
                !XiangqiSelectionRules.canSelect(current.game, side, cell)) return
            if (!room.selectionHints.offer(XiangqiLanMessage.Select(current.revision, cell))) return
            if (room.selectionJob != null) return
            room.selectionJob = room.scope.launch {
                delay(100)
                synchronized(lock) {
                    room.selectionJob = null
                    if (session !== room) return@synchronized
                    val hint = room.selectionHints.take() ?: return@synchronized
                    val latest = mutableState.value
                    if (latest.connected && !latest.awaitingAck && latest.revision == hint.revision &&
                        latest.localSide?.let { XiangqiSelectionRules.canSelect(latest.game, it, hint.cell) } == true) {
                        // Cosmetic traffic never fills the reliable game queue or fails a room.
                        room.selectionOutgoing.trySend(XiangqiLanProtocol.encode(hint))
                    }
                }
            }
        }
    }

    private fun clearSelectionHints(room: Session) {
        room.selectionJob?.cancel(); room.selectionJob = null
        room.selectionHints.clear()
        room.selectionOutgoing.tryReceive()
    }

    /** Only the host may replace the board; the revision keeps in-flight guest moves stale. */
    fun restart() {
        synchronized(lock) {
            val room = session ?: return
            val current = mutableState.value
            if (!current.connected) return
            if (current.localSide != XiangqiSide.RED) {
                mutableState.value = current.copy(error = "请等待房主重开对局")
                return
            }
            publishHostGame(room, XiangqiEngine.newGame())
        }
    }

    /** Call on hide, mode change, pet sleep, and ON_STOP. No reconnect task survives this call. */
    fun close() {
        synchronized(lock) {
            session?.let(::dispose)
            session = null
            mutableState.value = mutableState.value.copy(
                localSide = null,
                hostAddress = "",
                connected = false,
                busy = false,
                awaitingAck = false,
                sessionActive = false,
                remoteSelection = null,
                status = "连接已关闭",
                error = null,
            )
        }
    }

    private fun start(side: XiangqiSide, status: String, address: String = ""): Session =
        synchronized(lock) {
            session?.let(::dispose)
            val room = Session()
            session = room
            mutableState.value = XiangqiLanUiState(
                localSide = side,
                hostAddress = address,
                busy = true,
                sessionActive = true,
                status = status,
            )
            room
        }

    private fun attachSocket(room: Session, socket: Socket): Boolean = synchronized(lock) {
        if (session !== room) {
            runCatching { socket.close() }
            false
        } else {
            room.socket = socket
            true
        }
    }

    private fun attachServer(room: Session, server: ServerSocket): Boolean = synchronized(lock) {
        if (session !== room) {
            runCatching { server.close() }
            false
        } else {
            room.server = server
            true
        }
    }

    private fun exchange(room: Session, socket: Socket, host: Boolean) {
        socket.soTimeout = READ_TIMEOUT_MS
        socket.tcpNoDelay = true
        socket.keepAlive = true
        val reader = BoundedLanLineReader(BufferedInputStream(socket.getInputStream()))
        val output = socket.getOutputStream()
        val connectionStarted = System.nanoTime()
        room.lastReceived.set(connectionStarted)
        room.scope.launch {
            try {
                while (isActive && isCurrent(room)) {
                    // Reliable board messages take priority. A cosmetic hint has just one slot.
                    val line = select<String?> {
                        room.outgoing.onReceiveCatching { it.getOrNull() }
                        room.selectionOutgoing.onReceiveCatching { it.getOrNull() }
                    } ?: break
                    room.writeStarted.set(System.nanoTime())
                    try {
                        output.write((line + "\n").toByteArray(Charsets.US_ASCII))
                        output.flush()
                    } finally {
                        room.writeStarted.set(0)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fail(room, friendlyFailure(failure))
            }
        }
        room.scope.launch {
            var lastPing = connectionStarted
            while (isActive && isCurrent(room)) {
                delay(READ_TIMEOUT_MS.toLong())
                val now = System.nanoTime()
                val connected = synchronized(lock) { session === room && mutableState.value.connected }
                val error = when {
                    !connected && now - connectionStarted > CONNECT_TIMEOUT_MS * NANOS_PER_MS ->
                        "连接确认超时，请检查房间后重试"
                    now - room.lastReceived.get() > PEER_TIMEOUT_MS * NANOS_PER_MS ->
                        "伙伴连接已断开，请重新加入房间"
                    room.writeStarted.get().let { it != 0L && now - it > CONNECT_TIMEOUT_MS * NANOS_PER_MS } ->
                        "发送超时，请重新连接"
                    room.ackStarted.get().let { it != 0L && now - it > ACK_TIMEOUT_MS * NANOS_PER_MS } ->
                        "落子确认超时，请重新连接"
                    else -> null
                }
                if (error != null) {
                    fail(room, error)
                    return@launch
                }
                if (now - lastPing >= HEARTBEAT_MS * NANOS_PER_MS) {
                    enqueue(room, XiangqiLanMessage.Ping)
                    lastPing = now
                }
            }
        }
        if (!host) enqueue(room, XiangqiLanMessage.Hello)
        while (room.scope.isActive && isCurrent(room)) {
            val line = try {
                reader.readLine() ?: throw LanConnectionException("伙伴已离开房间")
            } catch (_: SocketTimeoutException) {
                continue // The reader retains a partial line across socket timeouts.
            }
            val message = XiangqiLanProtocol.decode(line)
            room.lastReceived.set(System.nanoTime())
            synchronized(lock) {
                if (session !== room) return
                when (message) {
                    XiangqiLanMessage.Ping -> enqueue(room, XiangqiLanMessage.Pong)
                    XiangqiLanMessage.Pong -> Unit
                    else -> if (host) receiveAsHost(room, message) else receiveAsGuest(room, message)
                }
            }
        }
    }

    // Called under lock, including local moves and restarts, so every revision is broadcast in order.
    private fun publishHostGame(room: Session, game: XiangqiState) {
        val current = mutableState.value
        if (current.revision == Int.MAX_VALUE) {
            fail(room, "对局过长，请重新创建房间")
            return
        }
        clearSelectionHints(room)
        val next = current.copy(
            game = game,
            revision = current.revision + 1,
            error = null,
            status = connectedStatus(game, XiangqiSide.RED),
            remoteSelection = null,
        )
        mutableState.value = next
        enqueue(room, XiangqiLanMessage.Snapshot(next.revision, game))
    }

    private fun receiveAsHost(room: Session, message: XiangqiLanMessage) {
        val current = mutableState.value
        when (message) {
            XiangqiLanMessage.Hello -> {
                if (current.connected) throw LanProtocolException()
                mutableState.value = current.copy(
                    connected = true,
                    busy = false,
                    error = null,
                    status = connectedStatus(current.game, XiangqiSide.RED),
                )
                enqueue(room, XiangqiLanMessage.Snapshot(current.revision, current.game))
            }
            is XiangqiLanMessage.Move -> {
                if (!current.connected) throw LanProtocolException()
                val rejection = when {
                    message.revision != current.revision -> XiangqiLanRejection.STALE
                    current.game.outcome != XiangqiOutcome.PLAYING -> XiangqiLanRejection.FINISHED
                    current.game.turnSide != XiangqiSide.BLACK -> XiangqiLanRejection.TURN
                    else -> null
                }
                if (rejection != null) {
                    reject(room, rejection)
                    return
                }
                val game = XiangqiEngine.play(current.game, message.move)
                if (game === current.game) reject(room, XiangqiLanRejection.ILLEGAL)
                else publishHostGame(room, game)
            }
            is XiangqiLanMessage.Select -> receiveSelection(message)
            else -> throw LanProtocolException()
        }
    }

    private fun reject(room: Session, reason: XiangqiLanRejection) {
        val current = mutableState.value
        enqueue(room, XiangqiLanMessage.Reject(reason))
        enqueue(room, XiangqiLanMessage.Snapshot(current.revision, current.game))
    }

    private fun receiveAsGuest(room: Session, message: XiangqiLanMessage) {
        val current = mutableState.value
        when (message) {
            is XiangqiLanMessage.Snapshot -> {
                if (!current.connected) {
                    if (message.revision != 0 || message.game != XiangqiEngine.newGame()) {
                        throw LanProtocolException()
                    }
                } else {
                    val valid = when {
                        message.revision == current.revision -> message.game == current.game
                        current.revision < Int.MAX_VALUE && message.revision == current.revision + 1 ->
                            message.game == XiangqiEngine.newGame() ||
                                message.game.lastMove?.let { XiangqiEngine.play(current.game, it) == message.game } == true
                        else -> false
                    }
                    if (!valid) throw LanProtocolException()
                }
                room.ackStarted.set(0)
                if (message.revision != current.revision || message.game != current.game) clearSelectionHints(room)
                mutableState.value = current.copy(
                    game = message.game,
                    revision = message.revision,
                    connected = true,
                    busy = false,
                    awaitingAck = false,
                    error = if (message.revision == current.revision) current.error else null,
                    status = connectedStatus(message.game, XiangqiSide.BLACK),
                    remoteSelection = if (message.revision == current.revision && message.game == current.game)
                        current.remoteSelection else null,
                )
            }
            is XiangqiLanMessage.Reject -> {
                if (!current.connected) throw LanProtocolException()
                room.ackStarted.set(0)
                mutableState.value = current.copy(
                    awaitingAck = false,
                    error = message.reason.hint,
                    status = connectedStatus(current.game, XiangqiSide.BLACK),
                    remoteSelection = null,
                )
            }
            is XiangqiLanMessage.Select -> receiveSelection(message)
            else -> throw LanProtocolException()
        }
    }

    private fun receiveSelection(message: XiangqiLanMessage.Select) {
        val current = mutableState.value
        if (current.connected && XiangqiSelectionRules.accepts(current.revision, current.game, current.localSide, message) &&
            current.remoteSelection != message.cell) mutableState.value = current.copy(remoteSelection = message.cell)
    }

    private fun enqueue(room: Session, message: XiangqiLanMessage) {
        synchronized(lock) {
            if (session !== room) return
            if (room.outgoing.trySend(XiangqiLanProtocol.encode(message)).isFailure) {
                fail(room, "连接繁忙，请重新连接")
            }
        }
    }

    private fun isCurrent(room: Session): Boolean = synchronized(lock) { session === room }

    private fun fail(room: Session, error: String) {
        synchronized(lock) {
            if (session !== room) return
            dispose(room)
            session = null
            mutableState.value = mutableState.value.copy(
                connected = false,
                busy = false,
                awaitingAck = false,
                sessionActive = false,
                remoteSelection = null,
                status = "连接已结束",
                error = error,
            )
        }
    }

    private fun dispose(room: Session) {
        clearSelectionHints(room)
        runCatching { room.socket?.close() }
        runCatching { room.server?.close() }
        room.outgoing.close()
        room.selectionOutgoing.close()
        room.scope.cancel()
    }

    private class Session {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val outgoing = Channel<String>(16)
        val selectionOutgoing = Channel<String>(Channel.CONFLATED)
        val lastReceived = AtomicLong(0)
        val writeStarted = AtomicLong(0)
        val ackStarted = AtomicLong(0)
        val selectionHints = XiangqiSelectionHints()
        var selectionJob: Job? = null
        var socket: Socket? = null
        var server: ServerSocket? = null
    }

    companion object {
        const val PORT = 49761
        private const val READ_TIMEOUT_MS = 1_000
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val ACK_TIMEOUT_MS = 8_000
        private const val HEARTBEAT_MS = 4_000
        private const val PEER_TIMEOUT_MS = 15_000
        private const val WAIT_FOR_GUEST_MS = 300_000L
        private const val NANOS_PER_MS = 1_000_000L

        private fun connectedStatus(game: XiangqiState, side: XiangqiSide): String = when (game.outcome) {
            XiangqiOutcome.RED_WON -> "红方获胜 · 房主可重开"
            XiangqiOutcome.BLACK_WON -> "黑方获胜 · 房主可重开"
            XiangqiOutcome.PLAYING -> if (game.turnSide == side) "轮到你走棋" else "等待对方走棋"
        }

        private fun privateIpv4Address(): String? {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            return Collections.list(interfaces)
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("wifi")) 0 else 1 }
                .asSequence()
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .firstOrNull(::isPrivateIpv4)?.hostAddress
        }

        private fun friendlyFailure(failure: Exception): String = when (failure) {
            is LanConnectionException -> failure.message ?: "连接失败，请重新连接"
            is LanProtocolException -> "对局数据无效，请重新创建房间"
            is BindException -> "房间端口已占用，请关闭其他对局后重试"
            is NoRouteToHostException -> "无法连接房主，请确认同一 Wi-Fi"
            is ConnectException -> "连接失败，请确认房间已开启且地址正确"
            is SocketTimeoutException -> "连接超时，请确认同一 Wi-Fi 和房主地址"
            is SecurityException -> "网络权限不可用，无法连接局域网"
            else -> "连接已断开，请检查 Wi-Fi 后重新连接"
        }
    }
}

internal enum class XiangqiLanRejection(val hint: String) {
    STALE("棋盘已更新，请重新走棋"),
    TURN("请等待对方走棋"),
    ILLEGAL("这一步不符合象棋规则"),
    FINISHED("对局已结束，请等待房主重开"),
}

internal sealed interface XiangqiLanMessage {
    data object Hello : XiangqiLanMessage
    data object Ping : XiangqiLanMessage
    data object Pong : XiangqiLanMessage
    data class Move(val revision: Int, val move: XiangqiMove) : XiangqiLanMessage
    data class Select(val revision: Int, val cell: GridCell?) : XiangqiLanMessage
    data class Snapshot(val revision: Int, val game: XiangqiState) : XiangqiLanMessage
    data class Reject(val reason: XiangqiLanRejection) : XiangqiLanMessage
}

/** Shared board/move encoding for LAN and the online transport; transport never changes game rules. */
internal object XiangqiWireCodec {
    fun encodeState(revision: Int, game: XiangqiState): String =
        XiangqiLanProtocol.encode(XiangqiLanMessage.Snapshot(revision, game))

    fun decodeState(line: String): XiangqiLanMessage.Snapshot =
        XiangqiLanProtocol.decode(line) as? XiangqiLanMessage.Snapshot ?: throw LanProtocolException()

    fun encodeMove(revision: Int, move: XiangqiMove): String =
        XiangqiLanProtocol.encode(XiangqiLanMessage.Move(revision, move))

    fun decodeMove(line: String): XiangqiLanMessage.Move =
        XiangqiLanProtocol.decode(line) as? XiangqiLanMessage.Move ?: throw LanProtocolException()
}

/** Small versioned ASCII protocol: no DNS, Java serialization, remote code, or unbounded reads. */
internal object XiangqiLanProtocol {
    const val MAX_LINE_BYTES = 1_024
    private const val VERSION = "XQ1"
    private const val MAX_PLY = 1_000_000

    fun encode(message: XiangqiLanMessage): String = when (message) {
        XiangqiLanMessage.Hello -> "$VERSION|HELLO"
        XiangqiLanMessage.Ping -> "$VERSION|PING"
        XiangqiLanMessage.Pong -> "$VERSION|PONG"
        is XiangqiLanMessage.Move -> "$VERSION|MOVE|${message.revision}|${index(message.move.from)}|${index(message.move.to)}"
        is XiangqiLanMessage.Select -> "$VERSION|SELECT|${message.revision}|${message.cell?.let(::index) ?: -1}"
        is XiangqiLanMessage.Reject -> "$VERSION|REJECT|${message.reason.name}"
        is XiangqiLanMessage.Snapshot -> with(message.game) {
            "$VERSION|STATE|${message.revision}|${turnSide.name}|${outcome.name}|$ply|" +
                "${lastMove?.from?.let(::index) ?: -1}|${lastMove?.to?.let(::index) ?: -1}|" +
                board.joinToString(",")
        }
    }

    fun decode(line: String): XiangqiLanMessage {
        if (line.length > MAX_LINE_BYTES || line.any { it.code !in 32..126 }) throw LanProtocolException()
        val parts = line.split('|')
        if (parts.size < 2 || parts[0] != VERSION) throw LanProtocolException()
        return try {
            when (parts[1]) {
                "HELLO" -> { require(parts.size == 2); XiangqiLanMessage.Hello }
                "PING" -> { require(parts.size == 2); XiangqiLanMessage.Ping }
                "PONG" -> { require(parts.size == 2); XiangqiLanMessage.Pong }
                "MOVE" -> {
                    require(parts.size == 5)
                    val from = integer(parts[3], 0..89)
                    val to = integer(parts[4], 0..89)
                    require(from != to)
                    XiangqiLanMessage.Move(integer(parts[2], 0..Int.MAX_VALUE), XiangqiMove(cell(from), cell(to)))
                }
                "SELECT" -> {
                    require(parts.size == 4)
                    val selected = integer(parts[3], -1..89)
                    XiangqiLanMessage.Select(integer(parts[2], 0..Int.MAX_VALUE),
                        if (selected == -1) null else cell(selected))
                }
                "REJECT" -> {
                    require(parts.size == 3)
                    XiangqiLanMessage.Reject(XiangqiLanRejection.valueOf(parts[2]))
                }
                "STATE" -> {
                    require(parts.size == 9)
                    val revision = integer(parts[2], 0..Int.MAX_VALUE)
                    val side = XiangqiSide.valueOf(parts[3])
                    val outcome = XiangqiOutcome.valueOf(parts[4])
                    val ply = integer(parts[5], 0..MAX_PLY)
                    require(side == if (ply % 2 == 0) XiangqiSide.RED else XiangqiSide.BLACK)
                    val from = integer(parts[6], -1..89)
                    val to = integer(parts[7], -1..89)
                    require((from == -1) == (to == -1))
                    require(if (ply == 0) from == -1 else from >= 0 && from != to)
                    val board = parts[8].split(',').map { integer(it, -7..7) }
                    require(board.size == 90)
                    require(board.count { it == 1 } <= 1 && board.count { it == -1 } <= 1)
                    if (outcome == XiangqiOutcome.PLAYING) {
                        require(board.count { it == 1 } == 1 && board.count { it == -1 } == 1)
                    } else {
                        require(board.count { it == if (outcome == XiangqiOutcome.RED_WON) 1 else -1 } == 1)
                    }
                    val move = if (from < 0) null else XiangqiMove(cell(from), cell(to))
                    if (move != null) {
                        require(board[from] == 0)
                        require(if (side == XiangqiSide.RED) board[to] < 0 else board[to] > 0)
                    }
                    XiangqiLanMessage.Snapshot(revision, XiangqiState(board, side, outcome, move, ply))
                }
                else -> throw LanProtocolException()
            }
        } catch (_: IllegalArgumentException) {
            throw LanProtocolException()
        }
    }

    fun parseAddress(value: String): InetSocketAddress {
        val text = value.trim()
        require(text.length in 7..64)
        val parts = text.split(':')
        require(parts.size in 1..2)
        require(parts.size == 1 || parts[1] == XiangqiLanSession.PORT.toString())
        val octets = parts[0].split('.')
        require(octets.size == 4)
        val bytes = octets.map {
            require(it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit))
            it.toInt().also { octet -> require(octet in 0..255) }.toByte()
        }.toByteArray()
        val address = InetAddress.getByAddress(bytes)
        require(isPrivateIpv4(address))
        return InetSocketAddress(address, XiangqiLanSession.PORT)
    }

    private fun integer(value: String, range: IntRange): Int {
        require(value.isNotEmpty() && value.length <= 11)
        require(value.all { it in '0'..'9' || it == '-' } && value != "-")
        return value.toInt().also { require(it in range) }
    }

    private fun index(cell: GridCell): Int {
        require(cell.x in 0..8 && cell.y in 0..9)
        return cell.y * 9 + cell.x
    }

    private fun cell(index: Int) = GridCell(index % 9, index / 9)
}

/** The buffer belongs to the connection, so a timeout halfway through a line loses no bytes. */
internal class BoundedLanLineReader(private val input: InputStream) {
    private val partial = StringBuilder()

    fun readLine(): String? {
        while (true) {
            val next = input.read()
            if (next == -1) {
                if (partial.isNotEmpty()) throw LanProtocolException()
                return null
            }
            if (next == '\n'.code) {
                val line = partial.toString()
                partial.setLength(0)
                return line
            }
            if (next !in 32..126 || partial.length >= XiangqiLanProtocol.MAX_LINE_BYTES) {
                throw LanProtocolException()
            }
            partial.append(next.toChar())
        }
    }
}

private fun isPrivateIpv4(address: InetAddress): Boolean {
    if (address !is Inet4Address || address.isLoopbackAddress || address.isAnyLocalAddress) return false
    val bytes = address.address.map { it.toInt() and 255 }
    return bytes[0] == 10 || bytes[0] == 192 && bytes[1] == 168 ||
        bytes[0] == 172 && bytes[1] in 16..31
}

internal class LanProtocolException : IOException("Invalid Xiangqi LAN protocol")
private class LanConnectionException(message: String) : IOException(message)
