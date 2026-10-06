package com.jiligulu.app.ui.littleworld

import kotlin.math.abs

/** Local, bounded searches for the seven-tap shortcut. Call from a worker dispatcher. */
object XiangqiStrongMoveHelper {
    fun chooseMove(
        state: XiangqiState,
        timeBudgetMillis: Long = 1_500,
        shouldCancel: () -> Boolean = { false },
    ): XiangqiMove? = analyze(state, timeBudgetMillis, shouldCancel = shouldCancel).move

    /** Diagnostics make search depth/time measurable; scores are heuristic, not a strength rating. */
    internal fun analyze(
        state: XiangqiState,
        timeBudgetMillis: Long = 1_500,
        maxDepth: Int = 10,
        shouldCancel: () -> Boolean = { false },
    ): XiangqiSearchReport = XiangqiSearch(
        shouldCancel, timeBudgetMillis.coerceIn(50, 3_000), maxDepth.coerceIn(1, 12)
    ).choose(state)
}

internal data class XiangqiSearchReport(
    val move: XiangqiMove?,
    val completedDepth: Int,
    val nodes: Int,
    val elapsedMillis: Long,
    val score: Int?,
    val cancelled: Boolean,
    val budgetExpired: Boolean,
    val tableHits: Int,
)

object GomokuStrongMoveHelper {
    fun chooseMove(
        state: GomokuState,
        timeBudgetMillis: Long = 1_500,
        shouldCancel: () -> Boolean = { false },
    ): GridCell? {
        if (state.outcome != GomokuOutcome.PLAYING || shouldCancel()) return null
        return GomokuSearch(shouldCancel, timeBudgetMillis.coerceIn(100, 2_000)).choose(state)
    }
}

private class SearchStopped : RuntimeException(null, null, false, false)

private class SearchBudget(private val shouldCancel: () -> Boolean, millis: Long = 850, private val nodeLimit: Int = 100_000) {
    private val started = System.nanoTime()
    private val deadline = started + millis * 1_000_000L
    var nodes = 0
        private set
    val elapsedMillis: Long get() = (System.nanoTime() - started) / 1_000_000L
    var expired = false
        private set
    var cancelled = false
        private set

    fun visit() {
        nodes++
        if (nodes == 1 || nodes and 15 == 0) {
            if (shouldCancel() || Thread.currentThread().isInterrupted) {
                cancelled = true
                throw SearchStopped()
            }
            if (nodes >= nodeLimit || System.nanoTime() >= deadline) {
                expired = true
                throw SearchStopped()
            }
        }
    }

    fun finishCancelled(): Boolean = cancelled || shouldCancel() || Thread.currentThread().isInterrupted
}

private enum class Bound { EXACT, LOWER, UPPER }
private data class XiangqiEntry(val depth: Int, val score: Int, val bound: Bound, val move: XiangqiMove?)

/** Original implementation of standard alpha-beta techniques; no external engine code or weights. */
private class XiangqiSearch(shouldCancel: () -> Boolean, timeBudgetMillis: Long, private val maxDepth: Int) {
    private val budget = SearchBudget(shouldCancel, timeBudgetMillis, nodeLimit = 300_000)
    private val table = HashMap<Long, XiangqiEntry>()
    private val history = Array(2) { IntArray(90 * 90) }
    private val killers = Array(48) { arrayOfNulls<XiangqiMove>(2) }
    private val path = LongArray(48)
    private var tableHits = 0
    private val values = intArrayOf(0, 100_000, 210, 220, 440, 950, 470, 110)
    private val mate = 1_000_000

    fun choose(state: XiangqiState): XiangqiSearchReport {
        var completed: XiangqiMove? = null
        var completedDepth = 0
        var completedScore: Int? = null
        fun report(): XiangqiSearchReport {
            val cancelled = budget.finishCancelled()
            return XiangqiSearchReport(completed.takeUnless { cancelled }, completedDepth, budget.nodes,
                budget.elapsedMillis, completedScore, cancelled, budget.expired, tableHits)
        }
        try {
            budget.visit()
            if (state.outcome != XiangqiOutcome.PLAYING) return report()
            val moves = XiangqiEngine.legalMoves(state)
            if (moves.isEmpty()) return report()
            completed = order(state, moves, null, 0).first()
            if (moves.size == 1) return report()
            path[0] = key(state)
            var rootScores = emptyMap<XiangqiMove, Int>()
            // Commit only complete iterations: an expired search never favors an early partial branch.
            for (depth in 1..maxDepth) {
                budget.visit()
                var best = completed
                var bestScore = -mate * 2
                var alpha = -mate * 2
                val iterationScores = HashMap<XiangqiMove, Int>()
                val ordered = order(state, moves, completed, 0).sortedByDescending {
                    if (it == completed) Int.MAX_VALUE else rootScores[it] ?: Int.MIN_VALUE
                }
                for (move in ordered) {
                    budget.visit()
                    val next = XiangqiEngine.applyGeneratedMove(state, move)
                    val score = -search(next, depth - 1, -mate * 2, -alpha, 1, 2)
                    iterationScores[move] = score
                    if (score > bestScore) {
                        bestScore = score
                        best = move
                    }
                    if (score > alpha) alpha = score
                    if (score >= mate - 1) {
                        completed = move
                        completedDepth = depth
                        completedScore = score
                        return report()
                    }
                }
                completed = best
                completedDepth = depth
                completedScore = bestScore
                rootScores = iterationScores
                if (abs(bestScore) >= mate - 100) break
            }
        } catch (_: SearchStopped) {
            // The previous complete iteration remains legal in the immutable root position.
        }
        return report()
    }

    private fun search(state: XiangqiState, depthIn: Int, alphaIn: Int, betaIn: Int, ply: Int, extensionsLeft: Int): Int {
        budget.visit()
        terminal(state, ply)?.let { return it }
        val positionKey = key(state)
        // Search-only cycle avoidance, not an adjudication of official long-check/long-chase rules.
        if ((0 until ply).any { path[it] == positionKey }) return 0
        path[ply] = positionKey
        val inCheck = XiangqiEngine.isInCheck(state, state.turnSide)
        val extended = inCheck && extensionsLeft > 0
        val depth = depthIn + if (extended) 1 else 0
        val remainingExtensions = extensionsLeft - if (extended) 1 else 0
        if (depth <= 0) return quiet(state, alphaIn, betaIn, ply, 6, 1)
        val tableKey = positionKey xor (remainingExtensions.toLong() * -7046029254386353131L)
        val cached = table[tableKey]
        var alpha = alphaIn
        var beta = betaIn
        if (cached != null && cached.depth >= depth) {
            tableHits++
            val cachedScore = fromTableScore(cached.score, ply)
            when (cached.bound) {
                Bound.EXACT -> return cachedScore
                Bound.LOWER -> alpha = maxOf(alpha, cachedScore)
                Bound.UPPER -> beta = minOf(beta, cachedScore)
            }
            if (alpha >= beta) return cachedScore
        }
        val alphaStart = alpha
        val betaStart = beta
        val moves = XiangqiEngine.legalMoves(state)
        if (moves.isEmpty()) return -mate + ply
        var best = -mate * 2
        var bestMove: XiangqiMove? = null
        for ((index, move) in order(state, moves, cached?.move, ply).withIndex()) {
            val next = XiangqiEngine.applyGeneratedMove(state, move)
            // Principal-variation search: later moves first prove that they can improve alpha.
            var score = if (index == 0) -search(next, depth - 1, -beta, -alpha, ply + 1, remainingExtensions)
                else -search(next, depth - 1, -alpha - 1, -alpha, ply + 1, remainingExtensions)
            if (index > 0 && score > alpha && score < beta) {
                score = -search(next, depth - 1, -beta, -alpha, ply + 1, remainingExtensions)
            }
            if (score > best) {
                best = score
                bestMove = move
            }
            alpha = maxOf(alpha, score)
            if (alpha >= beta) {
                if (state.pieceAt(move.to.x, move.to.y) == 0) {
                    val index = moveIndex(move)
                    val sideHistory = history[if (state.turnSide == XiangqiSide.RED) 0 else 1]
                    sideHistory[index] = (sideHistory[index] + depth * depth).coerceAtMost(10_000)
                    if (killers[ply][0] != move) {
                        killers[ply][1] = killers[ply][0]
                        killers[ply][0] = move
                    }
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
        if (table.size < 65_536 || cached != null) {
            table[tableKey] = XiangqiEntry(depth, toTableScore(best, ply), bound, bestMove)
        }
        return best
    }

    private fun quiet(state: XiangqiState, alphaIn: Int, beta: Int, ply: Int, remaining: Int, checksLeft: Int): Int {
        budget.visit()
        terminal(state, ply)?.let { return it }
        val positionKey = key(state)
        if ((0 until ply).any { path[it] == positionKey }) return 0
        path[ply] = positionKey
        val inCheck = XiangqiEngine.isInCheck(state, state.turnSide)
        val moves = XiangqiEngine.legalMoves(state)
        if (moves.isEmpty()) return -mate + ply
        // The hard cap prevents endless checking chains. Ordinarily a checked node must evade,
        // even after the capture budget is exhausted; it cannot use an illegal stand-pat score.
        if (ply >= 46 || remaining <= 0 && !inCheck) return evaluate(state)
        var alpha = alphaIn
        var best = if (inCheck) -mate * 2 else evaluate(state)
        if (!inCheck) {
            if (best >= beta) return best
            alpha = maxOf(alpha, best)
        }
        // Checked nodes must search every legal evasion; standing still is never a legal option.
        for (move in order(state, moves, null, ply)) {
            val capture = state.pieceAt(move.to.x, move.to.y) != 0
            if (!inCheck && !capture && (checksLeft <= 0 || remaining < 5)) continue
            val next = XiangqiEngine.applyGeneratedMove(state, move)
            val quietCheck = !inCheck && !capture && XiangqiEngine.isInCheck(next, next.turnSide)
            if (!inCheck && !capture && !quietCheck) continue
            val score = -quiet(next, -beta, -alpha, ply + 1, remaining - 1, checksLeft - if (quietCheck) 1 else 0)
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
            val mobility = when (type) {
                XiangqiEngine.HORSE -> {
                    val rank = index / 9
                    val freeLegs = listOf(GridCell(x - 1, rank), GridCell(x + 1, rank),
                        GridCell(x, rank - 1), GridCell(x, rank + 1))
                        .count { it.x in 0..8 && it.y in 0..9 && state.pieceAt(it.x, it.y) == 0 }
                    (freeLegs - 2) * 12
                }
                XiangqiEngine.ROOK, XiangqiEngine.CANNON -> {
                    var open = 0
                    val rank = index / 9
                    for ((dx, dy) in raySteps) {
                        var file = x + dx
                        var row = rank + dy
                        while (file in 0..8 && row in 0..9 && state.pieceAt(file, row) == 0) {
                            open++
                            file += dx
                            row += dy
                        }
                    }
                    open * if (type == XiangqiEngine.ROOK) 3 else 1
                }
                else -> 0
            }
            redScore += (values[type] + position + mobility) * if (piece > 0) 1 else -1
        }
        var score = redScore * state.turnSide.sign
        if (XiangqiEngine.isInCheck(state, state.turnSide)) score -= 45
        if (XiangqiEngine.isInCheck(state, state.turnSide.opponent)) score += 45
        return score
    }

    private val raySteps = listOf(0 to -1, -1 to 0, 1 to 0, 0 to 1)

    private fun order(state: XiangqiState, moves: List<XiangqiMove>, preferred: XiangqiMove?, ply: Int): List<XiangqiMove> =
        moves.sortedByDescending { move ->
            if (move == preferred) 2_000_000 else {
                val victim = abs(state.pieceAt(move.to.x, move.to.y))
                val attacker = abs(state.pieceAt(move.from.x, move.from.y))
                (if (victim != 0) 20_000 + values[victim] * 16 - values[attacker] else 0) +
                    (if (move == killers[ply][0]) 15_000 else if (move == killers[ply][1]) 14_000 else 0) +
                    history[if (state.turnSide == XiangqiSide.RED) 0 else 1][moveIndex(move)] +
                    (4 - abs(4 - move.to.x)) * 2
            }
        }

    private fun moveIndex(move: XiangqiMove): Int =
        (move.from.y * 9 + move.from.x) * 90 + move.to.y * 9 + move.to.x

    private fun key(state: XiangqiState): Long {
        var hash = -3750763034362895579L
        for (piece in state.board) hash = (hash xor (piece + 7).toLong()) * 1099511628211L
        return (hash xor state.turnSide.sign.toLong()) * 1099511628211L
    }

    // Mate distance is root-relative during search and position-relative inside the shared TT.
    private fun toTableScore(score: Int, ply: Int): Int = when {
        score > mate - 100 -> score + ply
        score < -mate + 100 -> score - ply
        else -> score
    }
    private fun fromTableScore(score: Int, ply: Int): Int = when {
        score > mate - 100 -> score - ply
        score < -mate + 100 -> score + ply
        else -> score
    }
}

private data class GomokuCandidate(val cell: GridCell, val attack: Int, val defense: Int) {
    val priority: Int get() = attack * 11 / 10 + defense
}
private data class GomokuEntry(val depth: Int, val score: Int, val bound: Bound, val move: GridCell?)

private class GomokuSearch(shouldCancel: () -> Boolean, timeBudgetMillis: Long) {
    private val budget = SearchBudget(shouldCancel, timeBudgetMillis)
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
            // A split three can make an open four next move, just like a contiguous live three.
            // Merely counting adjacent runs missed these common human attacks.
            val liveThree = length == 3 && open == 2 || (-4..4).any { gap ->
                if (gap == 0 || !emptyAt(gap)) false else {
                    fun mine(step: Int): Boolean = step == 0 || step == gap ||
                        inside(state, cell.x + axis.x * step, cell.y + axis.y * step) &&
                        state.cellAt(cell.x + axis.x * step, cell.y + axis.y * step) == player
                    var low = gap
                    var high = gap
                    while (mine(low - 1)) low--
                    while (mine(high + 1)) high++
                    high - low + 1 >= 4 && emptyAt(low - 1) && emptyAt(high + 1)
                }
            }
            if (liveThree) { strongThrees++; axisScore = maxOf(axisScore, 6_000) }
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
        val potentials = candidates(state)
        val ownThreat = potentials.maxOfOrNull { it.attack } ?: 0
        val otherThreat = potentials.maxOfOrNull { it.defense } ?: 0
        // Threats by the side about to move carry a small tempo advantage, not a fictitious win.
        return (own * 11 / 10 - other + ownThreat * 2 - otherThreat).coerceIn(-mate / 2, mate / 2)
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
