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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** A second distinct gesture may hand off to the parent during the scrollbar's 800ms linger. */
class EdgeSpringState {
    var visible by mutableStateOf(false)
        internal set
    internal var lastDirection = 0
    internal var until = 0L
    internal fun begin(now: Long): Int = if (now < until) lastDirection else 0
    internal fun finish(direction: Int, now: Long) {
        lastDirection = direction
        until = if (direction != 0) now + 800 else 0
    }
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
    var offset by remember { mutableFloatStateOf(0f) }
    val rebound = remember { Animatable(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    var hideJob by remember { mutableStateOf<Job?>(null) }
    var touched by remember { mutableStateOf(false) }
    var allowDirection by remember { mutableIntStateOf(0) }
    var blocked by remember { mutableIntStateOf(0) }
    fun consume(delta: Float, source: NestedScrollSource): Offset {
        val direction = if (delta > 0) 1 else if (delta < 0) -1 else 0
        val edge = (direction == 1 && topEnabled && !backward()) || (direction == -1 && !forward())
        if (!edge || (handOffOnRepeat && direction == allowDirection)) return Offset.Zero
        if (source == NestedScrollSource.UserInput && touched) {
            blocked = direction
            gate.visible = true
            offset = (offset + delta * .23f).coerceIn(-limit, limit)
        }
        return Offset(0f, delta)
    }
    val connection = remember(handOffOnRepeat, topEnabled, interceptPre, gate) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource) = if (interceptPre) consume(available.y, source) else Offset.Zero
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = consume(available.y, source)
        override suspend fun onPreFling(available: Velocity): Velocity =
            if (blocked != 0) Velocity(0f, available.y) else Velocity.Zero
    } }
    this.pointerInput(gate, handOffOnRepeat, topEnabled) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touched = true
            blocked = 0
            allowDirection = if (handOffOnRepeat) gate.begin(SystemClock.uptimeMillis()) else 0
            job?.cancel(); hideJob?.cancel()
            offset = 0f
            do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
            touched = false
            gate.finish(blocked, SystemClock.uptimeMillis())
            val distance = offset
            job = scope.launch {
                rebound.snapTo(distance); offset = 0f
                rebound.animateTo(0f, spring(dampingRatio = .72f, stiffness = 500f, visibilityThreshold = .5f))
            }
            hideJob = scope.launch { kotlinx.coroutines.delay(800); gate.visible = false }
        }
    }.nestedScroll(connection).graphicsLayer { translationY = if (touched) offset else rebound.value }
}
