package com.jiligulu.app.ui.capture

import org.junit.Assert.*
import org.junit.Test

class GlassLensSamplingGeometryTest {
    private val region = GlassSampleRegion(
        roi = GlassRect(40, 70, 180, 220),
        excluded = GlassRect(80, 110, 143, 173),
        bubble = GlassRect(166, 226, 286, 346),
        scaleX = .5f,
        scaleY = .5f,
    )

    @Test fun screenPositionMapsIntoTheCaptureRoi() {
        assertEquals(43f, GlassLensSamplingGeometry.sourceX(region, 0f), .0001f)
        assertEquals(43f, GlassLensSamplingGeometry.sourceY(region, 0f), .0001f)
        assertEquals(73f, GlassLensSamplingGeometry.sourceX(region, 60f), .0001f)
        assertEquals(73f, GlassLensSamplingGeometry.sourceY(region, 60f), .0001f)
    }

    @Test fun reprojectionMovesTheTargetWithoutMovingOldCapturedPixelsOrItsMask() {
        val moved = region.copy(bubble = GlassRect(186, 218, 306, 338))
        assertEquals(10f, GlassLensSamplingGeometry.sourceX(moved, 60f) -
            GlassLensSamplingGeometry.sourceX(region, 60f), .0001f)
        assertEquals(-4f, GlassLensSamplingGeometry.sourceY(moved, 60f) -
            GlassLensSamplingGeometry.sourceY(region, 60f), .0001f)
        assertEquals(region.roi, moved.roi)
        assertEquals(region.excluded, moved.excluded)
        // Previously hidden pixels stay forbidden when the bubble moves away from them.
        assertFalse(GlassLensSamplingGeometry.isSafeSource(moved, 43f, 43f))
    }

    @Test fun nonUniformCaptureScalingUsesEachAxis() {
        val resized = region.copy(scaleX = .4f, scaleY = .6f)
        assertEquals(50.4f, GlassLensSamplingGeometry.sourceX(resized, 60f), .0001f)
        assertEquals(101.6f, GlassLensSamplingGeometry.sourceY(resized, 60f), .0001f)
    }

    @Test fun fourBoundaryAnchorsAreOutsideTheBilinearExclusionGuard() {
        val left = GlassLensSamplingGeometry.excludedLeft(region)
        val top = GlassLensSamplingGeometry.excludedTop(region)
        val right = GlassLensSamplingGeometry.excludedRight(region)
        val bottom = GlassLensSamplingGeometry.excludedBottom(region)
        val margin = GlassLensSamplingGeometry.RECONSTRUCTION_MARGIN
        assertTrue(margin > GlassLensSamplingGeometry.EXCLUSION_GUARD)
        assertTrue(GlassLensSamplingGeometry.isSafeSource(region, left - margin, 73f))
        assertTrue(GlassLensSamplingGeometry.isSafeSource(region, 73f, top - margin))
        assertTrue(GlassLensSamplingGeometry.isSafeSource(region, right + margin, 73f))
        assertTrue(GlassLensSamplingGeometry.isSafeSource(region, 73f, bottom + margin))
        assertFalse(GlassLensSamplingGeometry.isSafeSource(region, left - 1f, 73f))
        assertFalse(GlassLensSamplingGeometry.isSafeSource(region, right + 1f, 73f))
        assertFalse(GlassLensSamplingGeometry.isSafeSource(region, 73f, top - 1f))
        assertFalse(GlassLensSamplingGeometry.isSafeSource(region, 73f, bottom + 1f))
        assertFalse(GlassLensSamplingGeometry.isSafeSource(region, 73f, 73f))
    }

    @Test fun clippedScreenCornerUsesOnlyTheRemainingValidSides() {
        val corner = GlassSampleRegion(
            GlassRect(0, 0, 70, 70), GlassRect(-3, -3, 43, 43), GlassRect(0, 0, 80, 80), .5f, .5f,
        )
        val margin = GlassLensSamplingGeometry.RECONSTRUCTION_MARGIN
        assertFalse(GlassLensSamplingGeometry.isSafeSource(corner,
            GlassLensSamplingGeometry.excludedLeft(corner) - margin, 20f))
        assertFalse(GlassLensSamplingGeometry.isSafeSource(corner, 20f,
            GlassLensSamplingGeometry.excludedTop(corner) - margin))
        assertTrue(GlassLensSamplingGeometry.isSafeSource(corner,
            GlassLensSamplingGeometry.excludedRight(corner) + margin, 20f))
        assertTrue(GlassLensSamplingGeometry.isSafeSource(corner, 20f,
            GlassLensSamplingGeometry.excludedBottom(corner) + margin))
        assertFalse(GlassLensSamplingGeometry.isSafeSource(corner, 69f, 69f))
    }

    @Test fun aLargerMotionExclusionNeverShrinksBackToTheCurrentBubble() {
        val moving = region.copy(excluded = GlassRect(48, 80, 155, 188))
        assertEquals(8f, GlassLensSamplingGeometry.excludedLeft(moving), .0001f)
        assertEquals(10f, GlassLensSamplingGeometry.excludedTop(moving), .0001f)
        assertEquals(115f, GlassLensSamplingGeometry.excludedRight(moving), .0001f)
        assertEquals(118f, GlassLensSamplingGeometry.excludedBottom(moving), .0001f)
        // These points were outside the original bubble but covered during recent movement.
        assertTrue(GlassLensSamplingGeometry.isSafeSource(region, 20f, 73f))
        assertFalse(GlassLensSamplingGeometry.isSafeSource(moving, 20f, 73f))
        assertFalse(GlassLensSamplingGeometry.isSafeSource(moving, Float.NaN, 73f))
        assertFalse(GlassLensSamplingGeometry.isSafeSource(moving, 20f, Float.POSITIVE_INFINITY))
    }
}
