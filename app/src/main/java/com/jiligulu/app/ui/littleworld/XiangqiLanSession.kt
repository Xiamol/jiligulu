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
    val pendingUndoRequest: XiangqiSide? = null,
    val canUndo: Boolean = false,
    val isHost: Boolean = false,
    val round: Int = 0,
    val pendingMatchName: String? = null,
    val awaitingMatch: Boolean = false,
    val rematchRequestedBy: XiangqiSide? = null,
    val myRematchRequested: Boolean = false,
    val resultSecondsLeft: Int = 0,
    val roomEnded: Boolean = false,
    val localBackground: Boolean = false,
    val remoteBackground: Boolean = false,
    val reconnecting: Boolean = false,
    val resignedBy: XiangqiSide? = null,
)

/** The room creator is the board authority, independent of randomly assigned red/black. */
class XiangqiLanSession : XiangqiRoomSession(null, false) {
    companion object { const val PORT = 49761 }
}
internal enum class XiangqiLanRejection(val hint: String) {
    STALE("棋盘已更新，请重新走棋"),
    TURN("请等待对方走棋"),
    ILLEGAL("这一步不符合象棋规则"),
    FINISHED("对局已结束，请等待房主重开"),
    UNDO_PENDING("请先完成悔棋协商"),
}

internal sealed interface XiangqiLanMessage {
    data object Hello : XiangqiLanMessage
    data object Ping : XiangqiLanMessage
    data object Pong : XiangqiLanMessage
    data class Move(val revision: Int, val move: XiangqiMove) : XiangqiLanMessage
    data class Select(val revision: Int, val cell: GridCell?) : XiangqiLanMessage
    data class Snapshot(val revision: Int, val game: XiangqiState) : XiangqiLanMessage
    data class Reject(val reason: XiangqiLanRejection) : XiangqiLanMessage
    data class UndoRequest(val request: XiangqiUndoRequest) : XiangqiLanMessage
    data class UndoResponse(val revision: Int, val id: Int, val requester: XiangqiSide, val accept: Boolean) : XiangqiLanMessage
    data class UndoResult(val request: XiangqiUndoRequest, val resolution: XiangqiUndoResolution) : XiangqiLanMessage
    data class UndoSnapshot(val request: XiangqiUndoRequest, val snapshot: Snapshot) : XiangqiLanMessage
    data class Control(val value: RoomControl) : XiangqiLanMessage
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
        is XiangqiLanMessage.UndoRequest -> with(message.request) { "$VERSION|UNDO_REQUEST|$revision|$id|${requester.name}" }
        is XiangqiLanMessage.UndoResponse -> "$VERSION|UNDO_RESPONSE|${message.revision}|${message.id}|${message.requester.name}|${if(message.accept) 1 else 0}"
        is XiangqiLanMessage.UndoResult -> with(message.request) { "$VERSION|UNDO_RESULT|$revision|$id|${requester.name}|${message.resolution.name}" }
        is XiangqiLanMessage.UndoSnapshot -> with(message.request) {
            "$VERSION|UNDO_STATE|$revision|$id|${requester.name}|" + encode(message.snapshot).split('|').drop(2).joinToString("|")
        }
        is XiangqiLanMessage.Control -> "$VERSION|ROOM|" + RoomControlCodec.encode(message.value)
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
                "ROOM" -> {require(parts.size>=3);XiangqiLanMessage.Control(RoomControlCodec.decode(parts.drop(2).joinToString("|")))}
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
                "UNDO_REQUEST" -> {
                    require(parts.size == 5)
                    XiangqiLanMessage.UndoRequest(undoRequest(parts))
                }
                "UNDO_RESPONSE" -> {
                    require(parts.size == 6)
                    XiangqiLanMessage.UndoResponse(integer(parts[2], 0..Int.MAX_VALUE),
                        integer(parts[3], 1..Int.MAX_VALUE), XiangqiSide.valueOf(parts[4]), integer(parts[5], 0..1) == 1)
                }
                "UNDO_RESULT" -> {
                    require(parts.size == 6)
                    XiangqiLanMessage.UndoResult(undoRequest(parts), XiangqiUndoResolution.valueOf(parts[5]))
                }
                "UNDO_STATE" -> {
                    require(parts.size == 12)
                    val request = undoRequest(parts)
                    val snapshot = decode("$VERSION|STATE|" + parts.drop(5).joinToString("|")) as XiangqiLanMessage.Snapshot
                    require(request.revision < Int.MAX_VALUE && snapshot.revision == request.revision + 1)
                    XiangqiLanMessage.UndoSnapshot(request, snapshot)
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

    private fun undoRequest(parts: List<String>): XiangqiUndoRequest = XiangqiUndoRequest(
        integer(parts[2], 0..Int.MAX_VALUE), integer(parts[3], 1..Int.MAX_VALUE), XiangqiSide.valueOf(parts[4]),
    )

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
