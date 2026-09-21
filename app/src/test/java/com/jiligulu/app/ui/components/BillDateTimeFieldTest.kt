package com.jiligulu.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.ZoneId

class BillDateTimeFieldTest {
    private val taipei = ZoneId.of("Asia/Taipei")

    @Test
    fun `changing to a leap day keeps local time without UTC day drift`() {
        val original = ZonedDateTime.of(2026, 9, 21, 0, 15, 32, 0, taipei)
        val edited = withBillDate(original.toInstant().toEpochMilli(), LocalDate.of(2024, 2, 29), taipei)
        assertEquals(ZonedDateTime.of(2024, 2, 29, 0, 15, 32, 0, taipei).toInstant().toEpochMilli(), edited)
    }

    @Test
    fun `changing time to midnight preserves chosen date and removes seconds`() {
        val original = ZonedDateTime.of(2026, 12, 31, 23, 59, 52, 12_000_000, taipei)
        val edited = withBillTime(original.toInstant().toEpochMilli(), 0, 0, taipei)
        assertEquals(ZonedDateTime.of(2026, 12, 31, 0, 0, 0, 0, taipei).toInstant().toEpochMilli(), edited)
    }

    @Test
    fun `changing date across year keeps the users timezone`() {
        val zone = ZoneId.of("America/New_York")
        val original = ZonedDateTime.of(2026, 7, 1, 18, 30, 0, 0, zone)
        val edited = withBillDate(original.toInstant().toEpochMilli(), LocalDate.of(2025, 12, 31), zone)
        assertEquals(ZonedDateTime.of(2025, 12, 31, 18, 30, 0, 0, zone).toInstant().toEpochMilli(), edited)
    }

    @Test
    fun `AI historical and future dates remain inside date picker year range`() {
        assertTrue(1899 in billDatePickerYearRange(1899))
        assertTrue(2101 in billDatePickerYearRange(2101))
        assertTrue(2026 in billDatePickerYearRange(2026))
        assertEquals(1900..2100, billDatePickerYearRange(Int.MAX_VALUE))
        assertEquals(1900..2100, billDatePickerYearRange(0))
    }
}
