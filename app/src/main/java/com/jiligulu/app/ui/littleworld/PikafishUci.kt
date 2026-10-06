package com.jiligulu.app.ui.littleworld

import java.io.IOException

/** UCI coordinates always use red's view, independently of the screen's flipped board. */
internal object PikafishUci {
    fun fen(state: XiangqiState): String = buildString {
        val pieces = charArrayOf(' ', 'k', 'a', 'b', 'n', 'r', 'c', 'p')
        for (y in 0..9) {
            if (y > 0) append('/')
            var empty = 0
            for (x in 0..8) {
                val piece = state.pieceAt(x, y)
                if (piece == 0) empty++ else {
                    if (empty > 0) { append(empty); empty = 0 }
                    val letter = pieces[kotlin.math.abs(piece)]
                    append(if (piece > 0) letter.uppercaseChar() else letter)
                }
            }
            if (empty > 0) append(empty)
        }
        append(if (state.turnSide == XiangqiSide.RED) " w" else " b")
        // A snapshot does not contain the reversible-move counter or prior repetition history.
        append(" - - 0 ")
        append(state.ply / 2 + 1)
    }

    fun move(token: String): XiangqiMove? {
        if (token.length != 4 || token[0] !in 'a'..'i' || token[2] !in 'a'..'i' ||
            token[1] !in '0'..'9' || token[3] !in '0'..'9') return null
        val from = GridCell(token[0] - 'a', 9 - (token[1] - '0'))
        val to = GridCell(token[2] - 'a', 9 - (token[3] - '0'))
        return XiangqiMove(from, to).takeIf { from != to }
    }

    fun bestMove(line: String, state: XiangqiState): XiangqiMove? {
        val words = line.trim().split(Regex("\\s+"))
        if (words.size < 2 || words[0] != "bestmove") return null
        val candidate = move(words[1]) ?: return null
        return candidate.takeIf { it in XiangqiEngine.legalMoves(state) }
    }
}

internal sealed interface PikafishUciEvent {
    data class Line(val text: String) : PikafishUciEvent
    data object Closed : PikafishUciEvent
    data object Failed : PikafishUciEvent
}

/** A bounded polling interface keeps cancellation independent of a blocking native stdout read. */
internal interface PikafishUciTransport : AutoCloseable {
    fun writeLine(command: String)
    fun poll(waitMillis: Long): PikafishUciEvent?
}

internal class PikafishUciSession(
    private val transport: PikafishUciTransport,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    fun chooseMove(state: XiangqiState, modelPath: String, timeBudgetMillis: Long,
        shouldCancel: () -> Boolean): XiangqiMove? {
        fun cancelled() = shouldCancel() || Thread.currentThread().isInterrupted
        fun awaitLine(deadline: Long, accepts: (String) -> Boolean): String? {
            while (!cancelled()) {
                val remaining = deadline - nowMillis()
                if (remaining <= 0) return null
                when (val event = transport.poll(minOf(25L, remaining))) {
                    null -> Unit
                    is PikafishUciEvent.Line -> {
                        val line = event.text.trim()
                        if (line.contains("CRITICAL ERROR", ignoreCase = true)) return null
                        if (accepts(line)) return line.takeUnless { cancelled() }
                    }
                    PikafishUciEvent.Closed, PikafishUciEvent.Failed -> return null
                }
            }
            return null
        }
        try {
            if (cancelled() || state.outcome != XiangqiOutcome.PLAYING ||
                modelPath.any { it == '\n' || it == '\r' }) return null
            val startupDeadline = nowMillis() + 6_000
            transport.writeLine("uci")
            if (awaitLine(startupDeadline) { it == "uciok" } == null) return null
            if (cancelled()) return null
            transport.writeLine("setoption name Threads value 1")
            transport.writeLine("setoption name Hash value 32")
            transport.writeLine("setoption name Ponder value false")
            transport.writeLine("setoption name EvalFile value $modelPath")
            transport.writeLine("isready")
            if (awaitLine(startupDeadline) { it == "readyok" } == null) return null
            if (cancelled()) return null
            val budget = timeBudgetMillis.coerceIn(100, 6_000)
            transport.writeLine("position fen ${PikafishUci.fen(state)}")
            transport.writeLine("go movetime $budget")
            val answer = awaitLine(nowMillis() + budget + 1_000) {
                it == "bestmove" || it.startsWith("bestmove ") || it.startsWith("bestmove\t")
            } ?: return null
            if (cancelled()) return null
            return PikafishUci.bestMove(answer, state).takeUnless { cancelled() }
        } catch (_: IOException) {
            return null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            // The transport owns and terminates its process, including cancelled and failed starts.
            transport.close()
        }
    }
}
