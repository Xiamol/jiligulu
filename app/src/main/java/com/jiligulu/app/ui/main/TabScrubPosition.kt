package com.jiligulu.app.ui.main

import kotlin.math.roundToInt

/** One continuous coordinate shared by the content pager and bottom navigation thumb. */
internal object TabScrubPosition {
    fun fromPixels(start: Float, delta: Float, travel: Float): Float =
        (start + if (travel > 0f) delta / travel else 0f).coerceIn(0f, 1f)

    fun pageOffset(progress: Float): Pair<Int, Float> {
        val clamped = progress.coerceIn(0f, 1f)
        val page = clamped.roundToInt()
        return page to clamped - page
    }

    /** Content velocity points opposite the destination index; a decisive fling wins. */
    fun settle(progress: Float, contentVelocity: Float): Int = when {
        contentVelocity < -700f -> 1
        contentVelocity > 700f -> 0
        else -> progress.coerceIn(0f, 1f).roundToInt()
    }
}
