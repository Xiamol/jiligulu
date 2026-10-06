package com.jiligulu.app.ui.littleworld

import org.junit.Assert.*
import org.junit.Test

class XiangqiSearchTest {
    @Test fun cpuAndHelperFindAForcedThreePlyWinInsteadOfTakingTheFreePawnForEitherSide() {
        val red = matingNet()
        for (state in listOf(red, red.copy(board = red.board.reversed().map { -it }, turnSide = XiangqiSide.BLACK))) {
            val original = state.board.toList()
            val moves = listOf(
                "CPU700" to requireNotNull(XiangqiEngine.chooseCpuMove(state, timeBudgetMillis = 700)),
                "HELPER700" to requireNotNull(XiangqiStrongMoveHelper.chooseMove(state, timeBudgetMillis = 700)),
            )
            for ((label, move) in moves) {
                assertTrue(move in XiangqiEngine.legalMoves(state))
                assertTrue("$label ${state.turnSide}: every opponent reply must still allow mate, $move", forcesWinInThree(state, move))
            }
            assertEquals(original, state.board)
        }
        // This attractive pawn capture was selected by the previous fixed two-ply CPU.
        assertFalse(forcesWinInThree(red, XiangqiMove(GridCell(0, 3), GridCell(1, 3))))
    }

    @Test fun iterativeSearchCompletesMultipleDepthsAndActuallyUsesTheTranspositionTable() {
        val state = XiangqiEngine.newGame()
        val report = XiangqiStrongMoveHelper.analyze(state, timeBudgetMillis = 3_000, maxDepth = 3)
        assertEquals(3, report.completedDepth)
        assertTrue(report.tableHits > 0)
        assertTrue(report.nodes > 100)
        assertTrue(report.move in XiangqiEngine.legalMoves(state))
        assertFalse(report.cancelled)
    }

    @Test fun cancellationDiscardsAStaleMoveAndOrdinaryCpuAcceptsTheSameCancellationContract() {
        var polls = 0
        val report = XiangqiStrongMoveHelper.analyze(XiangqiEngine.newGame(), timeBudgetMillis = 3_000) { ++polls >= 3 }
        assertNull(report.move)
        assertTrue(report.cancelled)
        assertTrue(report.nodes < 100)
        assertNull(XiangqiEngine.chooseCpuMove(XiangqiEngine.newGame()) { true })
    }

    @Test fun shortTimeBudgetKeepsALegalCompletedResultWithoutMutatingTheBoard() {
        val state = XiangqiEngine.newGame()
        val report = XiangqiStrongMoveHelper.analyze(state, timeBudgetMillis = 50)
        assertTrue(report.move in XiangqiEngine.legalMoves(state))
        assertFalse(report.cancelled)
        assertTrue(report.budgetExpired)
        assertTrue(report.nodes <= 300_016)
        // A generous ceiling catches an ignored deadline without depending on millisecond scheduling.
        assertTrue("elapsed=${report.elapsedMillis}", report.elapsedMillis < 2_000)
        assertEquals(XiangqiEngine.newGame(), state)
    }

    @Test fun generatedMoveFastPathAgreesWithPublicRulesOnOpeningMoves() {
        val state = XiangqiEngine.newGame()
        for (move in XiangqiEngine.legalMoves(state)) {
            assertEquals(XiangqiEngine.play(state, move), XiangqiEngine.applyGeneratedMove(state, move))
        }
    }

    private fun forcesWinInThree(state: XiangqiState, move: XiangqiMove): Boolean {
        val winner = if (state.turnSide == XiangqiSide.RED) XiangqiOutcome.RED_WON else XiangqiOutcome.BLACK_WON
        val next = XiangqiEngine.play(state, move)
        if (next.outcome == winner) return true
        val replies = XiangqiEngine.legalMoves(next)
        return replies.isNotEmpty() && replies.all { reply ->
            val response = XiangqiEngine.play(next, reply)
            XiangqiEngine.legalMoves(response).any { XiangqiEngine.play(response, it).outcome == winner }
        }
    }

    private fun matingNet(): XiangqiState = XiangqiState(board = MutableList(90) { 0 }.apply {
        this[85] = 1
        this[4] = -1
        this[5 * 9 + 4] = 7
        this[3 * 9] = 5
        this[2 * 9 + 8] = 5
        this[3 * 9 + 1] = -7
    })
}
