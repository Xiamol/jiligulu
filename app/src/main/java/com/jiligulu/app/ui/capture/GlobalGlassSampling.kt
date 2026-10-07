package com.jiligulu.app.ui.capture

import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Pure geometry/pixel policy: no Android window or projection is created here. */
internal data class GlassRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
}

internal data class GlassSampleRegion(
    val roi: GlassRect, val excluded: GlassRect, val bubble: GlassRect,
    val scaleX: Float, val scaleY: Float,
) {
    val originX get() = bubble.left * scaleX - roi.left
    val originY get() = bubble.top * scaleY - roi.top
}

/** A reusable copy of the accepted ROI; the worker's input array is reused next frame. */
internal class GlassPixelHistory {
    private var region: GlassSampleRegion? = null
    private var previous = IntArray(0)

    fun changed(nextRegion: GlassSampleRegion, pixels: IntArray): Boolean {
        require(pixels.size == nextRegion.roi.width * nextRegion.roi.height)
        if (region == nextRegion && previous.contentEquals(pixels)) return false
        if (previous.size != pixels.size) previous = IntArray(pixels.size)
        pixels.copyInto(previous)
        region = nextRegion
        return true
    }

    fun clear() { region = null; previous.fill(0) }
    fun release() { clear(); previous = IntArray(0) }
}

internal object GlobalGlassSampling {
    const val FRAME_INTERVAL_MS = 84L // no more than 12 sampling requests per second
    const val SETTLE_MS = 300L
    const val FRAME_MAX_AGE_MS = 350L
    const val MAX_CAPTURE_EDGE = 1280

    /** No target or a held drag waits for an event, not a recurring sampling timer. */
    fun settleDelayMillis(hasTarget: Boolean, interacting: Boolean, changedAt: Long, now: Long): Long? {
        if (!hasTarget || interacting) return null
        return (SETTLE_MS - (now - changedAt).coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    fun captureSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = min(1f, MAX_CAPTURE_EDGE.toFloat() / max(width, height))
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }

    fun region(sourceWidth: Int, sourceHeight: Int, captureWidth: Int, captureHeight: Int, bubble: GlassRect): GlassSampleRegion? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || captureWidth <= 0 || captureHeight <= 0 || bubble.width <= 0 || bubble.height <= 0) return null
        val sx = captureWidth.toFloat() / sourceWidth
        val sy = captureHeight.toFloat() / sourceHeight
        // Exclude the complete rectangular window, including corner alpha, shadows and bilinear taps.
        val mask = GlassRect(floor(bubble.left * sx).toInt() - 3, floor(bubble.top * sy).toInt() - 3,
            ceil(bubble.right * sx).toInt() + 3, ceil(bubble.bottom * sy).toInt() + 3)
        val pad = max(12, ceil(max(bubble.width * sx, bubble.height * sy) * .4f).toInt())
        val roi = GlassRect((mask.left - pad).coerceAtLeast(0), (mask.top - pad).coerceAtLeast(0),
            (mask.right + pad).coerceAtMost(captureWidth), (mask.bottom + pad).coerceAtMost(captureHeight))
        if (roi.width <= 0 || roi.height <= 0) return null
        return GlassSampleRegion(roi, mask, bubble, sx, sy)
    }

    /** Reads only pixels outside the exclusion rectangle. Hidden pixels are never reconstructed. */
    fun copyRing(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, region: GlassSampleRegion, output: IntArray): Boolean {
        require(pixelStride >= 4 && rowStride > 0 && output.size >= region.roi.width * region.roi.height)
        val base = buffer.position()
        var nonBlack = false
        var index = 0
        for (y in region.roi.top until region.roi.bottom) for (x in region.roi.left until region.roi.right) {
            var color = 0
            if (!region.excluded.contains(x, y)) {
                val offset = base + y * rowStride + x * pixelStride
                if (offset >= base && offset + 3 < buffer.limit()) {
                    val r = buffer.get(offset).toInt() and 255
                    val g = buffer.get(offset + 1).toInt() and 255
                    val b = buffer.get(offset + 2).toInt() and 255
                    val a = buffer.get(offset + 3).toInt() and 255
                    if (a != 0) {
                        color = (255 shl 24) or (r shl 16) or (g shl 8) or b
                        nonBlack = nonBlack || r + g + b > 3
                    }
                }
            }
            output[index++] = color
        }
        return nonBlack // all-black/protected frames fall back to system blur; never keep old pixels
    }
}
