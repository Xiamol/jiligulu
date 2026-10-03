package com.jiligulu.app.ui.littleworld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WishAmountTest {
    @Test fun moneyKeepsExactCents() {
        assertEquals(9901L, exactWishAmount("99.01"))
        assertEquals(100L, exactWishAmount("1.00"))
        assertEquals(1L, exactWishAmount("0.01"))
        assertNull(exactWishAmount("9.999"))
    }

    @Test fun optionalWaitingPriceIsOnlyZeroWhenAbsent() {
        assertEquals(0L, exactWishAmount("", optional = true))
        assertNull(exactWishAmount("0", optional = true))
        assertNull(exactWishAmount("-1", optional = true))
        assertNull(exactWishAmount(""))
    }

    @Test fun hugeAndBrokenValuesCannotBecomeProgress() {
        assertEquals(99_999_999_999L, exactWishAmount("999999999.99"))
        assertNull(exactWishAmount("1000000000"))
        assertNull(exactWishAmount("99999999999999999999999"))
        assertNull(exactWishAmount("12+4"))
        assertNull(exactWishAmount("NaN"))
    }
}
