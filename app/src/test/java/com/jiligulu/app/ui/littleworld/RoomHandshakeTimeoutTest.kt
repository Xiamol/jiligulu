package com.jiligulu.app.ui.littleworld

import org.junit.Assert.*
import org.junit.Test

class RoomHandshakeTimeoutTest {
    @Test fun absentTransportExpiresAtTheConnectionDeadline() {
        val clock = RoomHandshakeTimeout()
        clock.begin(1_000, 35_000)
        assertEquals(35_000, clock.remaining(1_000))
        assertFalse(clock.expired(35_999))
        assertTrue(clock.expired(36_000))
    }

    @Test fun guestGetsAnIndependentEightSecondBoardDeadlineAfterLinkConnects() {
        val clock = RoomHandshakeTimeout()
        clock.begin(0, 35_000)
        clock.linkReady(30_000, hosting = false)
        assertTrue(clock.waitingForState)
        assertEquals(8_000, clock.remaining(30_000))
        assertFalse(clock.expired(37_999))
        assertTrue(clock.expired(38_000))
    }

    @Test fun ongoingLinkTrafficDoesNotExtendOrCompleteTheBoardDeadline() {
        val clock = RoomHandshakeTimeout()
        clock.begin(0, 35_000)
        clock.linkReady(1_000, hosting = false)
        // Duplicate connection callbacks and periodic packet/heartbeat activity cannot restart it.
        for (trafficTime in listOf(2_000L, 5_000L, 8_999L)) {
            clock.linkReady(trafficTime, hosting = false)
            assertTrue(clock.waitingForState)
            assertEquals(9_000 - trafficTime, clock.remaining(trafficTime))
        }
        assertTrue(clock.expired(9_000))
    }

    @Test fun validatedInitialStateEndsGuestSynchronization() {
        val clock = RoomHandshakeTimeout()
        clock.begin(0, 35_000)
        clock.linkReady(5_000, hosting = false)
        clock.validatedState()
        assertFalse(clock.active)
        assertFalse(clock.expired(100_000))
        assertEquals(0, clock.remaining(100_000))
    }

    @Test fun hostsAlreadyOwnTheirInitialBoardWhenThePeerConnects() {
        val clock = RoomHandshakeTimeout()
        clock.begin(0, 120_000)
        clock.linkReady(100_000, hosting = true)
        assertFalse(clock.active)
        assertFalse(clock.waitingForState)
        assertFalse(clock.expired(400_000))
    }

    @Test fun stoppingAndStartingAnotherRoomDropsThePreviousDeadline() {
        val clock = RoomHandshakeTimeout()
        clock.begin(0, 35_000); clock.linkReady(1_000, hosting = false)
        clock.close()
        assertFalse(clock.expired(40_000))
        clock.begin(40_000, 120_000)
        assertEquals(120_000, clock.remaining(40_000))
        assertFalse(clock.waitingForState)
        assertFalse(clock.expired(159_999))
        assertTrue(clock.expired(160_000))
    }
}
