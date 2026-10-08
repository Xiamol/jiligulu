package com.jiligulu.app.ui.capture

import org.junit.Assert.*
import org.junit.Test

class OwnGlassFramePolicyTest {
    @Test fun startingToScrollAfterAnHourOfIdleKeepsTheMaterialWhileTheFirstCopyArrives() {
        val idle=OwnGlassFrameTicket(3,100,1000)
        assertTrue(OwnGlassFramePolicy.canDisplay(idle,3,101,3_600_010,15,3_600_000))
        assertFalse(OwnGlassFramePolicy.canDisplay(idle,3,101,3_600_202,15,3_600_000))
    }
    @Test fun continuouslyScrollingAt120HzDoesNotDropMaterialBetween15HzSamples() {
        var shown = OwnGlassFrameTicket(3, 0, 0, 12)
        var nextSample = 67L
        for (now in 16L..1000L step 8) {
            val currentFrame = now / 8
            if (now >= nextSample + 12) {
                val started = nextSample
                val sample = OwnGlassFrameTicket(3, started / 8, started)
                assertTrue(OwnGlassFramePolicy.acceptsResult(sample, 3, currentFrame, now))
                shown = sample.copy(completedAtMillis = now)
                nextSample += 67
            }
            assertTrue("Material disappeared at $now ms", OwnGlassFramePolicy.canDisplay(shown,3,currentFrame,now,15))
        }
    }
    @Test fun pageFramesDuringCopyAreAllowedButOldWindowAndExcessiveLatencyAreRejected() {
        val ticket=OwnGlassFrameTicket(3,100,1000)
        assertTrue(OwnGlassFramePolicy.acceptsResult(ticket,3,115,1060))
        assertFalse(OwnGlassFramePolicy.acceptsResult(ticket,4,115,1060))
        assertFalse(OwnGlassFramePolicy.acceptsResult(ticket,3,99,1002))
        assertFalse(OwnGlassFramePolicy.acceptsResult(ticket,3,140,1251))
    }
    @Test fun aStalledChangingSourceEventuallyFallsBackInsteadOfKeepingAnOldImageForever() {
        val ticket=OwnGlassFrameTicket(3,100,1000,1020)
        assertTrue(OwnGlassFramePolicy.canDisplay(ticket,3,125,1080,120))
        assertFalse(OwnGlassFramePolicy.canDisplay(ticket,3,125,1121,120))
        assertFalse(OwnGlassFramePolicy.canDisplay(ticket,4,100,1021,15))
        assertFalse(OwnGlassFramePolicy.canDisplay(ticket,3,99,1021,15))
    }
    @Test fun aStaticSourceKeepsItsTextureWithoutAPeriodicTimer() {
        val ticket=OwnGlassFrameTicket(3,100,1000)
        assertTrue(OwnGlassFramePolicy.canDisplay(ticket,3,100,3_600_000,120))
        assertFalse(OwnGlassFramePolicy.canDisplay(ticket,3,101,3_600_000,120))
        assertTrue(OwnGlassFramePolicy.acceptsResult(ticket,3,100,1500))
    }
}
