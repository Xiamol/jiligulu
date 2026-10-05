package com.jiligulu.app.ui.littleworld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class XiangqiEngineTest {
    @Test fun initialPositionHasStandardPiecesAndFortyFourLegalOpeningMoves() {
        val state = XiangqiEngine.newGame()
        assertEquals(90, state.board.size)
        assertEquals(16, state.board.count { it > 0 })
        assertEquals(16, state.board.count { it < 0 })
        assertEquals(listOf(-5, -4, -3, -2, -1, -2, -3, -4, -5), state.board.take(9))
        assertEquals(listOf(5, 4, 3, 2, 1, 2, 3, 4, 5), state.board.takeLast(9))
        assertEquals(-6, state.pieceAt(1, 2))
        assertEquals(6, state.pieceAt(7, 7))
        assertEquals(-7, state.pieceAt(8, 3))
        assertEquals(7, state.pieceAt(0, 6))
        assertEquals(XiangqiSide.RED, state.turnSide)
        assertEquals(XiangqiSide.BLACK, state.turnSide.opponent)
        assertEquals(44, XiangqiEngine.legalMoves(state).size)
        assertFalse(XiangqiEngine.isInCheck(state, XiangqiSide.RED))
        assertFalse(XiangqiEngine.isInCheck(state, XiangqiSide.BLACK))
    }

    @Test fun acceptedMovesAlternateSidesAndPreserveTheOriginalBoard() {
        val initial = XiangqiEngine.newGame()
        val move = move(0, 6, 0, 5)
        val next = XiangqiEngine.play(initial, move)
        assertEquals(0, next.pieceAt(0, 6))
        assertEquals(7, next.pieceAt(0, 5))
        assertEquals(XiangqiSide.BLACK, next.turnSide)
        assertEquals(1, next.ply)
        assertEquals(move, next.lastMove)
        assertEquals(XiangqiOutcome.PLAYING, next.outcome)
        assertEquals(7, initial.pieceAt(0, 6))
        assertEquals(0, initial.pieceAt(0, 5))
        val black = XiangqiEngine.play(next, move(8, 3, 8, 4))
        assertEquals(XiangqiSide.RED, black.turnSide)
        assertEquals(2, black.ply)
    }

    @Test fun wrongSideOccupiedEmptyAndOutsideMovesLeaveEveryFieldUntouched() {
        val state = XiangqiEngine.newGame()
        for (invalid in listOf(
            move(0, 3, 0, 4), move(4, 4, 4, 3), move(0, 9, 1, 9),
            move(0, 6, -1, 6), move(8, 6, 9, 6), move(0, 6, 0, 10),
            move(-1, 0, 0, 0), move(0, 6, 0, 6),
        )) assertSame(state, XiangqiEngine.play(state, invalid))
        assertTrue(XiangqiEngine.legalMoves(state, GridCell(9, 0)).isEmpty())
        assertTrue(XiangqiEngine.legalMoves(state, GridCell(0, 3)).isEmpty())
        assertTrue(XiangqiEngine.legalMoves(state, GridCell(4, 4)).isEmpty())
    }

    @Test fun generalsAndAdvisorsStayInTheirOwnPalaces() {
        val red = position(mapOf(GridCell(3, 8) to 1), redGeneral = GridCell(3, 8), blackGeneral = GridCell(5, 0))
        assertEquals(setOf(GridCell(3, 7), GridCell(3, 9), GridCell(4, 8)), targets(red, 3, 8))
        val black = position(
            mapOf(GridCell(5, 1) to -1), turn = XiangqiSide.BLACK,
            redGeneral = GridCell(3, 9), blackGeneral = GridCell(5, 1),
        )
        assertEquals(setOf(GridCell(5, 0), GridCell(5, 2), GridCell(4, 1)), targets(black, 5, 1))
        val advisor = position(mapOf(GridCell(3, 9) to 2))
        assertEquals(setOf(GridCell(4, 8)), targets(advisor, 3, 9))
        val blackAdvisor = position(mapOf(GridCell(5, 0) to -2), turn = XiangqiSide.BLACK)
        assertEquals(setOf(GridCell(4, 1)), targets(blackAdvisor, 5, 0))
    }

    @Test fun elephantsCannotCrossTheRiverOrJumpAnOccupiedEye() {
        val open = position(mapOf(GridCell(4, 7) to 3))
        assertEquals(setOf(GridCell(2, 5), GridCell(6, 5), GridCell(2, 9), GridCell(6, 9)), targets(open, 4, 7))
        val blocked = position(mapOf(GridCell(4, 7) to 3, GridCell(3, 6) to -7))
        assertFalse(GridCell(2, 5) in targets(blocked, 4, 7))
        assertTrue(GridCell(6, 5) in targets(blocked, 4, 7))
        val redRiver = position(mapOf(GridCell(4, 5) to 3))
        assertEquals(setOf(GridCell(2, 7), GridCell(6, 7)), targets(redRiver, 4, 5))
        val blackRiver = position(mapOf(GridCell(4, 4) to -3), turn = XiangqiSide.BLACK)
        assertEquals(setOf(GridCell(2, 2), GridCell(6, 2)), targets(blackRiver, 4, 4))
    }

    @Test fun horseLegBlockingRemovesOnlyTheAffectedTwoDestinations() {
        val open = position(mapOf(GridCell(4, 5) to 4))
        assertEquals(8, targets(open, 4, 5).size)
        val blocked = position(mapOf(GridCell(4, 5) to 4, GridCell(3, 5) to 7))
        assertEquals(targets(open, 4, 5) - setOf(GridCell(2, 4), GridCell(2, 6)), targets(blocked, 4, 5))
        val vertical = position(mapOf(GridCell(4, 5) to 4, GridCell(4, 4) to -7))
        assertFalse(GridCell(3, 3) in targets(vertical, 4, 5))
        assertFalse(GridCell(5, 3) in targets(vertical, 4, 5))
        assertTrue(GridCell(6, 4) in targets(vertical, 4, 5))
    }

    @Test fun rookCannotCrossEitherSideAndStopsAfterCapturing() {
        val state = position(mapOf(
            GridCell(4, 5) to 5, GridCell(4, 3) to 7, GridCell(4, 7) to -7,
            GridCell(5, 5) to 7, GridCell(2, 5) to -4,
        ))
        val destinations = targets(state, 4, 5)
        assertTrue(GridCell(4, 4) in destinations)
        assertFalse(GridCell(4, 3) in destinations)
        assertFalse(GridCell(4, 2) in destinations)
        assertTrue(GridCell(4, 6) in destinations)
        assertTrue(GridCell(4, 7) in destinations)
        assertFalse(GridCell(4, 8) in destinations)
        assertFalse(GridCell(5, 5) in destinations)
        assertFalse(GridCell(6, 5) in destinations)
        assertTrue(GridCell(2, 5) in destinations)
        assertFalse(GridCell(1, 5) in destinations)
        val captured = XiangqiEngine.play(state, move(4, 5, 4, 7))
        assertEquals(5, captured.pieceAt(4, 7))
        assertEquals(0, captured.pieceAt(4, 5))
        assertEquals(-7, state.pieceAt(4, 7))
    }

    @Test fun cannonNeedsExactlyOneScreenToCaptureAndCannotLandBeyondIt() {
        val clear = position(mapOf(GridCell(4, 5) to 6, GridCell(4, 1) to -5))
        assertTrue(GridCell(4, 2) in targets(clear, 4, 5))
        assertFalse(GridCell(4, 1) in targets(clear, 4, 5))
        val oneScreen = position(mapOf(GridCell(4, 5) to 6, GridCell(4, 3) to 7, GridCell(4, 1) to -5))
        assertTrue(GridCell(4, 4) in targets(oneScreen, 4, 5))
        assertFalse(GridCell(4, 3) in targets(oneScreen, 4, 5))
        assertFalse(GridCell(4, 2) in targets(oneScreen, 4, 5))
        assertTrue(GridCell(4, 1) in targets(oneScreen, 4, 5))
        val twoScreens = position(mapOf(
            GridCell(4, 5) to 6, GridCell(4, 3) to -7, GridCell(4, 2) to 7, GridCell(4, 1) to -5,
        ))
        assertFalse(GridCell(4, 1) in targets(twoScreens, 4, 5))
        assertTrue(GridCell(4, 2) !in targets(twoScreens, 4, 5))
        val friendly = position(mapOf(GridCell(4, 5) to 6, GridCell(4, 3) to -7, GridCell(4, 1) to 5))
        assertFalse(GridCell(4, 1) in targets(friendly, 4, 5))
    }

    @Test fun pawnsOnlyAdvanceBeforeTheRiverThenCanMoveSidewaysButNeverBackwards() {
        val redBefore = position(mapOf(GridCell(4, 6) to 7))
        assertEquals(setOf(GridCell(4, 5)), targets(redBefore, 4, 6))
        val redAfter = position(mapOf(GridCell(4, 4) to 7))
        assertEquals(setOf(GridCell(4, 3), GridCell(3, 4), GridCell(5, 4)), targets(redAfter, 4, 4))
        val blackBefore = position(mapOf(GridCell(4, 3) to -7), turn = XiangqiSide.BLACK)
        assertEquals(setOf(GridCell(4, 4)), targets(blackBefore, 4, 3))
        val blackAfter = position(mapOf(GridCell(4, 5) to -7), turn = XiangqiSide.BLACK)
        assertEquals(setOf(GridCell(4, 6), GridCell(3, 5), GridCell(5, 5)), targets(blackAfter, 4, 5))
        val edge = position(mapOf(GridCell(0, 0) to 7))
        assertEquals(setOf(GridCell(1, 0)), targets(edge, 0, 0))
    }

    @Test fun facingGeneralsCheckBothSidesAndCanCaptureAlongTheOpenFile() {
        val state = position(emptyMap(), blackGeneral = GridCell(4, 0))
        assertTrue(XiangqiEngine.isInCheck(state, XiangqiSide.RED))
        assertTrue(XiangqiEngine.isInCheck(state, XiangqiSide.BLACK))
        val fly = move(4, 9, 4, 0)
        assertTrue(fly in XiangqiEngine.legalMoves(state))
        val won = XiangqiEngine.play(state, fly)
        assertEquals(XiangqiOutcome.RED_WON, won.outcome)
        assertEquals(1, won.pieceAt(4, 0))
        assertEquals(0, won.pieceAt(4, 9))
        assertEquals(XiangqiSide.BLACK, won.turnSide)
    }

    @Test fun movingAFileBlockerCannotExposeFacingGenerals() {
        val state = position(mapOf(GridCell(4, 4) to 7), blackGeneral = GridCell(4, 0))
        assertFalse(XiangqiEngine.isInCheck(state, XiangqiSide.RED))
        assertFalse(XiangqiEngine.isInCheck(state, XiangqiSide.BLACK))
        assertFalse(move(4, 4, 3, 4) in XiangqiEngine.legalMoves(state))
        assertSame(state, XiangqiEngine.play(state, move(4, 4, 5, 4)))
        assertTrue(move(4, 4, 4, 3) in XiangqiEngine.legalMoves(state))
    }

    @Test fun checkDetectionHonorsHorseLegsAndCannonScreens() {
        val horse = position(mapOf(GridCell(5, 7) to -4))
        assertTrue(XiangqiEngine.isInCheck(horse, XiangqiSide.RED))
        val blockedHorse = position(mapOf(GridCell(5, 7) to -4, GridCell(5, 8) to 7))
        assertFalse(XiangqiEngine.isInCheck(blockedHorse, XiangqiSide.RED))
        val cannon = position(mapOf(GridCell(4, 1) to -6))
        assertFalse(XiangqiEngine.isInCheck(cannon, XiangqiSide.RED))
        val screened = position(mapOf(GridCell(4, 1) to -6, GridCell(4, 5) to 7))
        assertTrue(XiangqiEngine.isInCheck(screened, XiangqiSide.RED))
        val doubleScreened = position(mapOf(GridCell(4, 1) to -6, GridCell(4, 5) to 7, GridCell(4, 7) to -7))
        assertFalse(XiangqiEngine.isInCheck(doubleScreened, XiangqiSide.RED))
    }

    @Test fun pinnedPiecesCannotExposeTheirGeneralToARook() {
        val state = position(mapOf(GridCell(4, 0) to -5, GridCell(4, 7) to 5))
        assertFalse(XiangqiEngine.isInCheck(state, XiangqiSide.RED))
        assertFalse(move(4, 7, 5, 7) in XiangqiEngine.legalMoves(state))
        assertSame(state, XiangqiEngine.play(state, move(4, 7, 3, 7)))
        assertTrue(move(4, 7, 4, 6) in XiangqiEngine.legalMoves(state))
        assertTrue(move(4, 7, 4, 0) in XiangqiEngine.legalMoves(state))
    }

    @Test fun everyLegalReplyToCheckActuallyResolvesTheCheck() {
        val state = position(mapOf(GridCell(4, 7) to -5, GridCell(0, 8) to 5))
        assertTrue(XiangqiEngine.isInCheck(state, XiangqiSide.RED))
        assertTrue(move(0, 8, 4, 8) in XiangqiEngine.legalMoves(state))
        assertFalse(move(0, 8, 0, 7) in XiangqiEngine.legalMoves(state))
        assertFalse(move(4, 9, 3, 9) in XiangqiEngine.legalMoves(state))
        assertTrue(move(4, 9, 5, 9) in XiangqiEngine.legalMoves(state))
        for (reply in XiangqiEngine.legalMoves(state)) {
            assertFalse(XiangqiEngine.isInCheck(XiangqiEngine.play(state, reply), XiangqiSide.RED))
        }
    }

    @Test fun capturingAGeneralWinsAndTerminalBoardsCannotMove() {
        val state = position(mapOf(GridCell(4, 2) to 5), redGeneral = GridCell(5, 9), blackGeneral = GridCell(4, 0))
        val won = XiangqiEngine.play(state, move(4, 2, 4, 0))
        assertEquals(XiangqiOutcome.RED_WON, won.outcome)
        assertEquals(5, won.pieceAt(4, 0))
        assertEquals(1, won.ply)
        assertTrue(XiangqiEngine.legalMoves(won).isEmpty())
        assertNull(XiangqiEngine.chooseCpuMove(won))
        assertSame(won, XiangqiEngine.play(won, move(5, 9, 5, 8)))
    }

    @Test fun stalemateIsALossEvenWhenTheGeneralIsNotInCheck() {
        val state = position(
            mapOf(GridCell(3, 2) to 5, GridCell(5, 1) to 5, GridCell(4, 5) to 7),
            blackGeneral = GridCell(4, 0),
        )
        val won = XiangqiEngine.play(state, move(3, 2, 3, 1))
        assertFalse(XiangqiEngine.isInCheck(won, XiangqiSide.BLACK))
        assertEquals(XiangqiOutcome.RED_WON, won.outcome)
        assertTrue(XiangqiEngine.legalMoves(won.copy(outcome = XiangqiOutcome.PLAYING)).isEmpty())
    }

    @Test fun checkmateEndsTheGameWithoutCapturingTheGeneral() {
        val state = position(
            mapOf(GridCell(3, 1) to 5, GridCell(3, 2) to 5, GridCell(5, 1) to 5, GridCell(4, 5) to 7),
            blackGeneral = GridCell(4, 0),
        )
        val won = XiangqiEngine.play(state, move(3, 1, 3, 0))
        assertTrue(XiangqiEngine.isInCheck(won, XiangqiSide.BLACK))
        assertEquals(-1, won.pieceAt(4, 0))
        assertEquals(XiangqiOutcome.RED_WON, won.outcome)
        assertTrue(XiangqiEngine.legalMoves(won.copy(outcome = XiangqiOutcome.PLAYING)).isEmpty())
    }

    @Test fun computerTakesAnImmediateWinForEitherSide() {
        for (side in XiangqiSide.entries) {
            val pieces = if (side == XiangqiSide.BLACK) mapOf(GridCell(4, 7) to -5)
                else mapOf(GridCell(3, 2) to 5)
            val state = position(pieces, turn = side)
            val before = state.board.toList()
            val move = requireNotNull(XiangqiEngine.chooseCpuMove(state))
            assertTrue(move in XiangqiEngine.legalMoves(state))
            assertEquals(
                if (side == XiangqiSide.RED) XiangqiOutcome.RED_WON else XiangqiOutcome.BLACK_WON,
                XiangqiEngine.play(state, move).outcome,
            )
            assertEquals(before, state.board)
        }
    }

    @Test fun computerFindsTheOnlyLegalBlockInsteadOfIgnoringCheck() {
        val state = position(
            mapOf(GridCell(0, 1) to -5, GridCell(4, 2) to 5, GridCell(3, 3) to 5),
            turn = XiangqiSide.BLACK, redGeneral = GridCell(5, 9), blackGeneral = GridCell(4, 0),
        )
        val forced = move(0, 1, 4, 1)
        assertTrue(XiangqiEngine.isInCheck(state, XiangqiSide.BLACK))
        assertEquals(listOf(forced), XiangqiEngine.legalMoves(state))
        assertEquals(forced, XiangqiEngine.chooseCpuMove(state))
        assertFalse(XiangqiEngine.isInCheck(XiangqiEngine.play(state, forced), XiangqiSide.BLACK))
    }

    @Test fun twoPlyComputerAvoidsTradingARookForADefendedPawn() {
        val state = position(
            mapOf(GridCell(4, 4) to -5, GridCell(4, 5) to 7, GridCell(4, 7) to 5),
            turn = XiangqiSide.BLACK,
        )
        val trap = move(4, 4, 4, 5)
        assertTrue(trap in XiangqiEngine.legalMoves(state))
        val chosen = requireNotNull(XiangqiEngine.chooseCpuMove(state))
        assertTrue(chosen in XiangqiEngine.legalMoves(state))
        assertFalse(trap == chosen)
        assertFalse(XiangqiEngine.isInCheck(XiangqiEngine.play(state, chosen), XiangqiSide.BLACK))
    }

    @Test fun computerCanChooseALegalOpeningForRedAndDoesNotMutateThePosition() {
        val state = XiangqiEngine.newGame()
        val original = state.board.toList()
        val move = requireNotNull(XiangqiEngine.chooseCpuMove(state))
        assertTrue(move in XiangqiEngine.legalMoves(state))
        assertEquals(original, state.board)
        assertFalse(XiangqiEngine.isInCheck(XiangqiEngine.play(state, move), XiangqiSide.RED))
    }

    private fun targets(state: XiangqiState, x: Int, y: Int): Set<GridCell> =
        XiangqiEngine.legalMoves(state, GridCell(x, y)).map { it.to }.toSet()

    private fun move(fromX: Int, fromY: Int, toX: Int, toY: Int): XiangqiMove =
        XiangqiMove(GridCell(fromX, fromY), GridCell(toX, toY))

    private fun position(
        pieces: Map<GridCell, Int>,
        turn: XiangqiSide = XiangqiSide.RED,
        redGeneral: GridCell = GridCell(4, 9),
        blackGeneral: GridCell = GridCell(3, 0),
    ): XiangqiState {
        val board = MutableList(90) { 0 }
        board[redGeneral.y * 9 + redGeneral.x] = 1
        board[blackGeneral.y * 9 + blackGeneral.x] = -1
        for ((cell, piece) in pieces) board[cell.y * 9 + cell.x] = piece
        return XiangqiState(board = board.toList(), turnSide = turn)
    }
}
