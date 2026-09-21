package com.jiligulu.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormattersTest {
    @Test fun `money beyond the supported range never wraps into a different bill`() {
        assertNull(Formatters.yuanTextToFen("92233720368547758.08"))
        assertNull(Formatters.yuanTextToFen("184467440737095516.17"))
        assertEquals(Long.MAX_VALUE, Formatters.yuanTextToFen("92233720368547758.07"))
    }

    @Test fun `normal decimal entry remains exact and invalid values are rejected`() {
        assertEquals(900L, Formatters.yuanTextToFen("9"))
        assertEquals(950L, Formatters.yuanTextToFen("9.50"))
        assertNull(Formatters.yuanTextToFen("0"))
        assertNull(Formatters.yuanTextToFen("-9"))
        assertNull(Formatters.yuanTextToFen("早餐"))
    }
}
