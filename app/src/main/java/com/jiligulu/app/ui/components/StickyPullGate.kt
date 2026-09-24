package com.jiligulu.app.ui.components

/** Two consecutive downward gestures; a pause or another gesture starts a new pair. */
internal class StickyPullGate(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val consecutiveWindowMillis: Long = 1200) {
    private var armedAt: Long? = null
    var allowExpand = false
        private set
    fun begin(pinned: Boolean, innerAtTop: Boolean) {
        val elapsed = armedAt?.let { nowMillis() - it }
        allowExpand = pinned && innerAtTop && elapsed != null && elapsed in 0..consecutiveWindowMillis
        armedAt = null
    }
    fun finish(pinned: Boolean, innerAtTop: Boolean, downward: Boolean) {
        armedAt = if (pinned && innerAtTop && downward) nowMillis() else null
    }
    fun blockedAtTop() { armedAt = nowMillis() }
    fun reset() { armedAt = null; allowExpand = false }
}
