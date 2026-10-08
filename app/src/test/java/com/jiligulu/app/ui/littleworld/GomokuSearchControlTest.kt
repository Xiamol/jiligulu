package com.jiligulu.app.ui.littleworld

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GomokuSearchControlTest {
    @Test fun expiredThreatSliceStopsBetweenGlobalPollingPoints() {
        var clockMillis = 0L
        val budget = GomokuSearchBudget({ false }, 1_000) { clockMillis * 1_000_000L }
        budget.threatSlice(100)
        repeat(7) { budget.visit(false) }

        clockMillis = 101
        budget.visit(false)
        expectThrows<ThreatSearchStopped> { budget.visit(true) }

        // A completed proof slice must not cancel the main search or a later slice.
        budget.visit(false)
        budget.threatSlice(400)
        budget.visit(true)
        clockMillis = 400
        expectThrows<ThreatSearchStopped> { budget.visit(true) }
    }

    @Test fun globalDeadlineAndCancellationRemainDifferentStopReasons() {
        var clockMillis = 0L
        val budget = GomokuSearchBudget({ false }, 1_000) { clockMillis * 1_000_000L }
        budget.visit(false)
        clockMillis = 1_000
        var expired: SearchStopped? = null
        repeat(16) {
            if (expired == null) {
                try { budget.visit(false) } catch (stopped: SearchStopped) { expired = stopped }
            }
        }
        assertTrue("The global deadline must be checked within one polling interval", expired != null)
        assertFalse(requireNotNull(expired).cancelled)

        val cancelled = GomokuSearchBudget({ true }, 1_000) { 0L }
        assertTrue(expectThrows<SearchStopped> { cancelled.visit(false) }.cancelled)
    }

    @Test fun oneShotCancellationDuringConstructionRemainsCancelledAfterTheCallbackResets() {
        val empty = GomokuEngine.newGame()
        var checks = 0
        val budget = GomokuSearchBudget({ ++checks == 2 }, 1_000) { 0L }
        assertFalse(budget.finishCancelled())
        val stopped = expectThrows<SearchStopped> { GomokuSearch(empty, budget) }
        assertTrue(stopped.cancelled)
        assertTrue("Cancellation must remain sticky when the next callback returns false", budget.finishCancelled())
        assertEquals(3, checks)

        var publicChecks = 0
        val reports = ArrayList<GomokuSearchDiagnostics>()
        val move = GomokuStrongMoveHelper.chooseMove(empty, onDiagnostics = { reports.add(it) }) { ++publicChecks == 2 }
        assertNull(move)
        assertTrue(publicChecks >= 3)
        assertEquals(1, reports.size)
        assertEquals(GomokuSearchSource.CANCELLED, reports.single().source)
        assertNull(reports.single().move)
        assertTrue(reports.single().phases.any { it.phase == GomokuSearchPhase.INITIALIZATION && !it.completed })
    }

    @Test fun interruptedScreeningKeepsProvenLossesAndLeavesUntestedMovesAvailable() {
        val safety = GomokuRootMoveSafety(intArrayOf(10, 20, 30, 40))
        val examined = ArrayList<Int>()
        expectThrows<ThreatSearchStopped> {
            safety.screen(intArrayOf(10, 20, 30, 40)) { move ->
                examined.add(move)
                when (move) {
                    10 -> true
                    20 -> throw ThreatSearchStopped()
                    else -> error("Screening must stop when the proof slice expires")
                }
            }
        }

        assertEquals(listOf(10, 20), examined)
        assertEquals(setOf(10), safety.knownLosses)
        assertArrayEquals(intArrayOf(20, 30, 40), safety.available(intArrayOf(10, 20, 30, 40)))
        assertEquals(20, safety.fallback(10, intArrayOf(10, 20, 30, 40)))
        assertEquals(30, safety.fallback(30, intArrayOf(10, 20, 30, 40)))
        assertFalse(safety.allKnownLosing)

        safety.screen(intArrayOf(20, 30)) { it == 30 }
        assertEquals(setOf(10, 30), safety.knownLosses)
        assertArrayEquals(intArrayOf(20, 40), safety.available(intArrayOf(10, 20, 30, 40)))
        assertEquals(20, safety.fallback(30, intArrayOf(10, 20, 30, 40)))
    }

    @Test fun exhaustingTheRankedCandidatesDoesNotMakeUntestedLegalMovesKnownLosses() {
        val safety = GomokuRootMoveSafety(intArrayOf(10, 20, 30, 40))
        safety.screen(intArrayOf(10, 20, 30)) { true }

        assertEquals(setOf(10, 20, 30), safety.knownLosses)
        assertTrue(safety.available(intArrayOf(10, 20, 30)).isEmpty())
        assertFalse(safety.allKnownLosing)
        assertEquals("An untested legal move is preferable to a proven losing fallback", 40,
            safety.fallback(10, intArrayOf(10, 20, 30)))

        safety.screen(intArrayOf(40)) { true }
        assertTrue(safety.allKnownLosing)
    }

    @Test fun realCounterFourLossProofSurvivesPhaseAndGlobalDeadlineStops() {
        val state = doubleThreeCounterFour()
        val before = state.board.toList()
        var activeSearch: GomokuSearch? = null
        var activeBudget: GomokuSearchBudget? = null
        var clockReads = 0
        val budget = GomokuSearchBudget({ false }, 1_000) {
            clockReads++
            when {
                // Keep a broken proof/screen integration from making this fake-clock test hang.
                clockReads >= 5_000 -> 1_000_000_000L
                activeSearch?.knownLosses.isNullOrEmpty() -> 0L
                activeBudget?.phases?.any { it.phase == GomokuSearchPhase.OPPONENT_VCF && !it.completed } == true ->
                    1_000_000_000L
                else -> 450_000_000L
            }
        }
        activeBudget = budget
        val search = GomokuSearch(state, budget)
        activeSearch = search
        val move = requireNotNull(search.choose())

        assertTrue("The real remote counter-four must disprove the tempting double three",
            GridCell(3, 3) in search.knownLosses)
        assertTrue(search.knownLosses.isNotEmpty())
        assertTrue(move.x in 0 until state.size && move.y in 0 until state.size)
        assertEquals(0, state.cellAt(move.x, move.y))
        assertFalse("A deadline fallback must never revive a completed loss proof", move in search.knownLosses)
        assertEquals(0, search.completedDepth)
        assertTrue(budget.phases.any { it.phase == GomokuSearchPhase.OPPONENT_VCF && !it.completed })
        assertTrue("The proof event, not the test's emergency cutoff, must advance the clock", clockReads < 5_000)
        assertEquals(before, state.board)
    }

    @Test fun nearlyFullStandardBoardIsSolvedAsADrawWithoutStandPatOrExtension() {
        val state = twoEmptyDraw()
        val before = state.board.toList()
        assertEquals(112, state.board.count { it == 1 })
        assertEquals(111, state.board.count { it == 2 })
        assertEquals(2, state.board.count { it == 0 })

        // These are all the legal continuations, so the exact score is known independently.
        for ((move, reply) in listOf(GridCell(3, 0) to GridCell(4, 0), GridCell(4, 0) to GridCell(3, 0))) {
            val after = GomokuEngine.play(state, move.x, move.y)
            assertEquals(GomokuOutcome.PLAYING, after.outcome)
            assertEquals(1, after.currentPlayer)
            assertEquals(GomokuOutcome.DRAW, GomokuEngine.play(after, reply.x, reply.y).outcome)
        }

        for ((alpha, beta) in listOf(-10_000_000 to 10_000_000, -1 to 1, -100_000 to -1, -1 to 50_000)) {
            val search = newSearch(state)
            assertEquals("A pass score must not override a forced draw for window [$alpha, $beta]", 0,
                search.scoreAtDepth(depth = 0, alpha = alpha, beta = beta, extension = 0))
            assertEquals(before, state.board)
        }
    }

    @Test fun legalQuietTranspositionMoveSurvivesCandidateTrimming() {
        val occupied = 7 * 15 + 7
        val state = GomokuState(currentPlayer = 2, board = MutableList(15 * 15) { 0 }.apply { this[occupied] = 1 })
        val search = newSearch(state)
        val preferred = 9 * 15 + 9
        val ordinary = search.candidateIndices(side = 2, quietLimit = 1)
        assertFalse("The fixture must exercise a quiet move normally trimmed away", preferred in ordinary)

        val retained = search.candidateIndices(side = 2, quietLimit = 1, preferred = preferred)
        assertTrue(preferred in retained)
        assertEquals(retained.size, retained.toSet().size)
        assertTrue(retained.all { it in state.board.indices && state.board[it] == 0 })
        assertFalse(occupied in search.candidateIndices(side = 2, quietLimit = 1, preferred = occupied))
        assertTrue(search.candidateIndices(side = 2, quietLimit = 1, preferred = state.board.size)
            .all { it in state.board.indices && state.board[it] == 0 })
    }

    @Test fun optionalDiagnosticsDescribeFastMovesAndPreserveCancellationCallForms() {
        val empty = GomokuEngine.newGame()
        assertEquals(GridCell(7, 7), GomokuStrongMoveHelper.chooseMove(empty))

        val reports = ArrayList<GomokuSearchDiagnostics>()
        val center = GomokuStrongMoveHelper.chooseMove(empty, onDiagnostics = { reports.add(it) })
        assertEquals(1, reports.size)
        assertEquals(GomokuSearchSource.CENTER, reports.single().source)
        assertEquals(center, reports.single().move)
        assertEquals(GridCell(7, 7), center)

        reports.clear()
        val immediate = GomokuStrongMoveHelper.chooseMove(immediateWin(), onDiagnostics = { reports.add(it) })
        assertEquals(1, reports.size)
        assertEquals(GomokuSearchSource.IMMEDIATE_WIN, reports.single().source)
        assertEquals(GridCell(5, 3), immediate)
        assertEquals(immediate, reports.single().move)

        // This exact positional form was supported before diagnostics were introduced.
        assertNull(GomokuStrongMoveHelper.chooseMove(empty, 1_000, { true }))
        assertNull(GomokuStrongMoveHelper.chooseMove(empty) { true })
        assertNull(GomokuStrongMoveHelper.chooseMove(empty, shouldCancel = { true }))
        reports.clear()
        val cancelled = GomokuStrongMoveHelper.chooseMove(empty, onDiagnostics = { reports.add(it) }) { true }
        assertNull(cancelled)
        assertEquals(1, reports.size)
        assertEquals(GomokuSearchSource.CANCELLED, reports.single().source)
        assertNull(reports.single().move)
    }

    @Test fun recordedQuietOpeningReportsItsMainPhaseAndReturnsALegalMove() {
        // Actual round1-ply3.json: black H8/I7, white I8, last move I7.
        val state = GomokuState(size = 15, currentPlayer = 2, lastMove = GridCell(8, 6),
            board = MutableList(15 * 15) { 0 }.apply {
            this[7 * 15 + 7] = 1
            this[6 * 15 + 8] = 1
            this[7 * 15 + 8] = 2
        })
        val before = state.board.toList()
        val reports = ArrayList<GomokuSearchDiagnostics>()
        val move = requireNotNull(GomokuStrongMoveHelper.chooseMove(state, timeBudgetMillis = 250,
            onDiagnostics = { reports.add(it) }))

        assertEquals(0, state.cellAt(move.x, move.y))
        assertEquals(before, state.board)
        assertEquals(1, reports.size)
        val report = reports.single()
        assertEquals(move, report.move)
        assertTrue(report.completedDepth >= 0)
        assertTrue(report.elapsedMillis >= 0)
        assertTrue(report.phases.any { it.phase == GomokuSearchPhase.MAIN_SEARCH && it.visits > 0 })
        assertTrue(report.phases.all { it.elapsedMillis >= 0 && it.visits >= 0 })
        assertTrue(report.knownLosses.isEmpty())
        if (report.completedDepth > 0) {
            assertEquals(GomokuSearchSource.COMPLETED_SEARCH, report.source)
            assertTrue(report.rootScore != null)
        } else {
            assertEquals(GomokuSearchSource.STATIC_FALLBACK, report.source)
            assertNull(report.rootScore)
        }
    }

    @Test fun throwingDiagnosticsListenerDoesNotDiscardTheSelectedMove() {
        var callbacks = 0
        val move = GomokuStrongMoveHelper.chooseMove(GomokuEngine.newGame(), onDiagnostics = {
            callbacks++
            throw IllegalStateException("Deliberately failing QA consumer")
        })
        assertEquals(1, callbacks)
        assertEquals(GridCell(7, 7), move)
    }

    @Test fun capturedNineteenMoveBlackGameTakesTheUniqueM9Win() {
        // Actual board after manual-H9-18.json: nine stones per side, last move H9.
        val state = GomokuState(size = 15, currentPlayer = 1, lastMove = GridCell(7, 8),
            board = capturedBoard(
                "000000000000000",
                "000000000000000",
                "000000000000000",
                "000010000000000",
                "000002000000000",
                "000002220000000",
                "000000121200000",
                "000000012100000",
                "000000221111000",
                "000000000000000",
                "000000000000000",
                "000000000000000",
                "000000000000000",
                "000000000000000",
                "000000000000000",
            ))
        assertCapturedWin(state, GridCell(12, 8), GomokuOutcome.HUMAN_WON)
    }

    @Test fun capturedFortySixMoveWhiteGameTakesTheUniqueH2Win() {
        // Actual board after manual-C7-45.json: 23 black/22 white stones, last move C7.
        val state = GomokuState(size = 15, currentPlayer = 2, lastMove = GridCell(2, 6),
            board = capturedBoard(
                "000000000000000",
                "000000000000000",
                "000000200000000",
                "000002010022120",
                "001020201211100",
                "012222120111200",
                "001021211210000",
                "000212112002000",
                "001010200000000",
                "000000010000000",
                "000000000000000",
                "000000000000000",
                "000000000000000",
                "000000000000000",
                "000000000000000",
            ))
        assertCapturedWin(state, GridCell(7, 1), GomokuOutcome.CPU_WON)
    }

    private fun assertCapturedWin(state: GomokuState, expected: GridCell, outcome: GomokuOutcome) {
        val before = state.copy(board = state.board.toList())
        val reports = ArrayList<GomokuSearchDiagnostics>()
        val move = requireNotNull(GomokuStrongMoveHelper.chooseMove(state, onDiagnostics = { reports.add(it) }))
        assertEquals(expected, move)
        assertEquals(outcome, GomokuEngine.play(state, move.x, move.y).outcome)
        assertEquals(1, reports.size)
        assertEquals(GomokuSearchSource.IMMEDIATE_WIN, reports.single().source)
        assertEquals(move, reports.single().move)
        assertEquals(before, state)
    }

    private fun capturedBoard(vararg rows: String): List<Int> {
        require(rows.size == 15 && rows.all { it.length == 15 && it.all { cell -> cell in '0'..'2' } })
        return rows.flatMap { row -> row.map { it - '0' } }
    }

    private fun newSearch(state: GomokuState): GomokuSearch =
        GomokuSearch(state, GomokuSearchBudget({ false }, 5_000) { 0L })

    private fun immediateWin(): GomokuState = GomokuState(currentPlayer = 1,
        board = MutableList(15 * 15) { 0 }.apply {
            for (x in listOf(3, 4, 6, 7)) this[3 * 15 + x] = 1
            for (x in 2..5) this[10 * 15 + x] = 2
        })

    private fun doubleThreeCounterFour(): GomokuState = GomokuState(currentPlayer = 1,
        board = MutableList(15 * 15) { 0 }.apply {
            for (cell in listOf(GridCell(2, 3), GridCell(4, 3), GridCell(3, 2), GridCell(3, 4),
                GridCell(3, 9), GridCell(7, 8), GridCell(14, 14))) this[cell.y * 15 + cell.x] = 1
            for (cell in listOf(GridCell(4, 9), GridCell(5, 9), GridCell(6, 9), GridCell(7, 10),
                GridCell(7, 11), GridCell(9, 10), GridCell(10, 9))) this[cell.y * 15 + cell.x] = 2
        })

    private fun twoEmptyDraw(): GomokuState = GomokuState(currentPlayer = 2,
        board = MutableList(15 * 15) { index -> 1 + ((index % 15 / 2 + index / 15) % 2) }.apply {
            this[3] = 0
            this[4] = 0
            this[0] = 2
            this[1] = 2
            this[2] = 2
            this[14 * 15 + 14] = 1
            this[12 * 15 + 10] = 1
        })

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            assertTrue("Expected ${T::class.java.simpleName}, got $failure", failure is T)
            return failure as T
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
