package com.jiligulu.app.ui.littleworld

import org.junit.Assert.*
import org.junit.Test

class GameFinishPresentationTest {
    @Test fun theSameWhiteWinShowsVictoryToWhiteAndDefeatToBlack() {
        val game=GomokuState(outcome=GomokuOutcome.CPU_WON)
        assertEquals(FinishMood.WIN,GameFinishPresenter.gomoku(game,2)?.mood)
        assertEquals(FinishMood.LOSE,GameFinishPresenter.gomoku(game,1)?.mood)
        assertEquals("白方胜出",GameFinishPresenter.gomoku(game,null)?.headline)
        assertNull(GameFinishPresenter.gomoku(GomokuEngine.newGame(),1))
    }
    @Test fun completedLinesIncludeWinningGapAndOverlineWithoutRowWrapping() {
        val board=MutableList(225){0}.apply{for(x in listOf(2,3,5,6,7))this[7*15+x]=1}
        val win=GomokuEngine.play(GomokuState(board=board),4,7)
        assertEquals((2..7).map{GridCell(it,7)},GameFinishPresenter.gomokuWinningLine(win))
        val forged=win.copy(lastMove=GridCell(0,0))
        assertTrue(GameFinishPresenter.gomokuWinningLine(forged).isEmpty())
        assertTrue(GameFinishPresenter.gomokuWinningLine(win.copy(outcome=GomokuOutcome.DRAW)).isEmpty())
    }
    @Test fun actualChessMatesIdentifyBothViewersAndDoNotInventAMateFromAnOutcomeFlag() {
        val puzzle=XiangqiPuzzles.all.first()
        val end=XiangqiEngine.play(puzzle.position,puzzle.solution)
        assertEquals(FinishMood.WIN,GameFinishPresenter.xiangqi(end,XiangqiSide.RED)?.mood)
        assertEquals(FinishMood.LOSE,GameFinishPresenter.xiangqi(end,XiangqiSide.BLACK)?.mood)
        assertEquals("红方胜出",GameFinishPresenter.xiangqi(end,null)?.headline)
        val falseFlag=XiangqiEngine.newGame().copy(outcome=XiangqiOutcome.RED_WON)
        assertFalse(GameFinishPresenter.xiangqi(falseFlag,XiangqiSide.RED)!!.detail.contains("将死"))
    }
    @Test fun blackPerspectiveClickAndEveryHighlightShareOneSquareMapping() {
        for(y in 0..9)for(x in 0..8){val cell=GridCell(x,y)
            assertEquals(cell,XiangqiBoardCoordinates.model(XiangqiBoardCoordinates.display(cell,true),true))
            assertEquals(cell,XiangqiBoardCoordinates.display(cell,false))}
        val afterRed=XiangqiEngine.play(XiangqiEngine.newGame(),XiangqiMove(GridCell(4,6),GridCell(4,5)))
        val from=XiangqiBoardCoordinates.model(GridCell(8,6),true)
        val to=XiangqiBoardCoordinates.model(GridCell(8,5),true)
        assertEquals(XiangqiMove(GridCell(0,3),GridCell(0,4)),XiangqiMove(from,to))
        assertTrue(XiangqiMove(from,to) in XiangqiEngine.legalMoves(afterRed))
        assertEquals(GridCell(8,5),XiangqiBoardCoordinates.display(to,true))
    }
    @Test fun undoForTheHumanPlayingSecondLeavesTheComputersOpeningOnTheBoard() {
        val start=GomokuEngine.newGame();val first=GomokuEngine.play(start,7,7)
        val second=GomokuEngine.play(first,8,8)
        assertEquals(-1,LocalChessUndo.gomokuTarget(listOf(start),humanPlayer=2))
        assertEquals(1,LocalChessUndo.gomokuTarget(listOf(start,first,second),humanPlayer=2))
        val xqStart=XiangqiEngine.newGame();val red=XiangqiEngine.play(xqStart,XiangqiMove(GridCell(0,6),GridCell(0,5)))
        assertEquals(-1,LocalChessUndo.xiangqiTarget(listOf(xqStart),true,XiangqiSide.BLACK))
        assertEquals(1,LocalChessUndo.xiangqiTarget(listOf(xqStart,red),true,XiangqiSide.BLACK))
    }
}
