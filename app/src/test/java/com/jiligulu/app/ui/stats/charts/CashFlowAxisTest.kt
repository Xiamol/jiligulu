package com.jiligulu.app.ui.stats.charts
import org.junit.Assert.*
import org.junit.Test
class CashFlowAxisTest {
    @Test fun tallestBarUsesNinetyPercentForSmallAndLargeAmounts() {
        for (max in listOf(.01, .57, 9.0, 2000.0, 50000.0)) assertEquals(.9, max / cashFlowAxisTop(max), .000001)
        assertTrue(cashFlowAxisTop(0.0).isFinite())
        assertTrue(cashFlowAxisTop(0.0) > 0)
    }
}
