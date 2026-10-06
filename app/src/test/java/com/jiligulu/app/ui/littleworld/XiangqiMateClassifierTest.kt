package com.jiligulu.app.ui.littleworld

import org.junit.Assert.*
import org.junit.Test

class XiangqiMateClassifierTest {
    @Test fun classifiesSmotheredCannonFromTheBoardForEitherColor() {
        val puzzle = XiangqiPuzzles.all[1]
        val final = XiangqiEngine.play(puzzle.position, puzzle.solution)
        for (state in listOf(final, rotate(final))) {
            val proof = requireNotNull(XiangqiMateClassifier.classify(state))
            assertEquals(XiangqiFinishFamily.SMOTHERED_CANNON, proof.family)
            assertEquals(state.turnSide.opponent, proof.winner)
            assertEquals(1, proof.checkingCells.size)
        }
    }

    @Test fun doubleCannonRequiresTheOtherFriendlyCannonToBeTheActualScreen() {
        val state = finalPosition(mapOf(
            GridCell(3, 0) to -2, GridCell(5, 0) to -2,
            GridCell(4, 1) to 6, GridCell(4, 3) to 6, GridCell(2, 2) to 4,
        ))
        assertEquals(XiangqiFinishFamily.DOUBLE_CANNON, XiangqiMateClassifier.classify(state)?.family)
        val pawnScreen = state.copy(board = state.board.toMutableList().apply { this[13] = 7 })
        assertEquals(XiangqiFinishFamily.CHECKMATE, XiangqiMateClassifier.classify(pawnScreen)?.family)
    }

    @Test fun ordinaryHorseMateIsNotInventedAsASpecificNamedPattern() {
        val puzzle = XiangqiPuzzles.all[2]
        val state = XiangqiEngine.play(puzzle.position, puzzle.solution)
        assertEquals(XiangqiFinishFamily.CHECKMATE, XiangqiMateClassifier.classify(state)?.family)
    }

    @Test fun stalemateIsDistinguishedFromCheckmate() {
        val state = finalPosition(mapOf(GridCell(3, 1) to 5, GridCell(5, 1) to 5, GridCell(4, 5) to 7))
        assertFalse(XiangqiEngine.isInCheck(state, XiangqiSide.BLACK))
        assertEquals(XiangqiFinishFamily.STALEMATE, XiangqiMateClassifier.classify(state)?.family)
    }

    @Test fun unprovenVictoryAndOngoingCheckNeverGetNamedMateEffects() {
        assertNull(XiangqiMateClassifier.classify(XiangqiEngine.newGame().copy(outcome = XiangqiOutcome.RED_WON)))
        assertNull(XiangqiMateClassifier.classify(XiangqiPuzzles.all[1].position))
        val canEscape = finalPosition(mapOf(GridCell(4, 3) to 6, GridCell(4, 1) to -7))
        assertTrue(XiangqiEngine.isInCheck(canEscape, XiangqiSide.BLACK))
        assertNull(XiangqiMateClassifier.classify(canEscape))
    }

    @Test fun capturedGeneralIsItsOwnResult() {
        val state = finalPosition(mapOf(GridCell(4, 0) to 5))
        assertEquals(XiangqiFinishFamily.GENERAL_CAPTURE, XiangqiMateClassifier.classify(state)?.family)
    }

    private fun finalPosition(pieces: Map<GridCell, Int>): XiangqiState = XiangqiState(
        board = MutableList(90) { 0 }.apply {
            this[85] = 1
            this[4] = -1
            pieces.forEach { (cell, piece) -> this[cell.y * 9 + cell.x] = piece }
        }, turnSide = XiangqiSide.BLACK, outcome = XiangqiOutcome.RED_WON,
    )

    private fun rotate(state: XiangqiState): XiangqiState = state.copy(
        board = state.board.reversed().map { -it }, turnSide = state.turnSide.opponent,
        outcome = XiangqiOutcome.BLACK_WON,
    )
}
