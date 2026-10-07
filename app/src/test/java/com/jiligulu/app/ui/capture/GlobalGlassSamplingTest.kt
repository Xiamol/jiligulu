package com.jiligulu.app.ui.capture

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class GlobalGlassSamplingTest {
    @Test fun latestFrameCadenceHonorsAllTargetsAndChangingRateAppliesImmediately() {
        for(fps in listOf(15,30,60,120)) {
            val cadence=GlassFrameCadence()
            val accepted=(0L until 1_000_000_000L step 1_000_000L).count { cadence.accepts(it,fps) }
            assertTrue("target=$fps actual=$accepted", accepted in fps..fps+1)
        }
        val cadence=GlassFrameCadence()
        assertTrue(cadence.accepts(0,15));assertFalse(cadence.accepts(1_000_000,15))
        assertTrue(cadence.accepts(1_000_000,120))
        cadence.reset();assertTrue(cadence.accepts(1_000_000,30))
    }

    @Test fun movingOverlayMasksCapturedAndLaterCoordinatesWithoutWaitingForRelease() {
        val history=GlassMotionHistory()
        history.record(GlassRect(10,10,30,30),1_000_000_000)
        val snapshot=history.record(GlassRect(25,10,45,30),1_010_000_000)
        val target=requireNotNull(snapshot.forFrame(1_005_000_000))
        assertEquals(GlassRect(25,10,45,30),target.bubble)
        assertEquals(GlassRect(10,10,45,30),target.unsafeBounds)
        history.clear()
        val restored=requireNotNull(history.record(GlassRect(70,70,90,90),2_000_000_000).forFrame(0))
        assertEquals(GlassRect(70,70,90,90),restored.unsafeBounds)
    }

    @Test fun sameSizeDraggedRoiKeepsBufferDimensionsEvenAtScreenEdges() {
        val sizes=(0..936).map {x ->
            val region=requireNotNull(GlobalGlassSampling.region(1080,2400,576,1280,GlassRect(x,2,x+144,146)))
            region.roi.width to region.roi.height
        }.toSet()
        assertEquals(1,sizes.size)
    }

    @Test fun anOutdatedFrameCannotUseMotionHistoryThatWasAlreadyTruncated() {
        val history=GlassMotionHistory()
        var snapshot=history.record(GlassRect(0,0,20,20),1)
        for(i in 1..100) snapshot=history.record(GlassRect(i,0,i+20,20),i*1_000_000L)
        assertNull(snapshot.forFrame(1))
        assertNotNull(snapshot.forFrame(100_000_000))
    }

    @Test fun identicalGlobalRingsDoNotRequestAnotherUploadButChangedPixelsDo() {
        val region = requireNotNull(GlobalGlassSampling.region(100,100,100,100,GlassRect(30,30,60,60)))
        val pixels = IntArray(region.roi.width * region.roi.height) { 0xff336699.toInt() }
        val history = GlassPixelHistory()
        assertTrue(history.changed(region, pixels))
        assertFalse(history.changed(region, pixels))
        // The capture worker reuses this array; retaining its reference would miss changes.
        pixels[0] = 0xff669933.toInt()
        assertTrue(history.changed(region, pixels))
        assertFalse(history.changed(region, pixels))
    }

    @Test fun geometryAndSessionChangesAlwaysPublishEvenWhenPixelsMatch() {
        val first = requireNotNull(GlobalGlassSampling.region(100,100,100,100,GlassRect(30,30,60,60)))
        val shifted = first.copy(bubble = GlassRect(31,30,61,60))
        val pixels = IntArray(first.roi.width * first.roi.height) { 0xff123456.toInt() }
        val history = GlassPixelHistory()
        assertTrue(history.changed(first, pixels))
        assertTrue(history.changed(shifted, pixels))
        history.clear()
        assertTrue(history.changed(shifted, pixels))
        history.release()
        assertTrue(history.changed(shifted, pixels))
    }

    @Test fun projectionIsDownscaledWithAnAspectPreservingBound() {
        assertEquals(576 to 1280, GlobalGlassSampling.captureSize(1080, 2400))
        assertEquals(1280 to 576, GlobalGlassSampling.captureSize(2400, 1080))
        assertEquals(720 to 1280, GlobalGlassSampling.captureSize(720, 1280))
    }

    @Test fun everyPixelOfTheFloatingWindowIncludingTransparentCornersIsExcluded() {
        val bubble = GlassRect(35, 41, 91, 97)
        val region = requireNotNull(GlobalGlassSampling.region(200, 200, 100, 100, bubble))
        val pixels = IntArray(region.roi.width * region.roi.height) { 0x12345678 }
        val frame = rgba(100, 100, 416) { x, y ->
            if (region.excluded.contains(x, y)) 0xffff00ff.toInt() else 0xff336699.toInt()
        }
        assertTrue(GlobalGlassSampling.copyRing(frame, 416, 4, region, pixels))
        for (y in region.roi.top until region.roi.bottom) for (x in region.roi.left until region.roi.right) {
            val color = pixels[(y-region.roi.top)*region.roi.width+x-region.roi.left]
            assertEquals(if (region.excluded.contains(x,y)) 0 else 0xff336699.toInt(), color)
        }
        assertFalse(pixels.contains(0xffff00ff.toInt()))
        assertTrue(region.excluded.left < bubble.left * region.scaleX)
        assertTrue(region.excluded.right > bubble.right * region.scaleX)
    }

    @Test fun allFourScreenEdgesClipTheRoiWithoutWrappingOrReadingHiddenPixels() {
        for (bubble in listOf(GlassRect(0,0,40,40),GlassRect(160,0,200,40),
            GlassRect(0,160,40,200),GlassRect(160,160,200,200))) {
            val region = requireNotNull(GlobalGlassSampling.region(200,200,100,100,bubble))
            assertTrue(region.roi.left >= 0 && region.roi.top >= 0 && region.roi.right <= 100 && region.roi.bottom <= 100)
            val pixels = IntArray(region.roi.width * region.roi.height)
            assertTrue(GlobalGlassSampling.copyRing(rgba(100,100,400) {_,_->0xff008800.toInt()},400,4,region,pixels))
            assertTrue(pixels.any {it==0})
            assertTrue(pixels.all {it==0 || it==0xff008800.toInt()})
        }
    }

    @Test fun liveOutsideColorsUpdateWhileMaskedCenterStaysEmpty() {
        val region = requireNotNull(GlobalGlassSampling.region(100,100,100,100,GlassRect(30,30,60,60)))
        val pixels = IntArray(region.roi.width * region.roi.height)
        GlobalGlassSampling.copyRing(rgba(100,100,400){_,_->0xff22bb44.toInt()},400,4,region,pixels)
        assertTrue(pixels.contains(0xff22bb44.toInt()))
        GlobalGlassSampling.copyRing(rgba(100,100,400){_,_->0xff884422.toInt()},400,4,region,pixels)
        assertFalse(pixels.contains(0xff22bb44.toInt()))
        assertTrue(pixels.contains(0xff884422.toInt()))
        assertEquals(0,pixels[(45-region.roi.top)*region.roi.width+45-region.roi.left])
    }

    @Test fun protectedBlackFrameDoesNotReusePreviousPixels() {
        val region = requireNotNull(GlobalGlassSampling.region(100,100,100,100,GlassRect(30,30,60,60)))
        val pixels = IntArray(region.roi.width * region.roi.height) {0xffabcdef.toInt()}
        assertFalse(GlobalGlassSampling.copyRing(rgba(100,100,400){_,_->0xff000000.toInt()},400,4,region,pixels))
        assertFalse(pixels.contains(0xffabcdef.toInt()))
        assertTrue(pixels.all {it==0 || it==0xff000000.toInt()})
    }

    @Test fun emptyAndOutsideTargetsDoNotProduceAFakeBackdrop() {
        assertNull(GlobalGlassSampling.region(100,100,50,50,GlassRect(30,30,30,40)))
        assertNull(GlobalGlassSampling.region(100,100,50,50,GlassRect(300,300,340,340)))
        assertNull(GlobalGlassSampling.region(0,100,50,50,GlassRect(1,1,4,4)))
    }

    private fun rgba(width:Int,height:Int,stride:Int,color:(Int,Int)->Int):ByteBuffer {
        val buffer=ByteBuffer.allocate(stride*height)
        for(y in 0 until height) for(x in 0 until width) {
            val value=color(x,y);val at=y*stride+x*4
            buffer.put(at,(value ushr 16).toByte());buffer.put(at+1,(value ushr 8).toByte())
            buffer.put(at+2,value.toByte());buffer.put(at+3,(value ushr 24).toByte())
        }
        return buffer
    }
}
