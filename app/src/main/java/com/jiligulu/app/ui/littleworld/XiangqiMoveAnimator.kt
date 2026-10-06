package com.jiligulu.app.ui.littleworld

import kotlin.math.abs
import kotlin.math.max

/** Presentation identity changes when the owning scene, round or lifecycle generation changes. */
internal data class XiangqiPresentationKey(
    val epoch: Int = 0,
    val restorationToken: Int = 0,
    val mode: Int = 0,
    val round: Int = 0,
    val room: String = "",
    val connected: Boolean = false,
    val localSide: XiangqiSide? = null,
)

internal data class XiangqiViewSnapshot(val position: XiangqiState, val identity: XiangqiPresentationKey) {
    companion object {
        fun of(position: XiangqiState, identity: XiangqiPresentationKey) =
            XiangqiViewSnapshot(position.copy(board = position.board.toList()), identity)
    }
}

internal data class XiangqiStepAnim(
    val before: XiangqiState,
    val after: XiangqiState,
    val mover: Int,
    val captured: Int,
    val durationMillis: Int,
) {
    val from: GridCell get() = checkNotNull(after.lastMove).from
    val to: GridCell get() = checkNotNull(after.lastMove).to
}

/** Standard move interpolation; the immutable engine position is never modified by the animation. */
internal object XiangqiMoveAnimator {
    const val MIN_DURATION_MS = 280
    const val MAX_DURATION_MS = 420
    const val PER_RANK_MS = 28
    const val CAPTURE_FADE_MS = 100
    const val FINISH_PAUSE_MS = 80
    const val FINISH_SHOW_MS = 3000
    const val SEAL_AT_MS = 1500
    const val SEAL_FADE_MS = 380
    const val LANDING_FLASH_MS = 320
    const val TRAIL_HOLD_MS = 1500

    fun durationFor(move: XiangqiMove): Int {
        val span = max(abs(move.to.x - move.from.x), abs(move.to.y - move.from.y))
        return (MIN_DURATION_MS + (span - 1) * PER_RANK_MS).coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }

    /** Only a complete, legal single-ply transition in the same presentation may animate. */
    fun stepFrom(previous: XiangqiViewSnapshot, next: XiangqiViewSnapshot): XiangqiStepAnim? {
        if (previous.identity != next.identity) return null
        val before = previous.position
        val after = next.position
        if (before.outcome != XiangqiOutcome.PLAYING || after.ply != before.ply + 1 ||
            after.turnSide != before.turnSide.opponent) return null
        val move = after.lastMove ?: return null
        if (move.from.x !in 0..8 || move.to.x !in 0..8 || move.from.y !in 0..9 || move.to.y !in 0..9) return null
        // This validates all 90 cells, turn, last move, ply and terminal outcome together.
        if (XiangqiEngine.play(before, move) != after) return null
        return XiangqiStepAnim(before, after, before.pieceAt(move.from.x, move.from.y),
            before.pieceAt(move.to.x, move.to.y), durationFor(move))
    }
}

internal enum class XiangqiQueueUpdate { UNCHANGED, ENQUEUED, RESET }
internal data class XiangqiQueuedStep(val generation: Long, val identity: XiangqiPresentationKey, val step: XiangqiStepAnim)

/** A small ordered queue prevents a fast remote reply from replacing a piece already in flight. */
internal class XiangqiMoveQueue(initial: XiangqiViewSnapshot) {
    private var observed = initial
    private val waiting = ArrayDeque<XiangqiQueuedStep>()
    private var generation = 0L
    val hasPending: Boolean get() = waiting.isNotEmpty()

    fun offer(next: XiangqiViewSnapshot): XiangqiQueueUpdate {
        if (next == observed) return XiangqiQueueUpdate.UNCHANGED
        val step = XiangqiMoveAnimator.stepFrom(observed, next)
        observed = next
        if (step == null || waiting.size >= MAX_PENDING) {
            invalidate()
            return XiangqiQueueUpdate.RESET
        }
        waiting.addLast(XiangqiQueuedStep(generation, next.identity, step))
        return XiangqiQueueUpdate.ENQUEUED
    }

    fun take(): XiangqiQueuedStep? = if (waiting.isEmpty()) null else waiting.removeFirst()
    fun owns(entry: XiangqiQueuedStep): Boolean = entry.generation == generation && entry.identity == observed.identity
    fun invalidate() { generation++; waiting.clear() }

    private companion object { const val MAX_PENDING = 4 }
}
