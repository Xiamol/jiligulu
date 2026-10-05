package com.jiligulu.app.ui.littleworld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiangqiSelectionRulesTest {
    @Test fun guestCanSeeRedsOwnSelectedPieceOnlyDuringRedsTurn() {
        val initial = XiangqiEngine.newGame()
        assertTrue(XiangqiSelectionRules.accepts(0, initial, XiangqiSide.BLACK,
            XiangqiLanMessage.Select(0, GridCell(0, 6))))
        assertFalse(XiangqiSelectionRules.accepts(0, initial, XiangqiSide.BLACK,
            XiangqiLanMessage.Select(0, GridCell(0, 3)))) // Black is not the peer of the black guest.
        assertFalse(XiangqiSelectionRules.accepts(0, initial, XiangqiSide.RED,
            XiangqiLanMessage.Select(0, GridCell(0, 3)))) // Black cannot choose before red moves.
        assertFalse(XiangqiSelectionRules.accepts(0, initial, XiangqiSide.BLACK,
            XiangqiLanMessage.Select(0, GridCell(0, 5)))) // Empty square.
        assertEquals(XiangqiEngine.newGame(), initial)
    }

    @Test fun hostSeesBlacksSelectionAfterOneLegalMoveButOldHintsAreIgnored() {
        val played = XiangqiEngine.play(XiangqiEngine.newGame(), XiangqiMove(GridCell(0, 6), GridCell(0, 5)))
        assertTrue(XiangqiSelectionRules.accepts(1, played, XiangqiSide.RED,
            XiangqiLanMessage.Select(1, GridCell(1, 2))))
        assertFalse(XiangqiSelectionRules.accepts(1, played, XiangqiSide.RED,
            XiangqiLanMessage.Select(0, GridCell(1, 2))))
        assertFalse(XiangqiSelectionRules.accepts(1, played, XiangqiSide.RED,
            XiangqiLanMessage.Select(2, GridCell(1, 2))))
        assertFalse(XiangqiSelectionRules.accepts(1, played, XiangqiSide.BLACK,
            XiangqiLanMessage.Select(1, GridCell(0, 5))))
    }

    @Test fun currentClearWorksRegardlessOfTurnOrFinishButNotStaleOrWithoutASide() {
        val finished = XiangqiEngine.newGame().copy(outcome = XiangqiOutcome.RED_WON)
        assertTrue(XiangqiSelectionRules.accepts(7, finished, XiangqiSide.RED,
            XiangqiLanMessage.Select(7, null)))
        assertFalse(XiangqiSelectionRules.accepts(7, finished, XiangqiSide.RED,
            XiangqiLanMessage.Select(6, null)))
        assertFalse(XiangqiSelectionRules.accepts(7, finished, null,
            XiangqiLanMessage.Select(7, null)))
        assertFalse(XiangqiSelectionRules.canSelect(finished, XiangqiSide.RED, GridCell(0, 6)))
    }

    @Test fun boundsCoordinatesBeforeLookingUpAPiece() {
        val initial = XiangqiEngine.newGame()
        for (cell in listOf(GridCell(-1, 6), GridCell(9, 6), GridCell(0, -1), GridCell(0, 10))) {
            assertFalse(XiangqiSelectionRules.accepts(0, initial, XiangqiSide.BLACK,
                XiangqiLanMessage.Select(0, cell)))
        }
    }

    @Test fun quickChangesKeepOnlyTheFinalChoiceAndNeverRepeatAnUnchangedHint() {
        val hints = XiangqiSelectionHints()
        val left = XiangqiLanMessage.Select(0, GridCell(0, 6))
        val right = XiangqiLanMessage.Select(0, GridCell(2, 6))
        assertFalse(hints.offer(XiangqiLanMessage.Select(0, null)))
        assertTrue(hints.offer(left))
        assertFalse(hints.offer(left))
        assertTrue(hints.offer(right))
        assertEquals(right, hints.take())
        assertFalse(hints.offer(right))
        assertTrue(hints.offer(left))
        assertFalse(hints.offer(right)) // The peer already shows right; cancel unsent left.
        assertNull(hints.take())
        val clear = XiangqiLanMessage.Select(0, null)
        assertTrue(hints.offer(clear))
        assertEquals(clear, hints.take())
        assertFalse(hints.offer(clear))
        hints.clear()
        assertTrue(hints.offer(XiangqiLanMessage.Select(1, GridCell(0, 3))))
    }
}
