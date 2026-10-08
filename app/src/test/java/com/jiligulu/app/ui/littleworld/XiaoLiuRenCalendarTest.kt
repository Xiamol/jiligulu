package com.jiligulu.app.ui.littleworld

import android.app.Application
import java.time.LocalDate
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
}
