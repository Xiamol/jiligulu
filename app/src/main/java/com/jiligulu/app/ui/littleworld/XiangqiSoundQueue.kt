package com.jiligulu.app.ui.littleworld

/** Each accepted move keeps its own sound until its matching animation lands. */
internal class XiangqiSoundQueue {
    data class Event(
        val id: Long,
        val epoch: Int,
        val position: XiangqiState,
        val captured: Boolean,
        val fallbackAtMillis: Long,
    )

    private val pending = ArrayDeque<Event>()
    private var nextId = 0L
    var epoch = 0
        private set
    var latestPosition: XiangqiState? = null
        private set
    val firstPending: Event? get() = pending.firstOrNull()

    fun invalidate() {
        epoch++
        pending.clear()
        latestPosition = null
    }

    fun register(previous: XiangqiState, next: XiangqiState, nowMillis: Long): Event? {
        val move = next.lastMove
        if (next.ply != previous.ply + 1 || move == null || XiangqiEngine.play(previous, move) != next) {
            invalidate()
            return null
        }
        if ((latestPosition != null && latestPosition != previous) || pending.size >= MAX_PENDING) invalidate()
        latestPosition = next
        // Queued animations may wait for earlier moves. Give each one a full fallback window.
        return Event(++nextId, epoch, next, previous.pieceAt(move.to.x, move.to.y) != 0,
            nowMillis + FALLBACK_MILLIS * (pending.size + 1)).also(pending::addLast)
    }

    /** A delayed callback may consume only its epoch and position, once, on the same move chain. */
    fun consume(expectedEpoch: Int, position: XiangqiState, current: XiangqiState): Event? {
        if (expectedEpoch != epoch || latestPosition != current) return null
        val event = pending.firstOrNull { it.epoch == expectedEpoch && it.position == position } ?: return null
        pending.remove(event)
        return event
    }

    /** When the board abandons a queued presentation, never replay its discarded intermediate steps. */
    fun restartLatest(expectedEpoch: Int, position: XiangqiState, current: XiangqiState, nowMillis: Long): Event? {
        if (expectedEpoch != epoch || position != current) return null
        val latest = pending.lastOrNull { it.position == position }.takeIf { latestPosition == position }
        invalidate()
        if (latest == null) return null
        latestPosition = position
        return Event(++nextId, epoch, position, latest.captured, nowMillis + FALLBACK_MILLIS).also(pending::addLast)
    }

    companion object {
        // Longer than the full movement/capture/landing presentation, including scheduling margin.
        const val FALLBACK_MILLIS = 1_200L
        const val MAX_PENDING = 5 // One moving piece and the board's four waiting steps.
    }
}
