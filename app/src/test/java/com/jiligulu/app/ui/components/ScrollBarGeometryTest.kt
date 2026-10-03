package com.jiligulu.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollBarGeometryTest {
    @Test fun partialRowsProduceContinuousProgressForListsAndGrids() {
        val top = scrollBarGeometry(0f, 3f, 12, false)
        val moving = scrollBarGeometry(.5f, 3.5f, 12, false)
        assertEquals(.25f, top.visibleFraction, .0001f)
        assertEquals(0f, top.progress, .0001f)
        assertEquals(.5f / 9f, moving.progress, .0001f)
        assertTrue(moving.progress > top.progress)
    }

    @Test fun bottomIsAlignedEvenWhenItemHeightEstimateIsInexact() {
        val bottom = scrollBarGeometry(7.6f, 11.2f, 12, true)
        assertEquals(1f, bottom.progress, .0001f)
        assertTrue(bottom.visibleFraction in 0f..1f)
    }

    @Test fun EmptyAndChangingLayoutsDoNotCreateInvalidCanvasCoordinates() {
        listOf(scrollBarGeometry(0f, 0f, 0, false),
            scrollBarGeometry(Float.NaN, 2f, 3, false),
            scrollBarGeometry(-10f, 100f, 3, false)).forEach { value ->
            assertTrue(value.progress.isFinite())
            assertTrue(value.visibleFraction.isFinite())
            assertTrue(value.progress in 0f..1f)
            assertTrue(value.visibleFraction in 0f..1f)
        }
    }
}
