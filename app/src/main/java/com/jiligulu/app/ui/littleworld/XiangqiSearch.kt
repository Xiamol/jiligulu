package com.jiligulu.app.ui.littleworld

import kotlin.math.abs

/** Resource profiles are deliberately separate: a hidden assist is not the ordinary CPU with a new label. */
internal enum class XiangqiSearchProfile(
    val maxDepth: Int, val nodeLimit: Int, val checkExtensions: Int,
    val quietPlies: Int, val quietChecks: Int, val tableLimit: Int,
) {
    NORMAL(10, 300_000, 2, 6, 1, 65_536),
    ASSIST(14, 1_200_000, 4, 10, 3, 196_608),
}

internal enum class XiangqiSearchStop { COMPLETED, TIME_LIMIT, NODE_LIMIT, CANCELLED }

/** Pure local assistance. Call from a worker dispatcher and cancel when the requested position changes. */
object XiangqiStrongMoveHelper {
    const val DEFAULT_BUDGET_MILLIS = 5_000L
    const val MAX_BUDGET_MILLIS = 6_000L

    fun chooseMove(
        state: XiangqiState,
        timeBudgetMillis: Long = DEFAULT_BUDGET_MILLIS,
        shouldCancel: () -> Boolean = { false },
    ): XiangqiMove? = analyze(state, timeBudgetMillis, shouldCancel = shouldCancel).move

    internal fun chooseNormalMove(
        state: XiangqiState, timeBudgetMillis: Long = 700,
        shouldCancel: () -> Boolean = { false },
    ): XiangqiMove? = analyze(state, timeBudgetMillis, maxDepth = XiangqiSearchProfile.NORMAL.maxDepth,
        profile = XiangqiSearchProfile.NORMAL, shouldCancel = shouldCancel).move

    /** Completed iterations, resource use and the stop cause can be compared without claiming an Elo rating. */
    internal fun analyze(
        state: XiangqiState, timeBudgetMillis: Long = DEFAULT_BUDGET_MILLIS,
        maxDepth: Int = XiangqiSearchProfile.ASSIST.maxDepth,
        profile: XiangqiSearchProfile = XiangqiSearchProfile.ASSIST,
        nodeLimit: Int = profile.nodeLimit,
        shouldCancel: () -> Boolean = { false },
    ): XiangqiSearchReport = XiangqiSearch(shouldCancel,
        timeBudgetMillis.coerceIn(50, if (profile == XiangqiSearchProfile.NORMAL) 3_000 else MAX_BUDGET_MILLIS),
        maxDepth.coerceIn(1, 18), profile, nodeLimit.coerceIn(64, 2_000_000)).choose(state)

    /** Deterministic diagnostic mode: node limit replaces the wall clock; cancellation remains active. */
    internal fun analyzeForNodes(
        state: XiangqiState, nodeBudget: Int,
        profile: XiangqiSearchProfile = XiangqiSearchProfile.ASSIST,
        maxDepth: Int = profile.maxDepth,
        shouldCancel: () -> Boolean = { false },
    ): XiangqiSearchReport = XiangqiSearch(shouldCancel, null, maxDepth.coerceIn(1, 18),
        profile, nodeBudget.coerceIn(64, 2_000_000)).choose(state)
}

internal data class XiangqiSearchReport(
    val move: XiangqiMove?, val completedDepth: Int, val nodes: Int, val elapsedMillis: Long,
    val score: Int?, val cancelled: Boolean, val budgetExpired: Boolean, val tableHits: Int,
    val profile: XiangqiSearchProfile, val nodeLimit: Int, val stopReason: XiangqiSearchStop,
    val quietChecksSearched: Int,
)

private class XiangqiSearchStopped : RuntimeException(null, null, false, false)

/** Independent of Gomoku's search budget. Node-only diagnostics never wait for a wall-clock timeout. */
private class XiangqiSearchBudget(
    private val shouldCancel: () -> Boolean, millis: Long?, private val nodeLimit: Int,
) {
    private val started = System.nanoTime()
    private val deadline = millis?.let { started + it * 1_000_000L }
    var nodes = 0
        private set
    val elapsedMillis: Long get() = (System.nanoTime() - started) / 1_000_000L
    var stopReason: XiangqiSearchStop? = null
        private set
    val expired: Boolean get() = stopReason == XiangqiSearchStop.TIME_LIMIT || stopReason == XiangqiSearchStop.NODE_LIMIT

    fun visit() {
        nodes++
        if (nodes == 1 || nodes and 15 == 0) {
            if (shouldCancel() || Thread.currentThread().isInterrupted) {
                stopReason = XiangqiSearchStop.CANCELLED
                throw XiangqiSearchStopped()
            }
            if (nodes >= nodeLimit) {
                stopReason = XiangqiSearchStop.NODE_LIMIT
                throw XiangqiSearchStopped()
            }
            if (deadline != null && System.nanoTime() >= deadline) {
                stopReason = XiangqiSearchStop.TIME_LIMIT
                throw XiangqiSearchStopped()
            }
        }
    }

    fun finishCancelled(): Boolean = stopReason == XiangqiSearchStop.CANCELLED ||
        shouldCancel() || Thread.currentThread().isInterrupted
}

private enum class XiangqiBound { EXACT, LOWER, UPPER }

private data class XiangqiQuietKey(val position: Long, val remaining: Int, val checksLeft: Int)
private data class XiangqiExchangeKey(val position: Long, val moveOrSquare: Int, val remaining: Int)

private data class XiangqiEntry(val depth: Int, val score: Int, val bound: XiangqiBound, val move: XiangqiMove?)

/** Original implementation of standard alpha-beta techniques; no external engine code or weights. */
private class XiangqiSearch(shouldCancel: () -> Boolean, timeBudgetMillis: Long?, private val maxDepth: Int,
    private val profile: XiangqiSearchProfile, private val nodeLimit: Int) {
    private val budget = XiangqiSearchBudget(shouldCancel, timeBudgetMillis, nodeLimit)
    private val table = HashMap<Long, XiangqiEntry>()
    private val history = Array(2) { IntArray(90 * 90) }
    private val killers = Array(64) { arrayOfNulls<XiangqiMove>(2) }
    private val path = LongArray(64)
    private val quietTable = HashMap<XiangqiQuietKey, XiangqiEntry>()
    private val exchangeTable = HashMap<XiangqiExchangeKey, Int>()
    private var tableHits = 0
    private var quietChecksSearched = 0
    private val values = intArrayOf(0, 100_000, 210, 220, 440, 950, 470, 110)
    private val mate = 1_000_000

    fun choose(state: XiangqiState): XiangqiSearchReport {
        var completed: XiangqiMove? = null
        var completedDepth = 0
        var completedScore: Int? = null
        fun report(): XiangqiSearchReport {
            val cancelled = budget.finishCancelled()
            return XiangqiSearchReport(completed.takeUnless { cancelled }, completedDepth, budget.nodes,
                budget.elapsedMillis, completedScore, cancelled, budget.expired, tableHits, profile, nodeLimit,
                if (cancelled) XiangqiSearchStop.CANCELLED else budget.stopReason ?: XiangqiSearchStop.COMPLETED,
                quietChecksSearched)
        }
        try {
            budget.visit()
            if (state.outcome != XiangqiOutcome.PLAYING) return report()
            val moves = XiangqiEngine.legalMoves(state)
            if (moves.isEmpty()) return report()
            completed = moves.first()
            if (moves.size == 1) return report()
            completed = order(state, moves, null, 0).first()
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
                    val score = -search(next, depth - 1, -mate * 2, -alpha, 1, profile.checkExtensions)
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
                // Quiescence can prove a long mate before the main tree has examined a shorter one.
                // Keep deepening until the full tree covers the replies preceding that mate.
                // A mate in one still returns immediately above; every continuation keeps the same hard budget.
                if (abs(bestScore) >= mate - 100 && depth >= (mate - abs(bestScore) - 1).coerceAtLeast(1)) break
            }
        } catch (_: XiangqiSearchStopped) {
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
        if (depth <= 0) return quiet(state, alphaIn, betaIn, ply, profile.quietPlies, profile.quietChecks)
        val tableKey = positionKey xor (remainingExtensions.toLong() * -7046029254386353131L)
        val cached = table[tableKey]
        var alpha = alphaIn
        var beta = betaIn
        if (cached != null && cached.depth >= depth) {
            tableHits++
            val cachedScore = fromTableScore(cached.score, ply)
            when (cached.bound) {
                XiangqiBound.EXACT -> return cachedScore
                XiangqiBound.LOWER -> alpha = maxOf(alpha, cachedScore)
                XiangqiBound.UPPER -> beta = minOf(beta, cachedScore)
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
            // Only late, nonchecking quiet moves in a null window get a shallow first probe.
            // Captures/checks and principal variations keep their full depth; a promising probe is re-searched.
            val reduce = profile == XiangqiSearchProfile.ASSIST && depth >= 4 && index >= 6 &&
                beta - alpha == 1 && !inCheck && state.pieceAt(move.to.x, move.to.y) == 0 &&
                !XiangqiEngine.isInCheck(next, next.turnSide)
            var score = if (index == 0) -search(next, depth - 1, -beta, -alpha, ply + 1, remainingExtensions)
                else -search(next, depth - if (reduce) 2 else 1, -alpha - 1, -alpha, ply + 1, remainingExtensions)
            if (reduce && score > alpha) {
                score = -search(next, depth - 1, -alpha - 1, -alpha, ply + 1, remainingExtensions)
            }
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
            best <= alphaStart -> XiangqiBound.UPPER
            best >= betaStart -> XiangqiBound.LOWER
            else -> XiangqiBound.EXACT
        }
        if (table.size < profile.tableLimit || cached != null) {
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
        var alpha = alphaIn
        var windowBeta = beta
        val quietKey = XiangqiQuietKey(positionKey, remaining.coerceAtLeast(0), checksLeft)
        val cached = if (profile == XiangqiSearchProfile.ASSIST && ply < 62 && remaining > 0) quietTable[quietKey] else null
        if (cached != null) {
            tableHits++
            val cachedScore = fromTableScore(cached.score, ply)
            when (cached.bound) {
                XiangqiBound.EXACT -> return cachedScore
                XiangqiBound.LOWER -> alpha = maxOf(alpha, cachedScore)
                XiangqiBound.UPPER -> windowBeta = minOf(windowBeta, cachedScore)
            }
            if (alpha >= windowBeta) return cachedScore
        }
        // Cache cutoffs avoid regenerating all legal moves for a repeated tactical position.
        val moves = XiangqiEngine.legalMoves(state)
        if (moves.isEmpty()) return -mate + ply
        // Checked nodes must evade even after the capture horizon; only the bounded path cap ends a checking chain.
        if (ply >= (if (profile == XiangqiSearchProfile.ASSIST) 62 else 46) || remaining <= 0 && !inCheck) return evaluate(state)
        val alphaStart = alpha
        val betaStart = windowBeta
        var best = if (inCheck) -mate * 2 else evaluate(state)
        var bestMove: XiangqiMove? = null
        if (!inCheck) {
            if (best >= windowBeta) return best
            alpha = maxOf(alpha, best)
        }
        // Checked nodes must search every legal evasion; standing still is never a legal option.
        for (move in order(state, moves, cached?.move, ply)) {
            val capture = state.pieceAt(move.to.x, move.to.y) != 0
            if (!inCheck && !capture && (checksLeft <= 0 || profile == XiangqiSearchProfile.NORMAL && remaining < 5)) continue
            val next = XiangqiEngine.applyGeneratedMove(state, move)
            val quietCheck = !inCheck && !capture && XiangqiEngine.isInCheck(next, next.turnSide)
            if (!inCheck && !capture && !quietCheck) continue
            if (quietCheck) quietChecksSearched++
            val score = -quiet(next, -windowBeta, -alpha, ply + 1, remaining - 1, checksLeft - if (quietCheck) 1 else 0)
            if (score > best) { best = score; bestMove = move }
            alpha = maxOf(alpha, score)
            if (alpha >= windowBeta) break
        }
        if (profile == XiangqiSearchProfile.ASSIST && (quietTable.size < profile.tableLimit / 2 || cached != null)) {
            val bound = when {
                best <= alphaStart -> XiangqiBound.UPPER
                best >= betaStart -> XiangqiBound.LOWER
                else -> XiangqiBound.EXACT
            }
            quietTable[quietKey] = XiangqiEntry(remaining, toTableScore(best, ply), bound, bestMove)
        }
        return best
    }

    private fun terminal(state: XiangqiState, ply: Int): Int? = when (state.outcome) {
        XiangqiOutcome.PLAYING -> null
        XiangqiOutcome.RED_WON -> if (state.turnSide == XiangqiSide.RED) mate - ply else -mate + ply
        XiangqiOutcome.BLACK_WON -> if (state.turnSide == XiangqiSide.BLACK) mate - ply else -mate + ply
        XiangqiOutcome.DRAW -> 0
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
        if (profile == XiangqiSearchProfile.ASSIST) {
            score += (kingSafety(state, XiangqiSide.RED) - kingSafety(state, XiangqiSide.BLACK)) * state.turnSide.sign
        }
        return score
    }

    /** Local palace shelter and enemy heavy-piece pressure; independent of side-to-move symmetry. */
    private fun kingSafety(state: XiangqiState, side: XiangqiSide): Int {
        val kingIndex = state.board.indexOf(side.sign * XiangqiEngine.GENERAL)
        if (kingIndex < 0) return -mate
        val kingX = kingIndex % 9
        val kingY = kingIndex / 9
        var guards = 0
        var danger = 0
        for (index in state.board.indices) {
            val piece = state.board[index]
            if (piece == 0) continue
            val type = abs(piece)
            val x = index % 9
            val y = index / 9
            if (piece * side.sign > 0) {
                if (type == XiangqiEngine.ADVISOR) guards += 18
                if (type == XiangqiEngine.ELEPHANT) guards += 12
                continue
            }
            val distance = abs(x - kingX) + abs(y - kingY)
            if (type == XiangqiEngine.HORSE && distance <= 4) danger += (5 - distance) * 16
            if (type == XiangqiEngine.PAWN && distance <= 3) danger += (4 - distance) * 25
            if ((type == XiangqiEngine.ROOK || type == XiangqiEngine.CANNON) && (x == kingX || y == kingY)) {
                val dx = (kingX - x).coerceIn(-1, 1)
                val dy = (kingY - y).coerceIn(-1, 1)
                var screens = 0
                var file = x + dx
                var rank = y + dy
                while (file != kingX || rank != kingY) {
                    if (state.pieceAt(file, rank) != 0) screens++
                    file += dx; rank += dy
                }
                danger += if (type == XiangqiEngine.ROOK) when (screens) { 0 -> 95; 1 -> 30; else -> 0 }
                    else when (screens) { 0 -> 16; 1 -> 95; 2 -> 24; else -> 0 }
            }
        }
        val advanced = if (side == XiangqiSide.RED) 9 - kingY else kingY
        return guards - danger - advanced * 12
    }

    private val raySteps = listOf(0 to -1, -1 to 0, 1 to 0, 0 to 1)

    private fun order(state: XiangqiState, moves: List<XiangqiMove>, preferred: XiangqiMove?, ply: Int): List<XiangqiMove> {
        val positionKey = if (profile == XiangqiSearchProfile.ASSIST) key(state) else 0L
        val ranks = moves.associateWith { move ->
            if (move == preferred) 2_000_000 else {
                val victim = abs(state.pieceAt(move.to.x, move.to.y))
                val attacker = abs(state.pieceAt(move.from.x, move.from.y))
                val captureRank = if (victim == 0) 0 else if (profile == XiangqiSearchProfile.NORMAL)
                    20_000 + values[victim] * 16 - values[attacker]
                else {
                    val gain = exchangeGain(state, move, positionKey)
                    (if (gain >= 0) 30_000 else -5_000) + gain * 8 + values[victim] - values[attacker] / 16
                }
                captureRank +
                    (if (move == killers[ply][0]) 15_000 else if (move == killers[ply][1]) 14_000 else 0) +
                    history[if (state.turnSide == XiangqiSide.RED) 0 else 1][moveIndex(move)] +
                    (4 - abs(4 - move.to.x)) * 2
            }
        }
        return moves.sortedByDescending { ranks.getValue(it) }
    }

    /** Legal least-value recaptures provide ordering only; no sacrifice is discarded by this approximation. */
    private fun exchangeGain(state: XiangqiState, move: XiangqiMove, positionKey: Long): Int {
        val cacheKey = XiangqiExchangeKey(positionKey, moveIndex(move), -1)
        exchangeTable[cacheKey]?.let { return it }
        val gain = values[abs(state.pieceAt(move.to.x, move.to.y))] -
            recaptureGain(XiangqiEngine.applyGeneratedMove(state, move), move.to, 4)
        if (exchangeTable.size < 65_536) exchangeTable[cacheKey] = gain
        return gain
    }

    private fun recaptureGain(state: XiangqiState, square: GridCell, remaining: Int): Int {
        budget.visit()
        if (remaining == 0 || state.outcome != XiangqiOutcome.PLAYING) return 0
        val cacheKey = XiangqiExchangeKey(key(state), square.y * 9 + square.x, remaining)
        exchangeTable[cacheKey]?.let { return it }
        val recapture = XiangqiEngine.legalMoves(state).asSequence().filter { it.to == square }
            .minByOrNull { values[abs(state.pieceAt(it.from.x, it.from.y))] } ?: return 0
        val gain = (values[abs(state.pieceAt(square.x, square.y))] -
            recaptureGain(XiangqiEngine.applyGeneratedMove(state, recapture), square, remaining - 1)).coerceAtLeast(0)
        if (exchangeTable.size < 65_536) exchangeTable[cacheKey] = gain
        return gain
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

