package com.jiligulu.app.ui.littleworld

import kotlin.math.abs

/** Freestyle rules, shared by both colours. Call from a cancellable background dispatcher. */
object GomokuStrongMoveHelper {
    fun chooseMove(
        state: GomokuState,
        timeBudgetMillis: Long = 1_500,
        shouldCancel: () -> Boolean = { false },
    ): GridCell? {
        if (state.outcome != GomokuOutcome.PLAYING || shouldCancel()) return null
        return try {
            GomokuSearch(state, shouldCancel, timeBudgetMillis.coerceIn(100, 5_000)).choose()
        } catch (_: SearchStopped) {
            // Initial pattern construction can exhaust a small budget on a slow device.
            // Cancellation discards the answer; a deadline alone still has a legal fallback.
            if (shouldCancel() || Thread.currentThread().isInterrupted) null
            else GomokuEngine.chooseHeuristicMove(state).takeUnless { shouldCancel() || Thread.currentThread().isInterrupted }
        }
    }
}

private class SearchStopped : RuntimeException(null, null, false, false)
private class ThreatSearchStopped : RuntimeException(null, null, false, false)

private class SearchBudget(private val shouldCancel: () -> Boolean, val millis: Long) {
    private val started = System.nanoTime()
    private val deadline = started + millis * 1_000_000L
    private var checks = 0
    val elapsedMillis: Long get() = (System.nanoTime() - started) / 1_000_000L
    private var threatDeadline = deadline
    private var cancelled = false

    fun visit(threatSearch: Boolean = false) {
        checks++
        if (checks == 1 || checks and 15 == 0) {
            if (shouldCancel() || Thread.currentThread().isInterrupted) {
                cancelled = true
                throw SearchStopped()
            }
            val now = System.nanoTime()
            if (now >= deadline) throw SearchStopped()
            if (threatSearch && now >= threatDeadline) throw ThreatSearchStopped()
        }
    }

    fun threatSlice(untilMillis: Long) {
        threatDeadline = minOf(deadline, started + untilMillis * 1_000_000L)
    }

    fun finishCancelled(): Boolean = cancelled || shouldCancel() || Thread.currentThread().isInterrupted
}

private enum class Bound { EXACT, LOWER, UPPER }
private data class SearchEntry(val depth: Int, val score: Int, val bound: Bound, val move: Int)
private data class ThreatKey(val hash: Long, val player: Int, val depth: Int, val threes: Boolean)
private data class Candidate(val index: Int, val priority: Int, val tactical: Boolean)

/**
 * Alpha-beta is complemented by actual continuous-four/three proof searches. A proof
 * checks the defender's counter-win and every legal response to an unforced three;
 * a large pattern score alone is never reported as a forced win.
 */
private class GomokuSearch(state: GomokuState, shouldCancel: () -> Boolean, millis: Long) {
    private val budget = SearchBudget(shouldCancel, millis)
    private val position = GomokuThreatPosition(state) { budget.visit() }
    private val player = state.currentPlayer
    private val table = HashMap<Long, SearchEntry>()
    private val threats = HashMap<ThreatKey, Int>()
    private val history = Array(3) { IntArray(state.board.size) }
    private val mate = 5_000_000

    fun choose(): GridCell? {
        var completed = -1
        try {
            budget.visit()
            if (position.emptyCount == position.cells.size) {
                return position.cell(position.size / 2 * position.size + position.size / 2)
                    .takeUnless { budget.finishCancelled() }
            }
            var root = candidates(player, 48)
            if (root.isEmpty()) return null
            completed = root.first().index
            val wins = position.winningMoves(player)
            if (wins.isNotEmpty()) return position.cell(wins.first()).takeUnless { budget.finishCancelled() }
            val mustBlock = position.winningMoves(3 - player)
            if (mustBlock.size == 1) return position.cell(mustBlock.first()).takeUnless { budget.finishCancelled() }
            val fork = root.firstOrNull { GomokuThreatPosition.wins(position.info(player, it.index)) >= 2 }
            if (fork != null && mustBlock.isEmpty()) {
                return position.cell(fork.index).takeUnless { budget.finishCancelled() }
            }

            // Keep most of the turn for the main search even when the proof tree is hard.
            // VCF can look beyond the ordinary minimax horizon without guessing replies.
            try {
                budget.threatSlice(budget.millis * 24 / 100)
                val winning = prove(player, 12, false)
                if (winning >= 0) return position.cell(winning).takeUnless { budget.finishCancelled() }
            } catch (_: ThreatSearchStopped) { /* No proof, rather than a fictitious win. */ }

            // Reject quiet moves that demonstrably walk into the opponent's forcing line.
            // The defence is checked on the resulting board, not only at its first point.
            try {
                budget.threatSlice(budget.millis * 42 / 100)
                val opponentAttack = prove(3 - player, 10, false)
                if (opponentAttack >= 0) {
                    val safe = ArrayList<Candidate>()
                    for (option in root) {
                        budget.visit(true)
                        position.make(option.index, player)
                        val losing = try { prove(3 - player, 10, false) >= 0 } finally { position.unmake() }
                        if (!losing) safe.add(option)
                    }
                    if (safe.isNotEmpty()) {
                        root = safe
                        completed = root.first().index
                    }
                }
            } catch (_: ThreatSearchStopped) { /* A partial screening is not committed. */ }

            try {
                budget.threatSlice(budget.millis * 55 / 100)
                val winning = prove(player, 4, true)
                if (winning >= 0) return position.cell(winning).takeUnless { budget.finishCancelled() }
            } catch (_: ThreatSearchStopped) { /* Continue with the completed root result. */ }

            for (depth in 1..10) {
                budget.visit()
                val ordered = root.sortedByDescending { if (it.index == completed) Int.MAX_VALUE else it.priority }
                var best = completed
                var bestScore = -mate * 2
                var alpha = -mate * 2
                for ((order, option) in ordered.withIndex()) {
                    budget.visit()
                    position.make(option.index, player)
                    val score = try {
                        if (order == 0) -search(3 - player, depth - 1, -mate * 2, -alpha, 1, 14)
                        else {
                            var probe = -search(3 - player, depth - 1, -alpha - 1, -alpha, 1, 14)
                            if (probe > alpha) probe = -search(3 - player, depth - 1, -mate * 2, -alpha, 1, 14)
                            probe
                        }
                    } finally { position.unmake() }
                    if (score > bestScore) { bestScore = score; best = option.index }
                    alpha = maxOf(alpha, score)
                }
                completed = best
                if (abs(bestScore) >= mate - 100) break
            }
        } catch (_: SearchStopped) {
            // Never publish a half-searched root iteration. The immutable input is untouched.
        }
        return if (completed < 0 || budget.finishCancelled()) null else position.cell(completed)
    }

    /** Return a proven winning first move, or -1 when no proof exists inside this horizon. */
    private fun prove(attacker: Int, depth: Int, includeThrees: Boolean): Int {
        budget.visit(true)
        val wins = position.winningMoves(attacker)
        if (wins.isNotEmpty()) return wins.first()
        if (depth <= 0 || position.emptyCount == 0) return -1
        val opponent = 3 - attacker
        val counterWins = position.winningMoves(opponent)
        if (counterWins.size >= 2) return -1
        val key = ThreatKey(position.hash, attacker, depth, includeThrees)
        threats[key]?.let { return it }
        val attacks = candidates(attacker, 0).filter {
            (counterWins.isEmpty() || it.index == counterWins.first()) &&
                (GomokuThreatPosition.wins(position.info(attacker, it.index)) > 0 ||
                    includeThrees && GomokuThreatPosition.threes(position.info(attacker, it.index)) > 0)
        }
        for (attack in attacks) {
            budget.visit(true)
            position.make(attack.index, attacker)
            val proven = try {
                val direct = position.winningMoves(attacker)
                when {
                    position.winningMoves(opponent).isNotEmpty() -> false
                    direct.size >= 2 -> true
                    direct.size == 1 -> {
                        // The defender has one and only one legal non-losing response.
                        position.make(direct.first(), opponent)
                        try { prove(attacker, depth - 1, includeThrees) >= 0 } finally { position.unmake() }
                    }
                    !includeThrees -> false
                    else -> {
                        // A three leaves the defender free to counterattack. All empty
                        // squares include distant counter-fours and intersecting defences.
                        var allRepliesLose = true
                        val replies = candidates(opponent, position.cells.size).map { it.index }.toMutableList()
                        val seen = BooleanArray(position.cells.size)
                        replies.forEach { seen[it] = true }
                        for (index in position.cells.indices) if (position.cells[index] == 0 && !seen[index]) replies.add(index)
                        for (reply in replies) {
                            budget.visit(true)
                            position.make(reply, opponent)
                            val continuation = try { prove(attacker, depth - 1, true) >= 0 } finally { position.unmake() }
                            if (!continuation) { allRepliesLose = false; break }
                        }
                        allRepliesLose
                    }
                }
            } finally { position.unmake() }
            if (proven) { threats[key] = attack.index; return attack.index }
        }
        threats[key] = -1
        return -1
    }

    private fun search(side: Int, depth: Int, alphaIn: Int, betaIn: Int, ply: Int, extension: Int): Int {
        budget.visit()
        if (position.emptyCount == 0) return 0
        val winning = position.winningMoves(side)
        if (winning.isNotEmpty()) return mate - ply - 1
        val opponent = 3 - side
        val blocks = position.winningMoves(opponent)
        if (blocks.size >= 2) return -mate + ply + 2
        if (blocks.isEmpty() && position.hasFork(side)) return mate - ply - 3
        if (ply >= 48) return evaluate(side)
        val key = position.hash xor if (side == 1) -7046029254386353131L else -3335678366873096957L
        val cached = table[key]
        var alpha = alphaIn
        var beta = betaIn
        if (depth > 0 && cached != null && cached.depth >= depth) {
            val value = fromTable(cached.score, ply)
            when (cached.bound) {
                Bound.EXACT -> return value
                Bound.LOWER -> alpha = maxOf(alpha, value)
                Bound.UPPER -> beta = minOf(beta, value)
            }
            if (alpha >= beta) return value
        }

        var options = if (blocks.size == 1) listOf(Candidate(blocks.first(), Int.MAX_VALUE, true))
            else candidates(side, if (depth >= 4) 16 else 22)
        if (options.isEmpty()) return 0
        val forcedDefence = blocks.isNotEmpty() || position.hasFork(opponent)
        var standPat = -mate * 2
        if (depth <= 0) {
            if (extension <= 0) return evaluate(side)
            if (!forcedDefence) {
                standPat = evaluate(side)
                if (standPat >= beta) return standPat
                alpha = maxOf(alpha, standPat)
                options = options.filter { GomokuThreatPosition.wins(position.info(side, it.index)) > 0 }
                if (options.isEmpty()) return standPat
            }
        }
        options = options.sortedByDescending {
            if (it.index == cached?.move) Int.MAX_VALUE else it.priority + history[side][it.index]
        }
        val alphaStart = alpha
        val betaStart = beta
        var best = standPat
        var bestMove = -1
        for ((order, option) in options.withIndex()) {
            val own = position.info(side, option.index)
            position.make(option.index, side)
            val score = try {
                // A quiet defence that still permits an open four/double-four loses;
                // a forcing counter-four is retained and searched with the right tempo.
                if (forcedDefence && blocks.isEmpty() && GomokuThreatPosition.wins(own) == 0 && position.hasFork(opponent)) {
                    -mate + ply + 4
                } else {
                    val reduction = if (depth >= 4 && order >= 8 && !option.tactical && !forcedDefence) 1 else 0
                    var value = -search(opponent, depth - 1 - reduction, -beta, -alpha, ply + 1,
                        extension - if (depth <= 0) 1 else 0)
                    if (reduction > 0 && value > alpha) value = -search(opponent, depth - 1, -beta, -alpha, ply + 1, extension)
                    value
                }
            } finally { position.unmake() }
            if (score > best) { best = score; bestMove = option.index }
            alpha = maxOf(alpha, score)
            if (alpha >= beta) {
                if (!option.tactical && depth > 0) history[side][option.index] = minOf(2_000, history[side][option.index] + depth * depth)
                break
            }
        }
        if (depth > 0) {
            if (table.size > 80_000) table.clear()
            table[key] = SearchEntry(depth, toTable(best, ply), when {
                best <= alphaStart -> Bound.UPPER
                best >= betaStart -> Bound.LOWER
                else -> Bound.EXACT
            }, bestMove)
        }
        return best
    }

    /** Global tactical scan; quiet branching alone is capped, never forced attacks/blocks. */
    private fun candidates(side: Int, quietLimit: Int): List<Candidate> {
        val opponent = 3 - side
        val wins = position.winningMoves(side)
        if (wins.isNotEmpty()) return wins.map { Candidate(it, Int.MAX_VALUE, true) }
        val blocks = position.winningMoves(opponent)
        if (blocks.isNotEmpty()) return blocks.map { Candidate(it, Int.MAX_VALUE, true) }
        val options = ArrayList<Candidate>()
        for (index in position.cells.indices) {
            if (position.cells[index] != 0) continue
            val own = position.info(side, index)
            val other = position.info(opponent, index)
            val tactical = GomokuThreatPosition.wins(own) > 0 || GomokuThreatPosition.wins(other) > 0 ||
                GomokuThreatPosition.threes(own) > 0 || GomokuThreatPosition.threes(other) > 0
            if (!tactical && position.neighbours[index] == 0) continue
            val attack = GomokuThreatPosition.score(own)
            val defence = GomokuThreatPosition.score(other)
            val centrality = position.size - abs(index % position.size - position.size / 2) - abs(index / position.size - position.size / 2)
            options.add(Candidate(index, attack * 11 / 10 + defence + centrality, tactical))
        }
        options.sortWith(compareByDescending<Candidate> { it.priority }.thenBy { it.index })
        if (quietLimit == 0) return options.filter { it.tactical }
        var quiet = 0
        return options.filter { it.tactical || quiet++ < quietLimit }
    }

    private fun evaluate(side: Int): Int {
        // Evaluate continuations across the whole board; actual forcing wins are proved above.
        val own = IntArray(4)
        val other = IntArray(4)
        var ownTotal = 0
        var otherTotal = 0
        for (index in position.cells.indices) if (position.cells[index] == 0) {
            val a = GomokuThreatPosition.score(position.info(side, index))
            val b = GomokuThreatPosition.score(position.info(3 - side, index))
            ownTotal += minOf(a, 500)
            otherTotal += minOf(b, 500)
            insert(own, a)
            insert(other, b)
        }
        return (own[0] * 2 + own[1] / 2 + own[2] / 4 + own[3] / 8 + ownTotal / 4 -
            other[0] * 2 - other[1] / 2 - other[2] / 4 - other[3] / 8 - otherTotal / 4).coerceIn(-mate / 2, mate / 2)
    }

    private fun insert(top: IntArray, value: Int) {
        for (i in top.indices) if (value > top[i]) {
            for (j in top.lastIndex downTo i + 1) top[j] = top[j - 1]
            top[i] = value
            return
        }
    }

    private fun toTable(value: Int, ply: Int): Int = when {
        value >= mate - 100 -> value + ply
        value <= -mate + 100 -> value - ply
        else -> value
    }
    private fun fromTable(value: Int, ply: Int): Int = when {
        value >= mate - 100 -> value - ply
        value <= -mate + 100 -> value + ply
        else -> value
    }
}
