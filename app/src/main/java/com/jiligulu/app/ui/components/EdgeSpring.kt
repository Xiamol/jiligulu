package com.jiligulu.app.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** A second distinct gesture may hand off to the parent during the scrollbar's 800ms linger. */
class EdgeSpringState {
    var visible by mutableStateOf(false)
        internal set
    internal var lastDirection = 0
    internal var until = 0L
    internal var hideJob: Job? = null
    internal fun begin(now: Long): Int = if (now < until) lastDirection else 0
    internal fun finish(direction: Int, now: Long) {
        lastDirection = direction
        until = if (direction != 0) now + 800 else 0
    }
}

/** One signed displacement per visible surface. Read offset only in its graphics layer. */
internal class EdgeSpringMotion(private val limit: Float) {
    var offset by mutableFloatStateOf(0f)
        private set
    var touching = false
        private set
    private val rebound = Animatable(0f)
    private var job: Job? = null
    private var generation = 0

    fun beginTouch() {
        generation++
        job?.cancel()
        job = null
        touching = true
    }

    fun pull(delta: Float) {
        // Pointer up is observed in Initial, before a scrollable handles its final Main
        // delta. That late delta must not cancel a rebound that has already started.
        if (!touching || !delta.isFinite()) return
        offset = (offset + delta * .24f).coerceIn(-limit, limit)
    }

    fun clear() {
        generation++
        job?.cancel()
        job = null
        offset = 0f
    }

    fun reset() {
        touching = false
        clear()
    }

    fun release(scope: CoroutineScope) {
        touching = false
        if (!scope.isActive || abs(offset) < .5f) { clear(); return }
        val distance = offset
        val token = ++generation
        job?.cancel()
        job = scope.launch {
            try {
                rebound.snapTo(distance)
                rebound.animateTo(0f, spring(dampingRatio = .72f, stiffness = 500f, visibilityThreshold = .5f)) {
                    if (token == generation && !touching) offset = value
                }
            } finally {
                // An old cancelled animation cannot clear the position of a newer drag.
                if (token == generation && !touching) {
                    offset = 0f
                    job = null
                }
            }
        }
    }
}

private class EdgeSpringGesture {
    var allowDirection = 0
    var blocked = 0
}

fun Modifier.edgeSpring(
    canBack: () -> Boolean, canForward: () -> Boolean,
    handOffOnRepeat: Boolean = false, topEnabled: Boolean = true, interceptPre: Boolean = true,
    state: EdgeSpringState? = null
): Modifier = composed {
    val gate = state ?: remember { EdgeSpringState() }
    val backward by rememberUpdatedState(canBack)
    val forward by rememberUpdatedState(canForward)
    val scope = rememberCoroutineScope()
    val limit = with(LocalDensity.current) { 32f * density }
    val motion = remember(limit) { EdgeSpringMotion(limit) }
    val gesture = remember { EdgeSpringGesture() }
    DisposableEffect(motion, gate) {
        onDispose {
            motion.reset()
            gate.hideJob?.cancel()
            gate.hideJob = null
            gate.visible = false
            gate.finish(0, SystemClock.uptimeMillis())
        }
    }
    fun consume(delta: Float, source: NestedScrollSource): Offset {
        val direction = if (delta > 0) 1 else if (delta < 0) -1 else 0
        val edge = (direction == 1 && topEnabled && !backward()) || (direction == -1 && !forward())
        if (!edge) return Offset.Zero
        if (handOffOnRepeat && direction == gesture.allowDirection) {
            motion.clear()
            return Offset.Zero
        }
        if (source == NestedScrollSource.UserInput && motion.touching) {
            gesture.blocked = direction
            gate.visible = true
            motion.pull(delta)
        }
        return Offset(0f, delta)
    }
    val connection = remember(handOffOnRepeat, topEnabled, interceptPre, gate, motion) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource) = if (interceptPre) consume(available.y, source) else Offset.Zero
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = consume(available.y, source)
        override suspend fun onPreFling(available: Velocity): Velocity =
            if (gesture.blocked != 0) Velocity(0f, available.y) else Velocity.Zero
    } }
    this.pointerInput(gate, motion, handOffOnRepeat, topEnabled) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            motion.beginTouch()
            gesture.blocked = 0
            gesture.allowDirection = if (handOffOnRepeat) gate.begin(SystemClock.uptimeMillis()) else 0
            gate.hideJob?.cancel()
            var complete = false
            try {
                do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
                complete = true
            } finally {
                gate.finish(if (complete) gesture.blocked else 0, SystemClock.uptimeMillis())
                // Always release, including a tap that interrupts an old spring, cancellation
                // and a gesture wholly consumed by an ancestor nested scroll connection.
                motion.release(scope)
                if (gate.visible && scope.isActive) gate.hideJob = scope.launch {
                    kotlinx.coroutines.delay(800)
                    gate.visible = false
                    gate.hideJob = null
                }
            }
        }
    }.nestedScroll(connection).graphicsLayer { translationY = motion.offset }
}
