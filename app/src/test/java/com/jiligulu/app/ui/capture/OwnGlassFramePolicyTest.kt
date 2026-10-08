package com.jiligulu.app.ui.capture

import org.junit.Assert.*
import org.junit.Test

class OwnGlassFramePolicyTest {
    @Test fun freshFramesRemainUsableAtBothSixtyAndOneTwentyTargets() {
        val ticket=OwnGlassFrameTicket(3,100,1000)
        assertTrue(OwnGlassFramePolicy.isFresh(ticket,3,101,1008,120))
        assertTrue(OwnGlassFramePolicy.isFresh(ticket,3,102,1016,120))
        assertTrue(OwnGlassFramePolicy.isFresh(ticket,3,101,1016,60))
        assertTrue(OwnGlassFramePolicy.isFresh(ticket,3,102,1032,60))
    }
    @Test fun staleResultsFromQueuedComparisonOrSourceSwitchAreNeverRebound() {
        val ticket=OwnGlassFrameTicket(3,100,1000)
        assertFalse(OwnGlassFramePolicy.isFresh(ticket,3,103,1008,120))
        assertFalse(OwnGlassFramePolicy.isFresh(ticket,3,101,1024,120))
        assertFalse(OwnGlassFramePolicy.isFresh(ticket,3,102,1040,60))
        assertFalse(OwnGlassFramePolicy.isFresh(ticket,4,100,1002,120))
        assertFalse(OwnGlassFramePolicy.isFresh(ticket,3,99,1002,120))
    }
    @Test fun aStaticSourceKeepsItsTextureWithoutAPeriodicTimer() {
        val ticket=OwnGlassFrameTicket(3,100,1000)
        assertTrue(OwnGlassFramePolicy.isFresh(ticket,3,100,3_600_000,120))
        assertFalse(OwnGlassFramePolicy.isFresh(ticket,3,101,3_600_000,120))
    }
}
