package com.jiligulu.app.ui.littleworld

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalChessUndoTest {
    @Test fun gomokuUndoReturnsToBeforeTheHumanMoveWithOrWithoutTheReply() {
        val initial = GomokuEngine.newGame()
        val human = GomokuEngine.play(initial, 7, 7)
        val cpu = GomokuEngine.play(human, 8, 8)
        assertEquals(-1, LocalChessUndo.gomokuTarget(emptyList()))
        assertEquals(0, LocalChessUndo.gomokuTarget(listOf(initial)))
        assertEquals(0, LocalChessUndo.gomokuTarget(listOf(initial, human)))
        assertEquals(2, LocalChessUndo.gomokuTarget(listOf(initial, human, cpu)))
    }

    @Test fun xiangqiHumanRoundAndHotseatUndoUseDifferentTargets() {
        val initial = XiangqiEngine.newGame()
        val red = XiangqiEngine.play(initial, XiangqiMove(GridCell(0, 6), GridCell(0, 5)))
        val black = XiangqiEngine.play(red, XiangqiMove(GridCell(0, 3), GridCell(0, 4)))
        assertEquals(0, LocalChessUndo.xiangqiTarget(listOf(initial, red), cpu = true))
        assertEquals(1, LocalChessUndo.xiangqiTarget(listOf(initial, red), cpu = false))
        assertEquals(2, LocalChessUndo.xiangqiTarget(listOf(initial, red, black), cpu = true))
        assertEquals(-1, LocalChessUndo.xiangqiTarget(emptyList(), cpu = false))
    }
}
