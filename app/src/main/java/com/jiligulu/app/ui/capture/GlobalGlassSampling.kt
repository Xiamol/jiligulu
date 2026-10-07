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
    fun union(other: GlassRect) = GlassRect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
}

internal data class GlassBoundsAt(val bounds: GlassRect, val atNanos: Long)
internal data class GlassSamplingTarget(val bubble: GlassRect, val unsafeBounds: GlassRect)
internal data class GlassMotionSnapshot(val positions: List<GlassBoundsAt>, val truncated: Boolean) {
    fun forFrame(frameNanos: Long): GlassSamplingTarget? {
        val current = positions.lastOrNull()?.bounds ?: return null
        // A frame older than retained motion cannot establish where our own window was.
        if (truncated && frameNanos > 0 && frameNanos < positions.first().atNanos) return null
        val start = if (frameNanos <= 0) 0 else {
            val cutoff = frameNanos - 50_000_000L // include compositor/layout lag, not just the latest coordinate
            positions.indexOfLast { it.atNanos <= cutoff }.coerceAtLeast(0)
        }
        val unsafe = positions.drop(start).fold(current) { total, item -> total.union(item.bounds) }
        return GlassSamplingTarget(current, unsafe)
    }
}

/** Immutable snapshots cross the worker boundary; no View is inspected from that thread. */
internal class GlassMotionHistory {
    private val positions = ArrayDeque<GlassBoundsAt>()
    private var truncated = false
    fun record(bounds: GlassRect, nowNanos: Long): GlassMotionSnapshot {
        if (positions.lastOrNull()?.bounds != bounds) positions.addLast(GlassBoundsAt(bounds, nowNanos))
        while (positions.size > 64) { positions.removeFirst(); truncated = true }
        return GlassMotionSnapshot(positions.toList(), truncated)
    }
    fun clear() { positions.clear(); truncated = false }
}

/** Persistent streams keep only their latest frame; the cadence never needs another timer. */
internal class GlassFrameCadence {
    private var interval = 0L
    private var nextAt = Long.MIN_VALUE
    fun accepts(nowNanos: Long, framesPerSecond: Int): Boolean {
        val nextInterval = 1_000_000_000L / framesPerSecond.coerceIn(1, 120)
        if (nextInterval != interval || nextAt == Long.MIN_VALUE) {
            interval = nextInterval; nextAt = nowNanos + interval; return true
        }
        if (nowNanos + 250_000L < nextAt) return false // small vsync jitter needn't halve a 120 target
        nextAt = if (nowNanos - nextAt > interval) nowNanos + interval else nextAt + interval
        return true
    }
    fun reset() { interval = 0; nextAt = Long.MIN_VALUE }
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
    const val FRAME_MAX_AGE_MS = 350L
    const val MAX_CAPTURE_EDGE = 1280


    fun captureSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = min(1f, MAX_CAPTURE_EDGE.toFloat() / max(width, height))
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }

    fun region(sourceWidth: Int, sourceHeight: Int, captureWidth: Int, captureHeight: Int, bubble: GlassRect,
        unsafeBounds: GlassRect = bubble): GlassSampleRegion? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || captureWidth <= 0 || captureHeight <= 0 || bubble.width <= 0 || bubble.height <= 0) return null
        val sx = captureWidth.toFloat() / sourceWidth
        val sy = captureHeight.toFloat() / sourceHeight
        // Exclude the complete rectangular window, including corner alpha, shadows and bilinear taps.
        val unsafe = unsafeBounds.union(bubble)
        val mask = GlassRect(floor(unsafe.left * sx).toInt() - 3, floor(unsafe.top * sy).toInt() - 3,
            ceil(unsafe.right * sx).toInt() + 3, ceil(unsafe.bottom * sy).toInt() + 3)
        val pad = max(12, ceil(max(bubble.width * sx, bubble.height * sy) * .4f).toInt())
        // Sample only the current neighbourhood, not the whole history's drag path.
        val roiWidth = min(captureWidth, ceil(bubble.width * sx).toInt() + 6 + pad * 2)
        val roiHeight = min(captureHeight, ceil(bubble.height * sy).toInt() + 6 + pad * 2)
        if (bubble.right <= 0 || bubble.bottom <= 0 || bubble.left >= sourceWidth || bubble.top >= sourceHeight) return null
        val left = (floor(bubble.left * sx).toInt() - 3 - pad).coerceIn(0, captureWidth - roiWidth)
        val top = (floor(bubble.top * sy).toInt() - 3 - pad).coerceIn(0, captureHeight - roiHeight)
        val roi = GlassRect(left, top, left + roiWidth, top + roiHeight)
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
