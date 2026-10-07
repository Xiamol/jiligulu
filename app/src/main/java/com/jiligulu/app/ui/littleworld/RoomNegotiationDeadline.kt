package com.jiligulu.app.ui.littleworld

/** Count only time during which both desks can respond; background/frozen renderers keep consent. */
internal class RoomNegotiationDeadline(durationMillis: Long, nowMillis: Long, paused: Boolean) {
    private var remainingMillis = durationMillis
    private var lastTick = nowMillis
    private var wasPaused = paused

    fun tick(nowMillis: Long, paused: Boolean): Long {
        // If either end of an interval is suspended, do not guess when its renderer froze.
        if (!wasPaused && !paused) remainingMillis = (remainingMillis - (nowMillis - lastTick).coerceAtLeast(0)).coerceAtLeast(0)
        lastTick = nowMillis
        wasPaused = paused
        return remainingMillis
    }
}
