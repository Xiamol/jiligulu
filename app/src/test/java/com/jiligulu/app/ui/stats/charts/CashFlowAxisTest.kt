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
    @Test fun compactSlotAmountsStayShortWhileUsingCorrectUnits() {
        assertEquals("43.3", compactCashFlowAmount(4327))
        assertEquals("63.4", compactCashFlowAmount(6340))
        assertEquals("1.2k", compactCashFlowAmount(123456))
        assertEquals("1.2万", compactCashFlowAmount(1234567))
        assertEquals("0", compactCashFlowAmount(0))
        assertEquals("0.01", compactCashFlowAmount(1))
    }
}
