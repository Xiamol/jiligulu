package com.jiligulu.app.ui.littleworld

import org.junit.Assert.assertEquals
import org.junit.Test

class RoomNegotiationDeadlineTest {
    @Test fun backgroundTimeAndACompletelyFrozenLooperDoNotConsumeConsentTime() {
        val clock = RoomNegotiationDeadline(20_000, 0, false)
        assertEquals(15_000L, clock.tick(5_000, false))
        assertEquals(15_000L, clock.tick(6_000, true))
        assertEquals(15_000L, clock.tick(131_000, false))
        assertEquals(1L, clock.tick(145_999, false))
        assertEquals(0L, clock.tick(146_000, false))
    }

    @Test fun initialSuspensionAndRepeatedQuietTransitionsKeepTheBoundedActiveTime() {
        val clock = RoomNegotiationDeadline(25_000, 0, true)
        assertEquals(25_000L, clock.tick(200_000, true))
        assertEquals(25_000L, clock.tick(201_000, false))
        assertEquals(20_000L, clock.tick(206_000, false))
        assertEquals(20_000L, clock.tick(207_000, true))
        assertEquals(20_000L, clock.tick(250_000, false))
        assertEquals(0L, clock.tick(270_000, false))
    }
}
