package com.jiligulu.app.ui.littleworld

import org.junit.Assert.*
import org.junit.Test

class XiangqiSnapshotRulesTest {
    @Test fun firstStateMustBeInitial() {
        val initial=XiangqiEngine.newGame()
        val moved=XiangqiEngine.play(initial,XiangqiMove(GridCell(0,6),GridCell(0,5)))
        assertTrue(XiangqiSnapshotRules.accepts(0,initial,false,XiangqiLanMessage.Snapshot(0,initial)))
        assertFalse(XiangqiSnapshotRules.accepts(0,initial,false,XiangqiLanMessage.Snapshot(1,moved)))
    }
    @Test fun sameRevisionCannotRewriteAndJumpsAreRejected() {
        val initial=XiangqiEngine.newGame()
        val moved=XiangqiEngine.play(initial,XiangqiMove(GridCell(0,6),GridCell(0,5)))
        assertFalse(XiangqiSnapshotRules.accepts(0,initial,true,XiangqiLanMessage.Snapshot(0,moved)))
        assertFalse(XiangqiSnapshotRules.accepts(0,initial,true,XiangqiLanMessage.Snapshot(2,moved)))
        assertTrue(XiangqiSnapshotRules.accepts(0,initial,true,XiangqiLanMessage.Snapshot(1,moved)))
    }
    @Test fun restartMustAdvanceExactlyOneRevision() {
        val initial=XiangqiEngine.newGame()
        val moved=XiangqiEngine.play(initial,XiangqiMove(GridCell(0,6),GridCell(0,5)))
        assertTrue(XiangqiSnapshotRules.accepts(1,moved,true,XiangqiLanMessage.Snapshot(2,initial)))
        assertFalse(XiangqiSnapshotRules.accepts(1,moved,true,XiangqiLanMessage.Snapshot(1,initial)))
    }
}
