package com.jiligulu.app.ui.littleworld

import java.io.IOException

enum class RapfiStatus { PLAYED, UNAVAILABLE, INVALID_POSITION, MODEL_REJECTED, PROTOCOL_FAILED, TIMED_OUT, CANCELLED }

/** Piskvork coordinates remain in the snapshot's row/column frame, including a flipped UI. */
internal object RapfiProtocol {
    const val MEMORY_BYTES = 64L * 1024 * 1024

    fun usable(state: GomokuState): Boolean = state.outcome == GomokuOutcome.PLAYING &&
        state.size in 5..22 && state.board.all { it in 0..2 } && state.board.any { it == 0 }

    /**
     * Protocol 1 is SELF, rather than always black. Rapfi infers selfColor from the
     * first entry and searches the side following the final entry. Anchor the first
     * entry as actual black and finish with OPPO, independently of the assisted colour.
     * No historical move list is needed to reconstruct the current position.
     */
    fun boardLines(state: GomokuState): List<String>? {
        if (!usable(state)) return null
        val black = state.board.indices.filter { state.board[it] == 1 }.toMutableList()
        val white = state.board.indices.filter { state.board[it] == 2 }.toMutableList()
        val last = state.lastMove
        if (last != null && last.x in 0 until state.size && last.y in 0 until state.size) {
            val index = last.y * state.size + last.x
            val group = if (state.board[index] == 1) black else white
            if (group.remove(index)) group.add(index)
        }
        fun flag(piece: Int) = if (piece == state.currentPlayer) 1 else 2
        val lines = ArrayList<String>()
        // Rapfi accepts a non-stone PASS in analysis positions. It anchors WHITE's
        // empty root or a snapshot with no black stones without colouring SELF black.
        if (black.isEmpty() && (white.isNotEmpty() || state.currentPlayer == 2)) lines.add("-1,-1,${flag(1)}")
        for (i in 0 until maxOf(black.size, white.size)) {
            if (i < black.size) {
                val index = black[i]
                lines.add("${index % state.size},${index / state.size},${flag(1)}")
            }
            if (i < white.size) {
                val index = white[i]
                lines.add("${index % state.size},${index / state.size},${flag(2)}")
            }
        }
        // Legal live games already end in OPPO. A non-stone pass also allows an
        // imported analysis snapshot with unequal counts to request its explicit side.
        if (lines.lastOrNull()?.endsWith(",1") == true) lines.add("-1,-1,2")
        return listOf("BOARD") + lines + "DONE"
    }

    fun move(line: String, state: GomokuState): GridCell? {
        val parts = line.trim().split(',')
        if (parts.size != 2) return null
        val x = parts[0].trim().toIntOrNull() ?: return null
        val y = parts[1].trim().toIntOrNull() ?: return null
        if (x !in 0 until state.size || y !in 0 until state.size || state.cellAt(x, y) != 0 ||
            state.outcome != GomokuOutcome.PLAYING) return null
        return GridCell(x, y)
    }
}

internal sealed interface RapfiEvent {
    data class Line(val text: String) : RapfiEvent
    data object Closed : RapfiEvent
    data object Failed : RapfiEvent
}

internal interface RapfiTransport : AutoCloseable {
    fun writeLine(command: String)
    fun poll(waitMillis: Long): RapfiEvent?
    fun stopAndClose() {
        try {
            runCatching { writeLine("STOP") }
            runCatching { writeLine("END") }
        } finally { close() }
    }
}

/** One request owns its process; a BOARD snapshot replaces any preceding game history. */
internal class RapfiSession(
    private val transport: RapfiTransport,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val absoluteDeadlineMillis: Long? = null,
) {
    var status = RapfiStatus.PROTOCOL_FAILED
        private set
    var version: String? = null
        private set
    var setupMillis = 0L
        private set
    var searchMillis = 0L
        private set

    fun chooseMove(state: GomokuState, timeBudgetMillis: Long, shouldCancel: () -> Boolean): GridCell? {
        val started = nowMillis()
        val deadline = minOf(absoluteDeadlineMillis ?: Long.MAX_VALUE, started + timeBudgetMillis.coerceIn(100, 10_000))
        var cancellationObserved = false
        fun cancelled(): Boolean {
            if (shouldCancel() || Thread.currentThread().isInterrupted) {
                cancellationObserved = true
                status = RapfiStatus.CANCELLED
            }
            return cancellationObserved
        }
        fun awaitLine(deadline: Long, accepts: (String) -> Boolean): String? {
            while (!cancelled()) {
                val remaining = deadline - nowMillis()
                if (remaining <= 0) { status = RapfiStatus.TIMED_OUT; return null }
                when (val event = transport.poll(minOf(25L, remaining))) {
                    null -> Unit
                    is RapfiEvent.Line -> {
                        val line = event.text.trim()
                        if (line.startsWith("ERROR", true) || line.startsWith("UNKNOWN", true) ||
                            line.contains("CRITICAL ERROR", true)) return null
                        if (accepts(line)) {
                            if (nowMillis() >= deadline) { status = RapfiStatus.TIMED_OUT; return null }
                            return line.takeUnless { cancelled() }
                        }
                    }
                    RapfiEvent.Closed, RapfiEvent.Failed -> return null
                }
            }
            return null
        }
        try {
            if (cancelled()) return null
            val board = RapfiProtocol.boardLines(state) ?: run { status = RapfiStatus.INVALID_POSITION; return null }
            val startupDeadline = minOf(deadline, nowMillis() + 6_000)
            transport.writeLine("ABOUT")
            val about = awaitLine(startupDeadline) { it.contains("name=\"Rapfi\"") } ?: return null
            version = Regex("version=\"([^\"]{1,120})\"").find(about)?.groupValues?.get(1)
            transport.writeLine("START ${state.size}")
            if (awaitLine(startupDeadline) { it == "OK" } == null || cancelled()) return null
            setupMillis = nowMillis() - started
            val budget = deadline - nowMillis() - 100
            if (budget <= 0) { status = RapfiStatus.TIMED_OUT; return null }
            transport.writeLine("INFO RULE 0")
            transport.writeLine("INFO THREAD_NUM 1")
            transport.writeLine("INFO MAX_MEMORY ${RapfiProtocol.MEMORY_BYTES}")
            transport.writeLine("INFO TIMEOUT_MATCH 0")
            transport.writeLine("INFO TIME_LEFT 2147483647")
            transport.writeLine("INFO TIMEOUT_TURN $budget")
            transport.writeLine("INFO PONDERING 0")
            transport.writeLine("INFO SHOW_DETAIL 0")
            for (line in board) {
                if (cancelled()) return null
                transport.writeLine(line)
            }
            val searchStarted = nowMillis()
            val answer = awaitLine(deadline) {
                it.matches(Regex("[+-]?\\d+\\s*,\\s*[+-]?\\d+"))
            } ?: return null
            searchMillis = nowMillis() - searchStarted
            val move = RapfiProtocol.move(answer, state) ?: return null
            if (cancelled()) return null
            status = RapfiStatus.PLAYED
            return move
        } catch (_: IOException) {
            return null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            status = RapfiStatus.CANCELLED
            return null
        } finally {
            runCatching { transport.stopAndClose() }
        }
    }
}
