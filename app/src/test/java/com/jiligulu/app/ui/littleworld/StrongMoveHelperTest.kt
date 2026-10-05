package com.jiligulu.app.ui.littleworld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrongMoveHelperTest {
    @Test fun xiangqiFindsRookCannonAndHorseMatesWithoutChangingTheRootBoard() {
        for (puzzle in XiangqiPuzzles.all) {
            val original = puzzle.position.board.toList()
            val move = requireNotNull(XiangqiStrongMoveHelper.chooseMove(puzzle.position))
            assertTrue(puzzle.title, move in XiangqiEngine.legalMoves(puzzle.position))
            assertEquals(puzzle.title, XiangqiOutcome.RED_WON, XiangqiEngine.play(puzzle.position, move).outcome)
            assertEquals(original, puzzle.position.board)
        }
    }

    @Test fun xiangqiWorksForBlackAndHonorsFlyingGeneralLegality() {
        val board = MutableList(90) { 0 }.apply {
            this[9 * 9 + 4] = 1
            this[4] = -1
            this[7 * 9 + 4] = -5
        }
        val state = XiangqiState(board = board, turnSide = XiangqiSide.BLACK)
        val move = requireNotNull(XiangqiStrongMoveHelper.chooseMove(state))
        assertTrue(move in XiangqiEngine.legalMoves(state))
        assertEquals(XiangqiOutcome.BLACK_WON, XiangqiEngine.play(state, move).outcome)
        assertFalse(XiangqiEngine.isInCheck(XiangqiEngine.play(state, move), XiangqiSide.BLACK))
    }

    @Test fun xiangqiResolvesTheOnlyLegalCheckEvasion() {
        val board = MutableList(90) { 0 }.apply {
            this[9 * 9 + 5] = 1
            this[4] = -1
            this[9] = -5
            this[2 * 9 + 4] = 5
            this[3 * 9 + 3] = 5
        }
        val state = XiangqiState(board = board, turnSide = XiangqiSide.BLACK)
        val forced = XiangqiMove(GridCell(0, 1), GridCell(4, 1))
        assertEquals(listOf(forced), XiangqiEngine.legalMoves(state))
        assertEquals(forced, XiangqiStrongMoveHelper.chooseMove(state))
    }

    @Test fun xiangqiDoesNotTradeARookForAPawnThatIsImmediatelyRecaptured() {
        val board = MutableList(90) { 0 }.apply {
            this[9 * 9 + 4] = 1
            this[3] = -1
            this[4 * 9 + 4] = -5
            this[5 * 9 + 4] = 7
            this[7 * 9 + 4] = 5
        }
        val state = XiangqiState(board = board, turnSide = XiangqiSide.BLACK)
        val move = requireNotNull(XiangqiStrongMoveHelper.chooseMove(state))
        assertTrue(move in XiangqiEngine.legalMoves(state))
        assertNotEquals(XiangqiMove(GridCell(4, 4), GridCell(4, 5)), move)
    }

    @Test fun gomokuTakesAnImmediateWinningGapForEitherCurrentPlayer() {
        for (player in 1..2) {
            val state = gomoku(player, mapOf(
                GridCell(4, 7) to player, GridCell(5, 7) to player,
                GridCell(7, 7) to player, GridCell(8, 7) to player,
            ))
            val before = state.board.toList()
            val move = requireNotNull(GomokuStrongMoveHelper.chooseMove(state))
            assertEquals(GridCell(6, 7), move)
            assertEquals(if (player == 1) GomokuOutcome.HUMAN_WON else GomokuOutcome.CPU_WON,
                GomokuEngine.play(state, move.x, move.y).outcome)
            assertEquals(before, state.board)
        }
    }

    @Test fun gomokuBlocksAUniqueBrokenFourRatherThanChasingItsOwnThree() {
        val state = gomoku(1, mapOf(
            GridCell(2, 4) to 1,
            GridCell(3, 4) to 2, GridCell(4, 4) to 2,
            GridCell(6, 4) to 2, GridCell(7, 4) to 2,
            GridCell(6, 8) to 1, GridCell(7, 8) to 1, GridCell(8, 8) to 1,
        ))
        assertEquals(GridCell(5, 4), GomokuStrongMoveHelper.chooseMove(state))
    }

    @Test fun gomokuCreatesAnOpenFourThatNeitherSingleReplyCanDefend() {
        val state = gomoku(1, mapOf(
            GridCell(5, 7) to 1, GridCell(6, 7) to 1, GridCell(7, 7) to 1,
            GridCell(4, 4) to 2, GridCell(8, 4) to 2,
        ))
        val move = requireNotNull(GomokuStrongMoveHelper.chooseMove(state))
        assertTrue(move == GridCell(4, 7) || move == GridCell(8, 7))
        val after = GomokuEngine.play(state, move.x, move.y)
        val winningPoints = (0 until after.size).flatMap { y ->
            (0 until after.size).map { x -> GridCell(x, y) }
        }.filter { cell ->
            val next = GomokuEngine.play(after.copy(currentPlayer = 1), cell.x, cell.y)
            next.outcome == GomokuOutcome.HUMAN_WON
        }
        assertEquals(2, winningPoints.size)
    }

    @Test fun gomokuEmptyBoardStartsInTheCenterAndFinishedBoardsDoNotMove() {
        assertEquals(GridCell(7, 7), GomokuStrongMoveHelper.chooseMove(GomokuEngine.newGame()))
        assertNull(GomokuStrongMoveHelper.chooseMove(GomokuState(outcome = GomokuOutcome.DRAW)))
        assertNull(XiangqiStrongMoveHelper.chooseMove(XiangqiState(outcome = XiangqiOutcome.RED_WON)))
    }

    @Test fun searchesCooperateWithCancellationAndNeverReturnAPartiallyChosenMove() {
        assertNull(XiangqiStrongMoveHelper.chooseMove(XiangqiEngine.newGame()) { true })
        assertNull(GomokuStrongMoveHelper.chooseMove(GomokuEngine.newGame()) { true })
        var xiangqiChecks = 0
        assertNull(XiangqiStrongMoveHelper.chooseMove(XiangqiEngine.newGame()) { ++xiangqiChecks >= 3 })
        var gomokuChecks = 0
        val state = gomoku(1, mapOf(GridCell(7, 7) to 1, GridCell(8, 8) to 2))
        assertNull(GomokuStrongMoveHelper.chooseMove(state) { ++gomokuChecks >= 3 })
        assertTrue(xiangqiChecks >= 3)
        assertTrue(gomokuChecks >= 3)
    }

    private fun gomoku(player: Int, pieces: Map<GridCell, Int>): GomokuState =
        GomokuState(currentPlayer = player, board = MutableList(15 * 15) { 0 }.apply {
            for ((cell, piece) in pieces) this[cell.y * 15 + cell.x] = piece
        })
}
