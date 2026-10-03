package com.jiligulu.app.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class TabScrubPositionTest {
    @Test fun fingerDistanceContinuouslyControlsAdjacentPages() {
        assertEquals(.25f, TabScrubPosition.fromPixels(0f, 50f, 200f), .0001f)
        assertEquals(.75f, TabScrubPosition.fromPixels(1f, -50f, 200f), .0001f)
        assertEquals(1f, TabScrubPosition.fromPixels(0f, 300f, 200f), .0001f)
        assertEquals(0f, TabScrubPosition.fromPixels(1f, -300f, 200f), .0001f)
    }

    @Test fun canonicalOffsetsReconstructTheSameContinuousPosition() {
        listOf(0f, .25f, .5f, .75f, 1f).forEach { progress ->
            val (page, offset) = TabScrubPosition.pageOffset(progress)
            assertEquals(progress, page + offset, .0001f)
        }
    }

    @Test fun releaseUsesDistanceOrADecisiveFling() {
        assertEquals(0, TabScrubPosition.settle(.35f, 0f))
        assertEquals(1, TabScrubPosition.settle(.7f, 0f))
        assertEquals(1, TabScrubPosition.settle(.15f, -900f))
        assertEquals(0, TabScrubPosition.settle(.85f, 900f))
    }
}
