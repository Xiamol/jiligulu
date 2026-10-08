package com.jiligulu.app.ui.stats.charts

import org.junit.Assert.assertEquals
import org.junit.Test

class CashFlowMonthBoundaryTest {
    @Test fun onlyTheNextSameDirectionGestureDuringTheGracePeriodIsGranted() {
        val gate = CashFlowMonthBoundary()
        assertEquals(0, gate.begin(10))
        gate.finish(-1, false, 100)
        assertEquals(-1, gate.begin(1_500))
        gate.finish(-1, true, 1_600)
        assertEquals(0, gate.begin(1_601))
        gate.finish(-1, false, 2_000)
        assertEquals(0, gate.begin(3_501))
    }

    @Test fun anInterveningOrdinaryGestureOrExplicitNavigationClearsTheArmedEdge() {
        val gate = CashFlowMonthBoundary()
        gate.finish(1, false, 100)
        assertEquals(1, gate.begin(200))
        gate.finish(0, false, 300)
        assertEquals(0, gate.begin(400))
        gate.finish(-1, false, 500)
        gate.clear()
        assertEquals(0, gate.begin(600))
    }

    @Test fun reversingDirectionCannotSpendThePreviousDirectionGrant() {
        val gate = CashFlowMonthBoundary()
        gate.finish(-1, false, 100)
        val allowed = gate.begin(200)
        val actualDirection = 1
        assertEquals(false, allowed == actualDirection)
        gate.finish(actualDirection, false, 300)
        assertEquals(actualDirection, gate.begin(400))
    }
}
