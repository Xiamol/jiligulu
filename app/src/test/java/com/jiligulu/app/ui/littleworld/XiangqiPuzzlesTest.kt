package com.jiligulu.app.ui.littleworld

import org.junit.Assert.*
import org.junit.Test

class XiangqiPuzzlesTest {
    @Test fun eachPuzzleStartsWithoutCheckingTheWrongSideAndHasALegalMate() {
        XiangqiPuzzles.all.forEach {puzzle ->
            assertFalse(puzzle.title,XiangqiEngine.isInCheck(puzzle.position,XiangqiSide.BLACK))
            assertFalse(puzzle.title,XiangqiEngine.isInCheck(puzzle.position,XiangqiSide.RED))
            val won=XiangqiEngine.play(puzzle.position,puzzle.solution)
            assertNotEquals(puzzle.title,puzzle.position,won)
            assertEquals(puzzle.title,XiangqiOutcome.RED_WON,won.outcome)
        }
    }
}
