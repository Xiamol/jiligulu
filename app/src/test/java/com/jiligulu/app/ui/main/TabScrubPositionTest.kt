package com.jiligulu.app.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class TabScrubPositionTest {
    @Test fun fingerDistanceContinuouslyControlsAdjacentPages() {
        assertEquals(.25f, TabScrubPosition.fromPixels(0f, 50f, 200f), .0001f)
        assertEquals(.75f, TabScrubPosition.fromPixels(1f, -50f, 200f), .0001f)
        assertEquals(2f, TabScrubPosition.fromPixels(0f, 500f, 200f), .0001f)
        assertEquals(0f, TabScrubPosition.fromPixels(2f, -500f, 200f), .0001f)
    }

    @Test fun releaseUsesDistanceOrADecisiveFling() {
        assertEquals(0, TabScrubPosition.settle(.35f, 0f))
        assertEquals(1, TabScrubPosition.settle(.7f, 0f))
        assertEquals(2, TabScrubPosition.settle(1.7f, 0f))
        assertEquals(1, TabScrubPosition.settle(.15f, -900f))
        assertEquals(2, TabScrubPosition.settle(1.15f, -900f))
        assertEquals(1, TabScrubPosition.settle(1.85f, 900f))
        assertEquals(0, TabScrubPosition.settle(.85f, 900f))
        assertEquals(2, TabScrubPosition.settle(2f, -900f))
        assertEquals(0, TabScrubPosition.settle(0f, 900f))
    }

    @Test fun directDragIncludesMovementBeforeSlopAndPreservesThumbGrab() {
        val width = 600f
        val grab = TabScrubPosition.grabOffset(120f, 0f, width, 70f)
        assertEquals(20f, grab, .0001f)
        assertEquals(0f, TabScrubPosition.fromTrack(120f, width, grab), .0001f)
        assertEquals(.1f, TabScrubPosition.fromTrack(140f, width, grab), .0001f)
        assertEquals(true, TabScrubPosition.dragged(20f, 2f, 8f))
        assertEquals(false, TabScrubPosition.dragged(6f, 2f, 8f))
    }

    @Test fun threeEqualTabsShareCentersAcrossThumbTouchAndPageProgress() {
        assertEquals(100f, TabScrubPosition.center(0f, 600f), .0001f)
        assertEquals(300f, TabScrubPosition.center(1f, 600f), .0001f)
        assertEquals(500f, TabScrubPosition.center(2f, 600f), .0001f)
        assertEquals(0f, TabScrubPosition.fromTrack(100f, 600f, 0f), .0001f)
        assertEquals(1f, TabScrubPosition.fromTrack(300f, 600f, 0f), .0001f)
        assertEquals(2f, TabScrubPosition.fromTrack(500f, 600f, 0f), .0001f)
        assertEquals(0, TabScrubPosition.tappedTab(50f, 600f))
        assertEquals(1, TabScrubPosition.tappedTab(300f, 600f))
        assertEquals(2, TabScrubPosition.tappedTab(550f, 600f))
    }

}
