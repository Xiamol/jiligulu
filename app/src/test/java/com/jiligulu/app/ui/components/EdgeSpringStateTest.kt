package com.jiligulu.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeSpringStateTest {
    @Test fun handoffRequiresFreshGestureWithinScrollbarLingerAndSameDirection() {
        val state = EdgeSpringState()
        assertEquals(0, state.begin(100))
        state.finish(-1, 200)
        assertEquals(-1, state.begin(999))
        assertEquals(0, state.begin(1000))
        state.finish(1, 1100)
        assertEquals(1, state.begin(1200))
        state.finish(0, 1300)
        assertEquals(0, state.begin(1301))
    }
}
