package com.jiligulu.app.ui.littleworld

import org.junit.Assert.*
import org.junit.Test

class GameFinishPresentationTest {
    @Test fun agreedChessDrawNeverAnnouncesAWinnerOrInventsAMate() {
        val game = XiangqiEngine.newGame().copy(outcome = XiangqiOutcome.DRAW)
        for (viewer in listOf(null, XiangqiSide.RED, XiangqiSide.BLACK)) {
            val shown = GameFinishPresenter.xiangqi(game, viewer)
            assertEquals(FinishMood.DRAW, shown?.mood)
            assertEquals("和棋", shown?.watermark)
        }
        assertNull(XiangqiMateClassifier.classify(game))
        assertEquals("和棋", xiangqiSealText(null, XiangqiOutcome.DRAW))
    }
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
        assertNamedWatermarkForBothViewers(end, XiangqiFinishFamily.CHECKMATE, "将死")
        val falseFlag=XiangqiEngine.newGame().copy(outcome=XiangqiOutcome.RED_WON)
        assertFalse(GameFinishPresenter.xiangqi(falseFlag,XiangqiSide.RED)!!.detail.contains("将死"))
        assertNull(GameFinishPresenter.xiangqi(falseFlag,XiangqiSide.RED)!!.watermark)
    }

    @Test fun aRealDoubleCannonMateKeepsItsNameForWinnerLoserAndBothColors() {
        val redWin = finalPosition(mapOf(
            GridCell(3,0) to -XiangqiEngine.ADVISOR, GridCell(5,0) to -XiangqiEngine.ADVISOR,
            GridCell(4,1) to XiangqiEngine.CANNON, GridCell(4,3) to XiangqiEngine.CANNON,
            GridCell(2,2) to XiangqiEngine.HORSE,
        ))
        for (game in listOf(redWin, reverseColors(redWin))) {
            assertNamedWatermarkForBothViewers(game, XiangqiFinishFamily.DOUBLE_CANNON, "重炮")
        }
    }

    @Test fun playingTheSmotheredCannonSolutionProducesTheRequestedSmotheredWatermark() {
        val puzzle = XiangqiPuzzles.all[1]
        val redWin = XiangqiEngine.play(puzzle.position, puzzle.solution)
        assertEquals(XiangqiOutcome.RED_WON, redWin.outcome)
        for (game in listOf(redWin, reverseColors(redWin))) {
            assertNamedWatermarkForBothViewers(game, XiangqiFinishFamily.SMOTHERED_CANNON, "闷杀")
        }
    }

    @Test fun noLegalMoveWithoutCheckIsShownAsStalemateInsteadOfCheckmate() {
        val redWin = finalPosition(mapOf(
            GridCell(3,1) to XiangqiEngine.ROOK, GridCell(5,1) to XiangqiEngine.ROOK,
            GridCell(4,5) to XiangqiEngine.PAWN,
        ))
        for (game in listOf(redWin, reverseColors(redWin))) {
            assertFalse(XiangqiEngine.isInCheck(game, game.turnSide))
            assertNamedWatermarkForBothViewers(game, XiangqiFinishFamily.STALEMATE, "困毙")
        }
    }

    @Test fun anActuallyPlayedGeneralCaptureIsNamedForEitherWinningColor() {
        val redBefore = finalPosition(mapOf(GridCell(4,2) to XiangqiEngine.ROOK),
            redGeneral = GridCell(5,9)).copy(turnSide = XiangqiSide.RED, outcome = XiangqiOutcome.PLAYING)
        val redMove = XiangqiMove(GridCell(4,2), GridCell(4,0))
        for ((before, move) in listOf(redBefore to redMove, reverseColors(redBefore) to reverseMove(redMove))) {
            assertTrue(move in XiangqiEngine.legalMoves(before))
            val end = XiangqiEngine.play(before, move)
            assertEquals(if (before.turnSide == XiangqiSide.RED) XiangqiOutcome.RED_WON else XiangqiOutcome.BLACK_WON, end.outcome)
            assertNamedWatermarkForBothViewers(end, XiangqiFinishFamily.GENERAL_CAPTURE, "擒将")
        }
    }

    @Test fun anOutcomeFlagAndAnEscapableCheckCannotInventANamedWatermark() {
        val escapableCheck = finalPosition(mapOf(GridCell(4,3) to XiangqiEngine.CANNON,
            GridCell(4,1) to -XiangqiEngine.PAWN))
        assertTrue(XiangqiEngine.isInCheck(escapableCheck, XiangqiSide.BLACK))
        for (game in listOf(
            XiangqiEngine.newGame().copy(outcome = XiangqiOutcome.RED_WON),
            XiangqiEngine.newGame().copy(outcome = XiangqiOutcome.BLACK_WON),
            escapableCheck, reverseColors(escapableCheck),
        )) {
            assertNull(XiangqiMateClassifier.classify(game))
            val winner = if (game.outcome == XiangqiOutcome.RED_WON) XiangqiSide.RED else XiangqiSide.BLACK
            assertEquals(FinishMood.WIN, GameFinishPresenter.xiangqi(game, winner)!!.mood)
            assertEquals(FinishMood.LOSE, GameFinishPresenter.xiangqi(game, winner.opponent)!!.mood)
            for (viewer in listOf(winner, winner.opponent, null)) {
                assertNull(GameFinishPresenter.xiangqi(game, viewer)!!.watermark)
            }
        }
        assertNull(GameFinishPresenter.xiangqi(XiangqiPuzzles.all[1].position, XiangqiSide.RED))
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

    private fun assertNamedWatermarkForBothViewers(game: XiangqiState, family: XiangqiFinishFamily, word: String) {
        val proof = requireNotNull(XiangqiMateClassifier.classify(game))
        assertEquals(family, proof.family)
        val winner = requireNotNull(GameFinishPresenter.xiangqi(game, proof.winner))
        val loser = requireNotNull(GameFinishPresenter.xiangqi(game, proof.winner.opponent))
        val shared = requireNotNull(GameFinishPresenter.xiangqi(game, null))
        assertEquals(FinishMood.WIN, winner.mood)
        assertEquals(FinishMood.LOSE, loser.mood)
        assertEquals(FinishMood.SHARED, shared.mood)
        assertEquals(word, winner.watermark)
        assertEquals(word, loser.watermark)
        assertEquals(word, shared.watermark)
        assertEquals(winner.detail, loser.detail)
        assertEquals(winner.detail, shared.detail)
        assertFalse("The description must not change its meaning for the defeated viewer", loser.detail.contains("对方"))
    }

    private fun finalPosition(pieces: Map<GridCell, Int>, redGeneral: GridCell = GridCell(4,9)) = XiangqiState(
        board = MutableList(90) { 0 }.apply {
            this[redGeneral.y * 9 + redGeneral.x] = XiangqiEngine.GENERAL
            this[4] = -XiangqiEngine.GENERAL
            pieces.forEach { (cell, piece) -> this[cell.y * 9 + cell.x] = piece }
        }, turnSide = XiangqiSide.BLACK, outcome = XiangqiOutcome.RED_WON,
    )

    private fun reverseMove(move: XiangqiMove) = XiangqiMove(
        GridCell(8 - move.from.x, 9 - move.from.y), GridCell(8 - move.to.x, 9 - move.to.y))

    private fun reverseColors(game: XiangqiState) = game.copy(
        board = game.board.reversed().map { -it }, turnSide = game.turnSide.opponent,
        outcome = when (game.outcome) {
            XiangqiOutcome.PLAYING -> XiangqiOutcome.PLAYING
            XiangqiOutcome.RED_WON -> XiangqiOutcome.BLACK_WON
            XiangqiOutcome.BLACK_WON -> XiangqiOutcome.RED_WON
            XiangqiOutcome.DRAW -> XiangqiOutcome.DRAW
        }, lastMove = game.lastMove?.let(::reverseMove),
    )
}
