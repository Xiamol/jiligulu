package com.jiligulu.app.ui.stats.charts
import org.junit.Assert.*
import org.junit.Test
class CashFlowAxisTest {
    @Test fun readableTicksLeaveReasonableHeadroom() {
        for (max in listOf(.01, .57, 9.0, 17.85, 2000.0, 50000.0)) {
            val axis = cashFlowAxis(max)
            assertTrue(max / axis.top in .80.. .95)
            assertTrue(axis.intervals in 3..6)
        }
        assertEquals(2200.0, cashFlowAxisTop(2000.0), .001)
        assertEquals(550.0, cashFlowAxis(2000.0).step, .001)
        assertTrue(cashFlowAxisTop(0.0) > 0)
    }
}
