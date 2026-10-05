package com.jiligulu.app.ui.littleworld

/** A local tap sequence only; it never changes game rules or exposes a help indicator. */
internal class HiddenGameHelpTapSequence {
    private var count = 0
    private var firstAt = 0L

    fun reset() { count = 0; firstAt = 0L }

    fun tap(nowMillis: Long, eligible: Boolean): Boolean {
        if (!eligible) { reset(); return false }
        if (count == 0 || nowMillis < firstAt || nowMillis - firstAt > 4000L) {
            firstAt = nowMillis
            count = 1
        } else count++
        return if (count >= 7) { reset(); true } else false
    }
}
