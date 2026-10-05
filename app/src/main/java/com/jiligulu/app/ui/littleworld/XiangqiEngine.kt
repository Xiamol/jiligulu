package com.jiligulu.app.ui.littleworld

import kotlin.math.abs

enum class XiangqiSide(val sign: Int) {
    RED(1), BLACK(-1);

    val opponent: XiangqiSide get() = if (this == RED) BLACK else RED
}

enum class XiangqiOutcome { PLAYING, RED_WON, BLACK_WON }

data class XiangqiMove(val from: GridCell, val to: GridCell)

data class XiangqiState(
    /** Row-major, nine files by ten ranks. Red is positive and starts at the bottom. */
    val board: List<Int> = initialXiangqiBoard(),
    val turnSide: XiangqiSide = XiangqiSide.RED,
    val outcome: XiangqiOutcome = XiangqiOutcome.PLAYING,
    val lastMove: XiangqiMove? = null,
    val ply: Int = 0,
) {
    init {
        require(board.size == 90)
        require(board.all { it in -7..7 })
        require(ply >= 0)
    }

    fun pieceAt(x: Int, y: Int): Int = if (x in 0..8 && y in 0..9) board[y * 9 + x] else 0
}

private fun initialXiangqiBoard(): List<Int> {
    val board = MutableList(90) { 0 }
    val backRank = listOf(5, 4, 3, 2, 1, 2, 3, 4, 5)
    for (x in 0..8) {
        board[x] = -backRank[x]
        board[81 + x] = backRank[x]
    }
    for (x in listOf(1, 7)) {
        board[2 * 9 + x] = -6
        board[7 * 9 + x] = 6
    }
    for (x in 0..8 step 2) {
        board[3 * 9 + x] = -7
        board[6 * 9 + x] = 7
    }
    return board.toList()
}

/** Offline Xiangqi rules. The caller owns turn timing, suspension, and networking. */
object XiangqiEngine {
    const val GENERAL = 1
    const val ADVISOR = 2
    const val ELEPHANT = 3
    const val HORSE = 4
    const val ROOK = 5
    const val CANNON = 6
    const val PAWN = 7

    private val directions = listOf(GridCell(0, -1), GridCell(-1, 0), GridCell(1, 0), GridCell(0, 1))
    private val diagonals = listOf(GridCell(-1, -1), GridCell(1, -1), GridCell(-1, 1), GridCell(1, 1))
    private val horseSteps = listOf(
        GridCell(-2, -1), GridCell(-2, 1), GridCell(2, -1), GridCell(2, 1),
        GridCell(-1, -2), GridCell(1, -2), GridCell(-1, 2), GridCell(1, 2),
    )
    private val values = intArrayOf(0, 100_000, 200, 200, 430, 900, 450, 100)
    private const val WIN_SCORE = 1_000_000

    fun newGame(): XiangqiState = XiangqiState()

    fun legalMoves(state: XiangqiState, from: GridCell? = null): List<XiangqiMove> {
        if (state.outcome != XiangqiOutcome.PLAYING ||
            generalCell(state.board, state.turnSide) == null || from != null && !inside(from)
        ) return emptyList()

        return buildList {
            for (index in state.board.indices) {
                if (state.board[index] * state.turnSide.sign <= 0) continue
                val origin = GridCell(index % 9, index / 9)
                if (from != null && origin != from) continue
                for (target in pseudoTargets(state.board, origin)) {
                    val move = XiangqiMove(origin, target)
                    if (!inCheck(movedBoard(state.board, move), state.turnSide)) add(move)
                }
            }
        }
    }

    /** Illegal and terminal moves return the original state without changing any field. */
    fun play(state: XiangqiState, move: XiangqiMove): XiangqiState {
        if (move !in legalMoves(state, move.from)) return state
        val next = applyLegalMove(state, move)
        return if (next.outcome == XiangqiOutcome.PLAYING && !hasLegalMove(next)) {
            next.copy(outcome = victory(state.turnSide))
        } else next
    }

    fun isInCheck(state: XiangqiState, side: XiangqiSide): Boolean = inCheck(state.board, side)

    /** Deterministic two-ply opponent: take a win, then prefer the best worst-case reply. */
    fun chooseCpuMove(state: XiangqiState): XiangqiMove? {
        val moves = legalMoves(state)
        if (moves.isEmpty()) return null
        val side = state.turnSide
        var bestMove: XiangqiMove? = null
        var bestScore = Int.MIN_VALUE
        for (move in moves) {
            val next = applyLegalMove(state, move)
            val replies = legalMoves(next)
            // Stalemate is a loss in Xiangqi, just like a captured or checkmated general.
            if (next.outcome == victory(side) || replies.isEmpty()) return move

            var worstReply = Int.MAX_VALUE
            for (reply in replies) {
                val afterReply = applyLegalMove(next, reply)
                val score = when {
                    afterReply.outcome == victory(side.opponent) || !hasLegalMove(afterReply) -> -WIN_SCORE
                    else -> evaluate(afterReply.board, side)
                }
                if (score < worstReply) worstReply = score
            }
            val captured = abs(state.pieceAt(move.to.x, move.to.y))
            val score = worstReply + values[captured] / 16 +
                if (inCheck(next.board, side.opponent)) 12 else 0
            if (score > bestScore) {
                bestScore = score
                bestMove = move
            }
        }
        return bestMove
    }

    private fun applyLegalMove(state: XiangqiState, move: XiangqiMove): XiangqiState {
        val capturedGeneral = abs(state.pieceAt(move.to.x, move.to.y)) == GENERAL
        return state.copy(
            board = movedBoard(state.board, move),
            turnSide = state.turnSide.opponent,
            outcome = if (capturedGeneral) victory(state.turnSide) else XiangqiOutcome.PLAYING,
            lastMove = move,
            ply = state.ply + 1,
        )
    }

    private fun movedBoard(board: List<Int>, move: XiangqiMove): List<Int> = board.toMutableList().apply {
        this[move.to.y * 9 + move.to.x] = this[move.from.y * 9 + move.from.x]
        this[move.from.y * 9 + move.from.x] = 0
    }

    private fun hasLegalMove(state: XiangqiState): Boolean {
        if (state.outcome != XiangqiOutcome.PLAYING || generalCell(state.board, state.turnSide) == null) return false
        for (index in state.board.indices) {
            if (state.board[index] * state.turnSide.sign <= 0) continue
            val from = GridCell(index % 9, index / 9)
            for (to in pseudoTargets(state.board, from)) {
                if (!inCheck(movedBoard(state.board, XiangqiMove(from, to)), state.turnSide)) return true
            }
        }
        return false
    }

    private fun pseudoTargets(board: List<Int>, from: GridCell): List<GridCell> {
        val piece = board[from.y * 9 + from.x]
        val side = if (piece > 0) XiangqiSide.RED else XiangqiSide.BLACK
        return buildList {
            fun addTarget(target: GridCell) {
                if (inside(target) && board[target.y * 9 + target.x] * side.sign <= 0) add(target)
            }
            when (abs(piece)) {
                GENERAL -> {
                    for (step in directions) {
                        val target = GridCell(from.x + step.x, from.y + step.y)
                        if (inPalace(target, side)) addTarget(target)
                    }
                    // Only the opposing general can be captured along the open file.
                    for (dy in listOf(-1, 1)) {
                        var y = from.y + dy
                        while (y in 0..9) {
                            val encountered = board[y * 9 + from.x]
                            if (encountered != 0) {
                                if (encountered == -side.sign * GENERAL) addTarget(GridCell(from.x, y))
                                break
                            }
                            y += dy
                        }
                    }
                }
                ADVISOR -> for (step in diagonals) {
                    val target = GridCell(from.x + step.x, from.y + step.y)
                    if (inPalace(target, side)) addTarget(target)
                }
                ELEPHANT -> for (step in diagonals) {
                    val eye = GridCell(from.x + step.x, from.y + step.y)
                    val target = GridCell(from.x + step.x * 2, from.y + step.y * 2)
                    if (inside(target) && ownRiverSide(target.y, side) && board[eye.y * 9 + eye.x] == 0) {
                        addTarget(target)
                    }
                }
                HORSE -> for (step in horseSteps) {
                    val target = GridCell(from.x + step.x, from.y + step.y)
                    if (!inside(target)) continue
                    val leg = if (abs(step.x) == 2) GridCell(from.x + step.x / 2, from.y)
                        else GridCell(from.x, from.y + step.y / 2)
                    if (board[leg.y * 9 + leg.x] == 0) addTarget(target)
                }
                ROOK, CANNON -> for (step in directions) {
                    var target = GridCell(from.x + step.x, from.y + step.y)
                    var screened = false
                    while (inside(target)) {
                        val occupant = board[target.y * 9 + target.x]
                        if (!screened) {
                            if (occupant == 0) addTarget(target)
                            else if (abs(piece) == ROOK) {
                                addTarget(target)
                                break
                            } else screened = true
                        } else if (occupant != 0) {
                            addTarget(target)
                            break
                        }
                        target = GridCell(target.x + step.x, target.y + step.y)
                    }
                }
                PAWN -> {
                    addTarget(GridCell(from.x, from.y - side.sign))
                    if (!ownRiverSide(from.y, side)) {
                        addTarget(GridCell(from.x - 1, from.y))
                        addTarget(GridCell(from.x + 1, from.y))
                    }
                }
            }
        }
    }

    private fun inCheck(board: List<Int>, side: XiangqiSide): Boolean {
        val general = generalCell(board, side) ?: return true
        for (index in board.indices) {
            val piece = board[index]
            if (piece * side.sign < 0 && attacks(board, GridCell(index % 9, index / 9), general, piece)) return true
        }
        return false
    }

    /** Attack testing avoids move-list allocation during legality checks and CPU search. */
    private fun attacks(board: List<Int>, from: GridCell, to: GridCell, piece: Int): Boolean {
        val side = if (piece > 0) XiangqiSide.RED else XiangqiSide.BLACK
        val dx = to.x - from.x
        val dy = to.y - from.y
        return when (abs(piece)) {
            GENERAL -> (abs(dx) + abs(dy) == 1 && inPalace(to, side)) ||
                (dx == 0 && abs(board[to.y * 9 + to.x]) == GENERAL && interveningPieces(board, from, to) == 0)
            ADVISOR -> abs(dx) == 1 && abs(dy) == 1 && inPalace(to, side)
            ELEPHANT -> abs(dx) == 2 && abs(dy) == 2 && ownRiverSide(to.y, side) &&
                board[(from.y + dy / 2) * 9 + from.x + dx / 2] == 0
            HORSE -> when {
                abs(dx) == 2 && abs(dy) == 1 -> board[from.y * 9 + from.x + dx / 2] == 0
                abs(dx) == 1 && abs(dy) == 2 -> board[(from.y + dy / 2) * 9 + from.x] == 0
                else -> false
            }
            ROOK -> (dx == 0 || dy == 0) && interveningPieces(board, from, to) == 0
            CANNON -> (dx == 0 || dy == 0) && interveningPieces(board, from, to) == 1
            PAWN -> (dx == 0 && dy == -side.sign) ||
                (!ownRiverSide(from.y, side) && dy == 0 && abs(dx) == 1)
            else -> false
        }
    }

    private fun interveningPieces(board: List<Int>, from: GridCell, to: GridCell): Int {
        val dx = (to.x - from.x).coerceIn(-1, 1)
        val dy = (to.y - from.y).coerceIn(-1, 1)
        var x = from.x + dx
        var y = from.y + dy
        var count = 0
        while (x != to.x || y != to.y) {
            if (board[y * 9 + x] != 0) count++
            x += dx
            y += dy
        }
        return count
    }

    private fun evaluate(board: List<Int>, side: XiangqiSide): Int {
        var score = 0
        for (index in board.indices) {
            val piece = board[index]
            if (piece == 0) continue
            val owner = if (piece > 0) XiangqiSide.RED else XiangqiSide.BLACK
            val type = abs(piece)
            val x = index % 9
            val y = index / 9
            val positionValue = when (type) {
                PAWN -> (if (ownRiverSide(y, owner)) 0 else 60) +
                    (if (owner == XiangqiSide.RED) 9 - y else y) * 7
                HORSE, CANNON -> (4 - abs(x - 4)) * 5
                else -> 0
            }
            score += (values[type] + positionValue) * if (owner == side) 1 else -1
        }
        if (inCheck(board, side)) score -= 35
        if (inCheck(board, side.opponent)) score += 35
        return score
    }

    private fun generalCell(board: List<Int>, side: XiangqiSide): GridCell? {
        val index = board.indexOf(side.sign * GENERAL)
        return if (index < 0) null else GridCell(index % 9, index / 9)
    }

    private fun inside(cell: GridCell): Boolean = cell.x in 0..8 && cell.y in 0..9
    private fun ownRiverSide(y: Int, side: XiangqiSide): Boolean = if (side == XiangqiSide.RED) y >= 5 else y <= 4
    private fun inPalace(cell: GridCell, side: XiangqiSide): Boolean =
        cell.x in 3..5 && if (side == XiangqiSide.RED) cell.y in 7..9 else cell.y in 0..2
    private fun victory(side: XiangqiSide): XiangqiOutcome =
        if (side == XiangqiSide.RED) XiangqiOutcome.RED_WON else XiangqiOutcome.BLACK_WON
}
