package com.jiligulu.app.ui.littleworld

import kotlin.math.abs


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
