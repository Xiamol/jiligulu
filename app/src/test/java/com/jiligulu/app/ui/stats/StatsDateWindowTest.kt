package com.jiligulu.app.ui.stats

import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StatsDateWindowTest {
    @Test fun dailyPrefetchReusesItsLoadedWindowUntilTheViewportApproachesAnEdge() {
        val visible = StatsDateWindow(LocalDate.of(2026, 12, 27), LocalDate.of(2027, 1, 5))
        val loaded = retainedStatsDayWindow(null, visible)
        assertEquals(StatsDateWindow(LocalDate.of(2026, 12, 17), LocalDate.of(2027, 1, 15)), loaded)
        assertEquals(loaded, retainedStatsDayWindow(loaded, visible.shifted(6)))
        val next = retainedStatsDayWindow(loaded, visible.shifted(8))
        assertEquals(visible.first.plusDays(8).minusDays(10), next.first)
        assertEquals(30, next.dates.size)
        val far = retainedStatsDayWindow(next, visible.shifted(10_000))
        assertEquals(30, far.dates.size)
        assertEquals(visible.first.plusDays(10_000).minusDays(10), far.first)
    }

    @Test fun monthPrefetchIncludesRealNeighbourMonthsAcrossTheNewYear() {
        val window = prefetchedStatsMonths(java.time.YearMonth.of(2027, 1))
        assertEquals(LocalDate.of(2026, 12, 1), window.first)
        assertEquals(LocalDate.of(2027, 2, 28), window.last)
        assertEquals(90, window.dates.size)
        assertEquals(LocalDate.of(1, 1, 1), prefetchedStatsMonths(java.time.YearMonth.of(1, 1)).first)
        assertEquals(LocalDate.of(9999, 12, 31), prefetchedStatsMonths(java.time.YearMonth.of(9999, 12)).last)
    }
    @Test fun tenDaysCrossDecemberAndJanuaryInsteadOfClampingToAMonth() {
        val window = compactStatsWindow(LocalDate.of(2026, 12, 31), LocalDate.of(2026, 10, 7))
        assertEquals(LocalDate.of(2026, 12, 27), window.first)
        assertEquals(LocalDate.of(2027, 1, 5), window.last)
        assertEquals(10, window.dates.size)
        assertEquals(LocalDate.of(2027, 1, 6), window.shifted(10).first)
        assertEquals(window, window.shifted(10).shifted(-10))
    }

    @Test fun wholeMonthIncludesAllLeapDaysAndTransitionsBothWays() {
        assertEquals(29, monthStatsWindow(LocalDate.of(2028, 2, 29)).dates.size)
        assertEquals(28, monthStatsWindow(LocalDate.of(2027, 2, 1)).dates.size)
        assertEquals(31, monthStatsWindow(LocalDate.of(2026, 12, 31)).dates.size)
        assertEquals(LocalDate.of(2027, 1, 31), adjacentStatsMonth(LocalDate.of(2026, 12, 31), 1))
        assertEquals(LocalDate.of(2027, 2, 28), adjacentStatsMonth(LocalDate.of(2027, 1, 31), 1))
        assertEquals(LocalDate.of(2026, 12, 31), adjacentStatsMonth(LocalDate.of(2027, 1, 31), -1))
    }

    @Test fun crossMonthBarsUseActualBillDatesAmountsAndTypeWithZeroDaysIncluded() {
        val zone = ZoneId.of("Asia/Tokyo")
        val window = compactStatsWindow(LocalDate.of(2026, 12, 31), LocalDate.of(2026, 10, 7))
        fun date(day: String) = LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli()
        val bills = listOf(
            BillEntity(id = 1, amountFen = 1299, type = BillType.EXPENSE, categoryId = 1, detail = "午饭", timestamp = date("2026-12-31") + 1000),
            BillEntity(id = 2, amountFen = 300, type = BillType.EXPENSE, categoryId = 1, detail = "饮品", timestamp = date("2026-12-31") + 2000),
            BillEntity(id = 3, amountFen = 500, type = BillType.EXPENSE, categoryId = 1, detail = "水果", timestamp = date("2027-01-01") + 1000),
            BillEntity(id = 4, amountFen = 8000, type = BillType.INCOME, categoryId = 2, detail = "红包", timestamp = date("2027-01-01") + 1000),
            BillEntity(id = 5, amountFen = 10000, type = BillType.EXPENSE, categoryId = 1, detail = "范围外", timestamp = date("2027-01-06")))
        val bars = statsDayBars(bills, BillType.EXPENSE, window, LocalDate.of(2026, 12, 31), zone)
        assertEquals(10, bars.size)
        assertEquals(1599L, bars[4].amountFen)
        assertEquals(500L, bars[5].amountFen)
        assertEquals(0L, bars.last().amountFen)
        assertEquals(2099L, bars.sumOf { it.amountFen })
        assertTrue(bars[4].isToday)
        assertEquals(8000L, statsDayBars(bills, BillType.INCOME, window, LocalDate.of(2026, 12, 31), zone).sumOf { it.amountFen })
    }

    @Test fun databaseEndIsExclusiveAndUsesLocalCalendarDaysAcrossDst() {
        val zone = ZoneId.of("America/New_York")
        val window = StatsDateWindow(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 8))
        val range = window.millis(zone)
        assertEquals(23 * 60 * 60 * 1000L, range.second - range.first)
        assertEquals(LocalDate.of(2026, 3, 9).atStartOfDay(zone).toInstant().toEpochMilli(), range.second)
    }

    @Test fun todaysInitialWindowShowsTenRecentDaysAcrossTheNewYear() {
        val today = LocalDate.of(2027, 1, 1)
        val window = compactStatsWindow(today, today)
        assertEquals(LocalDate.of(2026, 12, 23), window.first)
        assertEquals(today, window.last)
        assertEquals(10, window.dates.size)
        // Initial placement does not forbid moving beyond today or crossing another month.
        assertEquals(LocalDate.of(2027, 1, 2), window.shifted(10).first)
        assertEquals(LocalDate.of(2027, 1, 11), window.shifted(10).last)
    }

    @Test fun historicalSelectionStaysCenteredAndDoesNotRepositionAtMidnight() {
        val selected = LocalDate.of(2026, 12, 28)
        val beforeMidnight = compactStatsWindow(selected, LocalDate.of(2027, 1, 1))
        assertEquals(LocalDate.of(2026, 12, 24), beforeMidnight.first)
        assertEquals(LocalDate.of(2027, 1, 2), beforeMidnight.last)
        assertEquals(beforeMidnight, compactStatsWindow(selected, LocalDate.of(2027, 1, 2)))
    }
}
