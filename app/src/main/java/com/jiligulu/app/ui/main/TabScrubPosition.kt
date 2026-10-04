package com.jiligulu.app.ui.main

import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

internal const val MainPageCount = 3

/** One continuous coordinate shared by the content pager and bottom navigation thumb. */
internal object TabScrubPosition {
    fun center(progress: Float, width: Float): Float = width / MainPageCount * (.5f + progress.coerceIn(0f, (MainPageCount - 1).toFloat()))

    fun grabOffset(pointer: Float, progress: Float, width: Float, thumbWidth: Float): Float {
        val distance = pointer - center(progress, width)
        return if (abs(distance) <= thumbWidth / 2f) distance else 0f
    }

    /** Map the actual finger to tab centers, keeping an off-center grab of the thumb stable. */
    fun fromTrack(pointer: Float, width: Float, grabOffset: Float): Float =
        fromPixels(0f, pointer - grabOffset - width / (MainPageCount * 2f), width / MainPageCount)

    fun dragged(horizontal: Float, vertical: Float, slop: Float): Boolean =
        abs(horizontal) > slop && abs(horizontal) >= abs(vertical)

    fun tappedTab(pointer: Float, width: Float): Int =
        if (width <= 0f) 0 else (pointer / width * MainPageCount).toInt().coerceIn(0, MainPageCount - 1)

    fun fromPixels(start: Float, delta: Float, travel: Float): Float =
        (start + if (travel > 0f) delta / travel else 0f).coerceIn(0f, (MainPageCount - 1).toFloat())

    /** Content velocity points opposite the destination index; a decisive fling wins. */
    fun settle(progress: Float, contentVelocity: Float): Int {
        val position = progress.coerceIn(0f, (MainPageCount - 1).toFloat())
        return when {
            contentVelocity < -700f -> ceil(position + .001f).toInt()
            contentVelocity > 700f -> floor(position - .001f).toInt()
            else -> position.roundToInt()
        }.coerceIn(0, MainPageCount - 1)
    }
}
