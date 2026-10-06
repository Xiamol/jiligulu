package com.jiligulu.app.ui.littleworld

import org.junit.Assert.*
import org.junit.Test

class XiangqiAssistStrengthTest {
    @Test fun findingALongMateInQuiescenceDoesNotHideAShorterMainTreeMate() {
        val red = XiangqiState(board = MutableList(90) { 0 }.apply {
            this[85] = 1; this[4] = -1; this[49] = 7
            this[27] = 5; this[26] = 5; this[28] = -7
        })
        for (state in listOf(red, red.copy(board = red.board.reversed().map { -it }, turnSide = XiangqiSide.BLACK))) {
            val assist = XiangqiStrongMoveHelper.analyzeForNodes(state, 100_000, maxDepth = 6)
            assertTrue("A long q-search mate must not terminate depth one: $assist", assist.completedDepth >= 2)
            assertEquals(999_997, assist.score)
            assertTrue(forcesMateByChecks(XiangqiEngine.play(state, requireNotNull(assist.move)), state.turnSide, 2))
        }
    }

    @Test fun assistProvesFivePlyQuietCheckingMateThatTheNormalHorizonMissesForEitherSide() {
        val red = fivePlyCheckingMate()
        for (state in listOf(red, red.copy(board = red.board.reversed().map { -it }, turnSide = XiangqiSide.BLACK))) {
            val normal = XiangqiStrongMoveHelper.analyzeForNodes(state, 100_000,
                profile = XiangqiSearchProfile.NORMAL, maxDepth = 1)
            val assist = XiangqiStrongMoveHelper.analyzeForNodes(state, 100_000,
                profile = XiangqiSearchProfile.ASSIST, maxDepth = 1)
            assertEquals(1, normal.completedDepth)
            assertEquals(1, assist.completedDepth)
            assertTrue("The standard horizon does not prove mate: $normal", requireNotNull(normal.score) < 900_000)
            assertTrue("The extended checking line must be proved, not just preferred: $assist", requireNotNull(assist.score) > 900_000)
            val move = requireNotNull(assist.move)
            assertTrue(move in XiangqiEngine.legalMoves(state))
            assertTrue(forcesMateByChecks(XiangqiEngine.play(state, move), state.turnSide, 4))
            assertFalse(forcesMateByChecks(XiangqiEngine.play(state, requireNotNull(normal.move)), state.turnSide, 4))
            assertTrue(assist.quietChecksSearched > 0)
        }
    }

    @Test fun nodeLimitedStrengthReportsAreRepeatableAndKeepThePositionImmutable() {
        val state = XiangqiEngine.newGame()
        val before = state.board.toList()
        for (profile in XiangqiSearchProfile.entries) {
            val first = XiangqiStrongMoveHelper.analyzeForNodes(state, 2_048, profile = profile)
            val repeated = XiangqiStrongMoveHelper.analyzeForNodes(state, 2_048, profile = profile)
            assertEquals(first.move, repeated.move)
            assertEquals(first.score, repeated.score)
            assertEquals(first.completedDepth, repeated.completedDepth)
            assertEquals(first.nodes, repeated.nodes)
            assertEquals(first.tableHits, repeated.tableHits)
            assertEquals(first.quietChecksSearched, repeated.quietChecksSearched)
            assertEquals(XiangqiSearchStop.NODE_LIMIT, first.stopReason)
            assertEquals(profile, first.profile)
            assertTrue(first.move in XiangqiEngine.legalMoves(state))
            assertTrue(first.nodes <= 2_048 + 16)
        }
        assertEquals(before, state.board)
    }

    @Test fun nodeDiagnosticsStillCancelAndNeverPublishAStaleMove() {
        var polls = 0
        val report = XiangqiStrongMoveHelper.analyzeForNodes(XiangqiEngine.newGame(), 100_000) { ++polls >= 3 }
        assertTrue(report.cancelled)
        assertEquals(XiangqiSearchStop.CANCELLED, report.stopReason)
        assertNull(report.move)
        assertTrue(report.nodes < 100)
    }

    private fun forcesMateByChecks(state: XiangqiState, attacker: XiangqiSide, remaining: Int): Boolean {
        val winner = if (attacker == XiangqiSide.RED) XiangqiOutcome.RED_WON else XiangqiOutcome.BLACK_WON
        if (state.outcome != XiangqiOutcome.PLAYING) return state.outcome == winner
        if (remaining == 0) return false
        val moves = XiangqiEngine.legalMoves(state)
        if (state.turnSide == attacker) return moves.any { move ->
            val next = XiangqiEngine.play(state, move)
            (next.outcome == winner || XiangqiEngine.isInCheck(next, next.turnSide)) &&
                forcesMateByChecks(next, attacker, remaining - 1)
        }
        return moves.isNotEmpty() && moves.all { move ->
            forcesMateByChecks(XiangqiEngine.play(state, move), attacker, remaining - 1)
        }
    }

    private fun fivePlyCheckingMate() = XiangqiState(board = MutableList(90) { 0 }.apply {
        this[9 * 9 + 4] = 1
        this[5 * 9 + 4] = 7
        this[2 * 9 + 7] = 5
        this[2 * 9 + 8] = 5
        this[4] = -1
        this[5] = -5
        this[4 * 9 + 1] = -6
        this[6 * 9 + 5] = -7
    })
}
