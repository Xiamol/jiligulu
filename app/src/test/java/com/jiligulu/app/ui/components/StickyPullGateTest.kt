package com.jiligulu.app.ui.components
import org.junit.Assert.*
import org.junit.Test
class StickyPullGateTest {
    @Test fun arrivingAtTopStopsThisGestureAndAllowsTheNext() {
        val gate = StickyPullGate()
        gate.begin(true, false)
        gate.finish(true, false, true)
        gate.blockedAtTop() // Inertia reaches the first bill after the finger lifted.
        assertFalse(gate.allowExpand)
        gate.begin(true, true)
        assertTrue(gate.allowExpand)
    }
    @Test fun freshPinnedPageNeedsTwoPullsAndPageChangesResetIt() {
        val gate = StickyPullGate()
        gate.begin(true, true); assertFalse(gate.allowExpand)
        gate.finish(true, true, true)
        gate.begin(true, true); assertTrue(gate.allowExpand)
        gate.reset(); gate.begin(true, true); assertFalse(gate.allowExpand)
    }
    @Test fun horizontalOrUpwardGestureDoesNotArmExpansion() {
        val gate = StickyPullGate()
        gate.begin(false, true); gate.finish(true, true, false)
        gate.begin(true, true); assertFalse(gate.allowExpand)
    }
}
