package com.jiligulu.app.ui.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingCaptureGeometryTest {
    @Test fun normalizedPlacementKeepsItsEdgeAndHeightAcrossRotationAndIconResize() {
        val portrait = FloatingCaptureBounds(0, 80, 1080, 2320)
        val start = FloatingCaptureGeometry.normalize(960, 610, 120, portrait)
        assertEquals(1f, start.x, .0001f)
        assertEquals(.25f, start.y, .0001f)
        val landscape = FloatingCaptureBounds(60, 0, 2320, 1080)
        val restored = FloatingCaptureGeometry.restore(start, 168, landscape)
        assertEquals(2152, restored.x)
        assertEquals(228, restored.y)
        val resized = FloatingCaptureGeometry.normalize(restored.x, restored.y, 168, landscape)
        assertEquals(start.x, resized.x, .0001f)
        assertEquals(start.y, resized.y, .0001f)
        // A tiny/zero travel range stays within the safe origin instead of dividing by zero.
        assertEquals(FloatingCapturePixels(10, 20),
            FloatingCaptureGeometry.restore(start, 200, FloatingCaptureBounds(10, 20, 50, 60)))
    }
}
