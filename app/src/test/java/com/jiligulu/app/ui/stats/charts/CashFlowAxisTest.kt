package com.jiligulu.app.ui.stats.charts
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
class CashFlowAxisTest {
    @Test fun nativeSlotsFillTenDaysExactlyAtFractionalDensities() {
        for (width in listOf(219, 280, 670, 707, 853, 1007)) for (first in 0..29) {
            val slots = (first until first + 10).map { cashFlowDaySlotWidthPx(it, width) }
            assertEquals(width, slots.sum())
            assertTrue(slots.maxOrNull()!! - slots.minOrNull()!! <= 1)
        }
        for (width in listOf(219, 707, 1007)) for (count in 28..31)
            assertEquals(width, (0 until count).sumOf { cashFlowDateSlotWidthPx(it, count, width) })
    }

    @Test fun nativeCalendarIndicesCrossYearsAndIncludeBothCalendarEdges() {
        val december = LocalDate.of(2026, 12, 31)
        assertEquals(LocalDate.of(2027, 1, 1), cashFlowIndexDate(cashFlowDayIndex(december) + 1))
        assertEquals(YearMonth.of(2027, 1), cashFlowIndexMonth(cashFlowMonthIndex(YearMonth.of(2026, 12)) + 1))
        assertEquals(LocalDate.of(1, 1, 1), cashFlowIndexDate(0))
        assertEquals(LocalDate.of(9999, 12, 31), cashFlowIndexDate(cashFlowDayCount - 1))
        assertEquals(YearMonth.of(1, 1), cashFlowIndexMonth(0))
        assertEquals(YearMonth.of(9999, 12), cashFlowIndexMonth(cashFlowMonthCount - 1))
    }
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
    @Test fun monthDateLabelsRemainInsideFractionalSlotWidthsAtBothEdges() {
        for (days in 28..31) {
            for ((plotWidth, labelWidth) in listOf(219 to 24, 280 to 27, 670 to 72, 707 to 72)) {
                for (index in 0 until days) {
                    val left = cashFlowDateLabelLeftPx(index, days, plotWidth, labelWidth)
                    assertTrue(left >= 0)
                    assertTrue(left + labelWidth <= plotWidth)
                }
                assertEquals(0, cashFlowDateLabelLeftPx(0, days, plotWidth, labelWidth))
                assertEquals(plotWidth - labelWidth,
                    cashFlowDateLabelLeftPx(days - 1, days, plotWidth, labelWidth))
            }
        }
    }
}
