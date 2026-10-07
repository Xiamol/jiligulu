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
    @Test fun selectedMonthDayAlwaysGetsADateLabelIncludingNonFiveDayTicks() {
        for (days in 28..31) {
            val ticks = cashFlowMonthDateTicks(days, 7)
            assertTrue(7 in ticks)
            assertTrue(1 in ticks)
            assertTrue(5 in ticks)
            assertTrue(10 in ticks)
            assertTrue(days in ticks)
        }
    }
    @Test fun selectedDayThirtyHidesTheAdjacentMonthEndLabel() {
        val ticks = cashFlowMonthDateTicks(31, 30)
        assertTrue(30 in ticks)
        assertFalse(31 in ticks)
        assertTrue(25 in ticks)
        assertTrue(31 in cashFlowMonthDateTicks(31, 31))
        assertFalse(30 in cashFlowMonthDateTicks(31, 31))
    }
    @Test fun dateLabelsKeepSpaceAtEveryPossibleMonthEndAndSelection() {
        for (days in 28..31) {
            for (selected in 1..days) {
                val ticks = cashFlowMonthDateTicks(days, selected)
                assertTrue(selected in ticks)
                assertTrue(ticks.all { it in 1..days })
                assertTrue(ticks.sorted().zipWithNext().all { (first, second) -> second - first >= 2 })
            }
            assertTrue(days in cashFlowMonthDateTicks(days, null))
        }
        assertEquals(setOf(1, 5, 10, 15, 20, 25, 31), cashFlowMonthDateTicks(31, null))
    }
}
