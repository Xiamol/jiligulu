package com.jiligulu.app.ui.components

/** A downward gesture can unlock the header only if the preceding gesture already stopped at the top. */
internal class StickyPullGate {
    private var armed = false
    var allowExpand = false
        private set
    fun begin(pinned: Boolean, innerAtTop: Boolean) {
        allowExpand = pinned && armed && innerAtTop
    }
    fun finish(pinned: Boolean, innerAtTop: Boolean, downward: Boolean) {
        armed = pinned && innerAtTop && downward
    }
    fun blockedAtTop() { armed = true }
    fun reset() { armed = false; allowExpand = false }
}
