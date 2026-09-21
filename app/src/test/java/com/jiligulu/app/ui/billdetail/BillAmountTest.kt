package com.jiligulu.app.ui.billdetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillAmountTest {
    @Test
    fun `cent precision stays exact`() {
        assertEquals(9L, parseBillAmount("0.09"))
        assertEquals(990L, parseBillAmount(" 9.90 "))
        assertEquals(99999999999999L, parseBillAmount("999999999999.99"))
    }

    @Test
    fun `invalid amounts never round wrap or change sign`() {
        listOf("", "-1", "0", "0.00", "0.001", "1.234", "1e10", "NaN", "92233720368547758.08")
            .forEach { assertNull(it, parseBillAmount(it)) }
    }
}
