package com.jiligulu.app.ui.littleworld

import android.app.Application
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class XiaoLiuRenCalendarTest {
    @Test fun lunarCalendarMatchesHkoNewYearAndLeapSixthMonthAndIsDeviceTimezoneIndependent() {
        // HKO 2024/2025 Gregorian–lunar calendar tables, not a copied conversion table.
        val first = XiaoLiuRenCalendar.forDate(LocalDate.of(2024, 2, 10), 1)
        assertEquals(1, first.lunarMonth); assertEquals(1, first.lunarDay); assertFalse(first.leapMonth)
        val leap = XiaoLiuRenCalendar.forDate(LocalDate.of(2025, 7, 25), 5)
        assertEquals(6, leap.lunarMonth); assertEquals(1, leap.lunarDay); assertTrue(leap.leapMonth)
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            assertEquals(first, XiaoLiuRenCalendar.forDate(first.date, first.shichen))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(leap, XiaoLiuRenCalendar.forDate(leap.date, leap.shichen))
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun castRetainsTheQuestionInstantTimezoneAndCivilDayAcrossBothHalvesOfZi() {
        val zone = ZoneId.of("Asia/Shanghai")
        val instant = Instant.parse("2024-02-10T15:30:00Z").toEpochMilli()
        val cast = XiaoLiuRenCalendar.cast("  明天面试怎样准备？  ", LiuRenMode.TIME, "ignored", instant, zone)
        assertEquals("明天面试怎样准备？", cast.question)
        assertEquals(instant, cast.capturedAtMillis)
        assertEquals(zone.id, cast.zoneId)
        assertEquals(listOf(1, 1, 1), cast.counts)
        assertEquals("", cast.digits)
        val later = XiaoLiuRenCalendar.cast(cast.question, LiuRenMode.TIME, "", instant + 60 * 60 * 1000, zone)
        assertEquals(listOf(1, 2, 1), later.counts)
        assertEquals(listOf(1, 1, 1), cast.counts) // Original question is never re-dated on revisit.
        assertEquals(LiuRenPalace.DA_AN, cast.result.hour)
    }
    @Test fun reportedNumbersKeepLeadingZeroAndNeverBorrowTheClockCounts() {
        val cast = XiaoLiuRenCalendar.cast("明天出门准备什么？", LiuRenMode.NUMBERS, "012",
            Instant.parse("2025-07-25T06:00:00Z").toEpochMilli(), ZoneId.of("Asia/Shanghai"))
        assertTrue(cast.leapMonth)
        assertEquals("012", cast.digits)
        assertEquals(listOf(10, 1, 2), cast.counts)
        assertEquals(LiuRenPalace.XIAO_JI, cast.result.hour)
        assertTrue(runCatching { cast.copy(zoneId = "not/a/zone").checked() }.isFailure)
        assertTrue(runCatching { cast.copy(digits = "12").checked() }.isFailure)
    }
}
