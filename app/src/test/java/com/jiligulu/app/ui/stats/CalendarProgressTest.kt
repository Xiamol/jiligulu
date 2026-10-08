package com.jiligulu.app.ui.stats

import com.jiligulu.app.data.prefs.CalendarProgressMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarProgressTest {
    @Test fun onlyCompletedCalendarDaysCountAndLeapYearsHaveTheirRealLength() {
        assertEquals(CalendarProgress(0, 31), calendarProgress(LocalDate.of(2026, 10, 1), CalendarProgressMode.MONTH))
        assertEquals(CalendarProgress(28, 29), calendarProgress(LocalDate.of(2028, 2, 29), CalendarProgressMode.MONTH))
        assertEquals(CalendarProgress(27, 28), calendarProgress(LocalDate.of(2027, 2, 28), CalendarProgressMode.MONTH))
        assertEquals(CalendarProgress(365, 366), calendarProgress(LocalDate.of(2028, 12, 31), CalendarProgressMode.YEAR))
        assertEquals(CalendarProgress(364, 365), calendarProgress(LocalDate.of(2027, 12, 31), CalendarProgressMode.YEAR))
        assertEquals("0%", calendarProgress(LocalDate.of(2027, 1, 1), CalendarProgressMode.YEAR).percentage)
        assertTrue(calendarProgress(LocalDate.of(2027, 12, 31), CalendarProgressMode.YEAR).fraction < 1f)
    }

    @Test fun theSameInstantUsesItsLocalDateWithoutDependingOnDaylightSavingHours() {
        val instant = Instant.parse("2028-03-01T01:00:00Z").toEpochMilli()
        assertEquals(CalendarProgress(0, 31), calendarProgress(instant, ZoneId.of("Asia/Tokyo"), CalendarProgressMode.MONTH))
        assertEquals(CalendarProgress(28, 29), calendarProgress(instant, ZoneId.of("America/New_York"), CalendarProgressMode.MONTH))
        val dst = Instant.parse("2026-03-09T03:30:00Z").toEpochMilli()
        assertEquals(CalendarProgress(7, 31), calendarProgress(dst, ZoneId.of("America/New_York"), CalendarProgressMode.MONTH))
    }
}
