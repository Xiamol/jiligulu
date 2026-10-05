package com.jiligulu.app.ui.littleworld

import kotlin.math.abs

/** Local, bounded searches for the seven-tap shortcut. Call from a worker dispatcher. */
object XiangqiStrongMoveHelper {
    fun chooseMove(
        state: XiangqiState,
        shouldCancel: () -> Boolean = { false },
    ): XiangqiMove? {
        if (state.outcome != XiangqiOutcome.PLAYING || shouldCancel()) return null
        return XiangqiSearch(shouldCancel).choose(state)
    }
}

object GomokuStrongMoveHelper {
    fun chooseMove(
        state: GomokuState,
        shouldCancel: () -> Boolean = { false },
    ): GridCell? {
        if (state.outcome != GomokuOutcome.PLAYING || shouldCancel()) return null
        return GomokuSearch(shouldCancel).choose(state)
    }
}

private class SearchStopped : RuntimeException(null, null, false, false)

private class SearchBudget(private val shouldCancel: () -> Boolean) {
    private val deadline = System.nanoTime() + 850_000_000L
    private var nodes = 0
    var cancelled = false
        private set

    fun visit() {
        nodes++
        if (nodes == 1 || nodes and 15 == 0) {
            if (shouldCancel() || Thread.currentThread().isInterrupted) {
                cancelled = true
                throw SearchStopped()
            }
            if (nodes >= 64_000 || System.nanoTime() >= deadline) throw SearchStopped()
        }
    }

    fun finishCancelled(): Boolean = cancelled || shouldCancel() || Thread.currentThread().isInterrupted
}

private enum class Bound { EXACT, LOWER, UPPER }
private data class XiangqiEntry(val depth: Int, val score: Int, val bound: Bound, val move: XiangqiMove?)

private class XiangqiSearch(shouldCancel: () -> Boolean) {
    private val budget = SearchBudget(shouldCancel)
    private val table = HashMap<Long, XiangqiEntry>()
    private val history = IntArray(90 * 90)
    private val values = intArrayOf(0, 100_000, 210, 220, 440, 950, 470, 110)
    private val mate = 1_000_000

    fun choose(state: XiangqiState): XiangqiMove? {
        var completed: XiangqiMove? = null
        try {
            budget.visit()
            val moves = XiangqiEngine.legalMoves(state)
            if (moves.isEmpty()) return null
            completed = order(state, moves, null).first()
            if (moves.size == 1) return completed.takeUnless { budget.finishCancelled() }
            // Commit only complete iterations: an expired search never favors an early partial branch.
            for (depth in 1..6) {
                budget.visit()
                var best = completed
                var bestScore = -mate * 2
                var alpha = -mate * 2
                for (move in order(state, moves, completed)) {
                    budget.visit()
                    val next = XiangqiEngine.play(state, move)
                    val score = -search(next, depth - 1, -mate * 2, -alpha, 1)
                    if (score > bestScore) {
                        bestScore = score
                        best = move
                    }
                    if (score > alpha) alpha = score
                    if (score >= mate - 1) {
                        completed = move
                        return completed.takeUnless { budget.finishCancelled() }
                    }
                }
                completed = best
                if (abs(bestScore) >= mate - 100) break
            }
        } catch (_: SearchStopped) {
            // The previous complete iteration remains legal in the immutable root position.
        }
        return completed.takeUnless { budget.finishCancelled() }
    }

    private fun search(state: XiangqiState, depth: Int, alphaIn: Int, betaIn: Int, ply: Int): Int {
        budget.visit()
        terminal(state, ply)?.let { return it }
        if (depth <= 0) return quiet(state, alphaIn, betaIn, ply, 3)
        val key = key(state, ply)
        val cached = table[key]
        var alpha = alphaIn
        var beta = betaIn
        if (cached != null && cached.depth >= depth) {
            when (cached.bound) {
                Bound.EXACT -> return cached.score
                Bound.LOWER -> alpha = maxOf(alpha, cached.score)
                Bound.UPPER -> beta = minOf(beta, cached.score)
            }
            if (alpha >= beta) return cached.score
        }
        val alphaStart = alpha
        val betaStart = beta
        val moves = XiangqiEngine.legalMoves(state)
        if (moves.isEmpty()) return -mate + ply
        var best = -mate * 2
        var bestMove: XiangqiMove? = null
        for (move in order(state, moves, cached?.move)) {
            val next = XiangqiEngine.play(state, move)
            val score = -search(next, depth - 1, -beta, -alpha, ply + 1)
            if (score > best) {
                best = score
                bestMove = move
            }
            alpha = maxOf(alpha, score)
            if (alpha >= beta) {
                if (state.pieceAt(move.to.x, move.to.y) == 0) {
                    val index = moveIndex(move)
                    history[index] = (history[index] + depth * depth).coerceAtMost(10_000)
                }
                break
            }
        }
        // A cached bound that tightened the window can turn a nominal exact result into a bound.
        val bound = when {
            best <= alphaStart -> Bound.UPPER
            best >= betaStart -> Bound.LOWER
            else -> Bound.EXACT
        }
        table[key] = XiangqiEntry(depth, best, bound, bestMove)
        return best
    }

    private fun quiet(state: XiangqiState, alphaIn: Int, beta: Int, ply: Int, remaining: Int): Int {
        budget.visit()
        terminal(state, ply)?.let { return it }
        val inCheck = XiangqiEngine.isInCheck(state, state.turnSide)
        val moves = XiangqiEngine.legalMoves(state)
        if (moves.isEmpty()) return -mate + ply
        if (remaining <= 0) return evaluate(state)
        var alpha = alphaIn
        var best = if (inCheck) -mate * 2 else evaluate(state)
        if (!inCheck) {
            if (best >= beta) return best
            alpha = maxOf(alpha, best)
        }
        // Checked nodes must search every legal evasion; standing still is never a legal option.
        val tactical = if (inCheck) moves else moves.filter { state.pieceAt(it.to.x, it.to.y) != 0 }
        for (move in order(state, tactical, null)) {
            val score = -quiet(XiangqiEngine.play(state, move), -beta, -alpha, ply + 1, remaining - 1)
            best = maxOf(best, score)
            alpha = maxOf(alpha, score)
            if (alpha >= beta) break
        }
        return best
    }

    private fun terminal(state: XiangqiState, ply: Int): Int? = when (state.outcome) {
        XiangqiOutcome.PLAYING -> null
        XiangqiOutcome.RED_WON -> if (state.turnSide == XiangqiSide.RED) mate - ply else -mate + ply
        XiangqiOutcome.BLACK_WON -> if (state.turnSide == XiangqiSide.BLACK) mate - ply else -mate + ply
    }

    private fun evaluate(state: XiangqiState): Int {
        var redScore = 0
        for (index in state.board.indices) {
            val piece = state.board[index]
            if (piece == 0) continue
            val x = index % 9
            val y = if (piece > 0) 9 - index / 9 else index / 9
            val centrality = 4 - abs(4 - x)
            val type = abs(piece)
            val position = when (type) {
                XiangqiEngine.PAWN -> y * 7 + if (y >= 5) 70 + centrality * 9 else 0
                XiangqiEngine.HORSE -> centrality * 12 + minOf(y, 7) * 5 - if (x == 0 || x == 8) 18 else 0
                XiangqiEngine.CANNON -> centrality * 7 + minOf(y, 6) * 3
                XiangqiEngine.ROOK -> centrality * 3 + y * 3
                XiangqiEngine.ADVISOR, XiangqiEngine.ELEPHANT -> if (y <= 4) 8 else 0
                else -> 0
            }
            redScore += (values[type] + position) * if (piece > 0) 1 else -1
        }
        var score = redScore * state.turnSide.sign
        if (XiangqiEngine.isInCheck(state, state.turnSide)) score -= 45
        if (XiangqiEngine.isInCheck(state, state.turnSide.opponent)) score += 45
        return score
    }

    private fun order(state: XiangqiState, moves: List<XiangqiMove>, preferred: XiangqiMove?): List<XiangqiMove> =
        moves.sortedByDescending { move ->
            if (move == preferred) 2_000_000 else {
                val victim = abs(state.pieceAt(move.to.x, move.to.y))
                val attacker = abs(state.pieceAt(move.from.x, move.from.y))
                (if (victim != 0) 20_000 + values[victim] * 16 - values[attacker] else 0) +
                    history[moveIndex(move)] + (4 - abs(4 - move.to.x)) * 2
            }
        }

    private fun moveIndex(move: XiangqiMove): Int =
        (move.from.y * 9 + move.from.x) * 90 + move.to.y * 9 + move.to.x

    private fun key(state: XiangqiState, ply: Int): Long {
        var hash = -3750763034362895579L
        for (piece in state.board) hash = (hash xor (piece + 7).toLong()) * 1099511628211L
        return ((hash xor state.turnSide.sign.toLong()) * 1099511628211L) xor ply.toLong()
    }
}

private data class GomokuCandidate(val cell: GridCell, val attack: Int, val defense: Int) {
    val priority: Int get() = attack * 11 / 10 + defense
}
private data class GomokuEntry(val depth: Int, val score: Int, val bound: Bound, val move: GridCell?)

private class GomokuSearch(shouldCancel: () -> Boolean) {
    private val budget = SearchBudget(shouldCancel)
    private val table = HashMap<Long, GomokuEntry>()
    private val axes = listOf(GridCell(1, 0), GridCell(0, 1), GridCell(1, 1), GridCell(1, -1))
    private val mate = 5_000_000

    fun choose(state: GomokuState): GridCell? {
        var completed: GridCell? = null
        try {
            budget.visit()
            if (state.board.none { it != 0 }) return GridCell(state.size / 2, state.size / 2)
                .takeUnless { budget.finishCancelled() }
            val candidates = candidates(state)
            if (candidates.isEmpty()) return null
            val immediate = candidates.firstOrNull { it.attack >= mate }
            if (immediate != null) return immediate.cell.takeUnless { budget.finishCancelled() }
            completed = candidates.first().cell
            for (depth in 1..6) {
                budget.visit()
                var best = completed
                var bestScore = -mate * 2
                var alpha = -mate * 2
                for (candidate in ordered(candidates, completed).take(22)) {
                    budget.visit()
                    val next = GomokuEngine.play(state, candidate.cell.x, candidate.cell.y)
                    val score = -search(next, depth - 1, -mate * 2, -alpha, 1, 2)
                    if (score > bestScore) {
                        bestScore = score
                        best = candidate.cell
                    }
                    alpha = maxOf(alpha, score)
                }
                completed = best
                if (abs(bestScore) >= mate - 100) break
            }
        } catch (_: SearchStopped) {
            // Keep the last fully searched root result when the local work budget expires.
        }
        return completed.takeUnless { budget.finishCancelled() }
    }

    private fun search(
        state: GomokuState,
        depth: Int,
        alphaIn: Int,
        betaIn: Int,
        ply: Int,
        forcedExtension: Int,
    ): Int {
        budget.visit()
        // GomokuEngine keeps the winning player as currentPlayer in terminal positions.
        if (state.outcome == GomokuOutcome.DRAW) return 0
        if (state.outcome != GomokuOutcome.PLAYING) return -mate + ply
        val options = candidates(state)
        if (options.isEmpty()) return 0
        if (options.any { it.attack >= mate }) return mate - ply - 1
        val threatened = options.any { it.defense >= mate }
        if (threatened && options.count { it.defense >= mate } >= 2) return -mate + ply + 2
        if (depth <= 0 && (!threatened || forcedExtension <= 0)) return evaluate(state)
        val key = key(state, ply)
        val cached = table[key]
        var alpha = alphaIn
        var beta = betaIn
        // Forced leaf extensions use a different horizon from ordinary searched depths.
        if (depth > 0 && cached != null && cached.depth >= depth) {
            when (cached.bound) {
                Bound.EXACT -> return cached.score
                Bound.LOWER -> alpha = maxOf(alpha, cached.score)
                Bound.UPPER -> beta = minOf(beta, cached.score)
            }
            if (alpha >= beta) return cached.score
        }
        val alphaStart = alpha
        val betaStart = beta
        var best = -mate * 2
        var bestMove: GridCell? = null
        val limit = if (depth >= 3) 10 else 14
        for (candidate in ordered(options, cached?.move).take(limit)) {
            val next = GomokuEngine.play(state, candidate.cell.x, candidate.cell.y)
            val score = -search(next, depth - 1, -beta, -alpha, ply + 1,
                forcedExtension - if (depth <= 0) 1 else 0)
            if (score > best) {
                best = score
                bestMove = candidate.cell
            }
            alpha = maxOf(alpha, score)
            if (alpha >= beta) break
        }
        if (depth > 0) table[key] = GomokuEntry(depth, best, when {
            best <= alphaStart -> Bound.UPPER
            best >= betaStart -> Bound.LOWER
            else -> Bound.EXACT
        }, bestMove)
        return best
    }

    private fun candidates(state: GomokuState): List<GomokuCandidate> {
        val near = BooleanArray(state.board.size)
        for (index in state.board.indices) {
            if (state.board[index] == 0) continue
            val x = index % state.size
            val y = index / state.size
            for (dy in -2..2) for (dx in -2..2) {
                val nx = x + dx
                val ny = y + dy
                if (inside(state, nx, ny) && state.cellAt(nx, ny) == 0) near[ny * state.size + nx] = true
            }
        }
        val candidates = buildList {
            for (index in near.indices) if (near[index]) {
                val cell = GridCell(index % state.size, index / state.size)
                add(GomokuCandidate(cell, pointScore(state, cell, state.currentPlayer),
                    pointScore(state, cell, 3 - state.currentPlayer)))
            }
        }.sortedWith(compareByDescending<GomokuCandidate> { it.priority }
            .thenBy { abs(it.cell.x - state.size / 2) + abs(it.cell.y - state.size / 2) }
            .thenBy { it.cell.y * state.size + it.cell.x })
        val wins = candidates.filter { it.attack >= mate }
        if (wins.isNotEmpty()) return wins
        val blocks = candidates.filter { it.defense >= mate }
        return blocks.ifEmpty { candidates }
    }

    private fun ordered(options: List<GomokuCandidate>, preferred: GridCell?): List<GomokuCandidate> =
        if (preferred == null) options else options.sortedByDescending {
            if (it.cell == preferred) Int.MAX_VALUE else it.priority
        }

    /** Score both contiguous and broken lines; double threats receive a nonlinear bonus. */
    private fun pointScore(state: GomokuState, cell: GridCell, player: Int): Int {
        var score = 0
        var strongThrees = 0
        var fours = 0
        for (axis in axes) {
            var forward = 0
            var backward = 0
            while (inside(state, cell.x + axis.x * (forward + 1), cell.y + axis.y * (forward + 1)) &&
                state.cellAt(cell.x + axis.x * (forward + 1), cell.y + axis.y * (forward + 1)) == player) forward++
            while (inside(state, cell.x - axis.x * (backward + 1), cell.y - axis.y * (backward + 1)) &&
                state.cellAt(cell.x - axis.x * (backward + 1), cell.y - axis.y * (backward + 1)) == player) backward++
            val length = 1 + forward + backward
            if (length >= 5) return mate
            fun emptyAt(step: Int): Boolean {
                val x = cell.x + axis.x * step
                val y = cell.y + axis.y * step
                return inside(state, x, y) && state.cellAt(x, y) == 0
            }
            val open = (if (emptyAt(forward + 1)) 1 else 0) + (if (emptyAt(-backward - 1)) 1 else 0)
            var axisScore = when {
                open == 0 -> 0
                length == 4 -> if (open == 2) 120_000 else 16_000
                length == 3 -> if (open == 2) 6_000 else 450
                length == 2 -> if (open == 2) 240 else 45
                else -> if (open == 2) 12 else 2
            }
            // A five-cell unblocked window catches e.g. XX_X_ that a run-only AI misses.
            var strongestWindow = 0
            for (start in -4..0) {
                var stones = 0
                var blocked = false
                for (step in start until start + 5) {
                    val x = cell.x + axis.x * step
                    val y = cell.y + axis.y * step
                    if (!inside(state, x, y)) { blocked = true; break }
                    val occupant = if (step == 0) player else state.cellAt(x, y)
                    if (occupant == 3 - player) { blocked = true; break }
                    if (occupant == player) stones++
                }
                if (!blocked) strongestWindow = maxOf(strongestWindow, when (stones) {
                    4 -> 14_000
                    3 -> 800
                    2 -> 60
                    else -> 5
                })
            }
            axisScore = maxOf(axisScore, strongestWindow)
            if (axisScore >= 14_000) fours++
            if (length == 3 && open == 2) strongThrees++
            score += axisScore
        }
        if (fours >= 2) score += 400_000
        else if (fours >= 1 && strongThrees >= 1) score += 120_000
        else if (strongThrees >= 2) score += 30_000
        return score
    }

    private fun evaluate(state: GomokuState): Int {
        val own = patternValue(state, state.currentPlayer)
        val other = patternValue(state, 3 - state.currentPlayer)
        // Threats by the side about to move carry a small tempo advantage, not a fictitious win.
        return (own * 11 / 10 - other).coerceIn(-mate / 2, mate / 2)
    }

    private fun patternValue(state: GomokuState, player: Int): Int {
        var score = 0
        for (index in state.board.indices) {
            if (state.board[index] != player) continue
            val x = index % state.size
            val y = index / state.size
            for (axis in axes) {
                // Count each run once and retain whether either end can extend it.
                val px = x - axis.x
                val py = y - axis.y
                if (inside(state, px, py) && state.cellAt(px, py) == player) continue
                var length = 1
                while (inside(state, x + axis.x * length, y + axis.y * length) &&
                    state.cellAt(x + axis.x * length, y + axis.y * length) == player) length++
                val ex = x + axis.x * length
                val ey = y + axis.y * length
                val open = (if (inside(state, px, py) && state.cellAt(px, py) == 0) 1 else 0) +
                    (if (inside(state, ex, ey) && state.cellAt(ex, ey) == 0) 1 else 0)
                score += when {
                    length >= 5 -> mate / 2
                    open == 0 -> 0
                    length == 4 -> if (open == 2) 120_000 else 14_000
                    length == 3 -> if (open == 2) 5_500 else 380
                    length == 2 -> if (open == 2) 200 else 35
                    else -> if (open == 2) 5 else 1
                }
            }
        }
        return score
    }

    private fun key(state: GomokuState, ply: Int): Long {
        var hash = -3750763034362895579L
        for (piece in state.board) hash = (hash xor piece.toLong()) * 1099511628211L
        return ((hash xor state.currentPlayer.toLong()) * 1099511628211L) xor ply.toLong()
    }

    private fun inside(state: GomokuState, x: Int, y: Int): Boolean =
        x in 0 until state.size && y in 0 until state.size
}
