package com.jiligulu.app.ui.capture

import com.jiligulu.app.data.prefs.FloatingCapturePosition
import kotlin.math.roundToInt

/** Absolute screen frame, excluding status/navigation bars and a display cutout. */
internal data class FloatingCaptureBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
internal data class FloatingCapturePixels(val x: Int, val y: Int)

internal object FloatingCaptureGeometry {
    fun clamp(x: Int, y: Int, side: Int, bounds: FloatingCaptureBounds): FloatingCapturePixels =
        FloatingCapturePixels(x.coerceIn(bounds.left, (bounds.right - side).coerceAtLeast(bounds.left)),
            y.coerceIn(bounds.top, (bounds.bottom - side).coerceAtLeast(bounds.top)))

    fun normalize(x: Int, y: Int, side: Int, bounds: FloatingCaptureBounds): FloatingCapturePosition {
        val clamped = clamp(x, y, side, bounds)
        val rangeX = (bounds.right - bounds.left - side).coerceAtLeast(0)
        val rangeY = (bounds.bottom - bounds.top - side).coerceAtLeast(0)
        return FloatingCapturePosition(if (rangeX == 0) 0f else (clamped.x - bounds.left).toFloat() / rangeX,
            if (rangeY == 0) 0f else (clamped.y - bounds.top).toFloat() / rangeY)
    }

    fun restore(position: FloatingCapturePosition, side: Int, bounds: FloatingCaptureBounds): FloatingCapturePixels {
        val rangeX = (bounds.right - bounds.left - side).coerceAtLeast(0)
        val rangeY = (bounds.bottom - bounds.top - side).coerceAtLeast(0)
        val x = position.x.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val y = position.y.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        return clamp(bounds.left + (x * rangeX).roundToInt(), bounds.top + (y * rangeY).roundToInt(), side, bounds)
    }

    fun positionAfterDrop(start: FloatingCapturePosition, end: FloatingCapturePosition,
        droppedToHide: Boolean): FloatingCapturePosition = if (droppedToHide) start else end
}
