package com.jiligulu.app.ui.components

import androidx.compose.animation.core.spring
import androidx.compose.ui.Alignment
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.*
import kotlin.math.abs

internal val DaySnapSpec = spring<Float>(dampingRatio = 1f, stiffness = 650f, visibilityThreshold = 1f)

private val firstDay = LocalDate.of(1900, 1, 1).toEpochDay()
internal fun dayPage(day: Long): Int = (Instant.ofEpochMilli(day).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay() - firstDay).toInt()
internal fun pageDay(page: Int): Long = LocalDate.ofEpochDay(firstDay + page).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** Adjacent real pages stay visible while dragging. Only settled pages update the shared date. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DayPager(day: Long, latest: Long, onSelect: (Long) -> Unit, modifier: Modifier = Modifier,
    tag: String = "day-pager", onSwipePastToday: (() -> Unit)? = null,
    onPageDrag: ((Float) -> Unit)? = null, onPageDragEnd: ((Float) -> Unit)? = null,
    content: @Composable PagerScope.(Long) -> Unit) {
    val state = rememberPagerState(initialPage = dayPage(day).coerceIn(0, dayPage(latest))) { dayPage(latest) + 1 }
    val currentDay by rememberUpdatedState(day)
    val select by rememberUpdatedState(onSelect)
    val todaySwipe by rememberUpdatedState(onSwipePastToday)
    val latestDay by rememberUpdatedState(latest)
    var synchronized by remember { mutableStateOf(false) }
    var synchronizedDay by remember { mutableLongStateOf(day) }
    LaunchedEffect(day) {
        synchronized = false
        val target = dayPage(day).coerceIn(0, state.pageCount - 1)
        try {
            if (target != state.settledPage) state.animateScrollToPage(target, animationSpec = DaySnapSpec)
        } finally {
            synchronizedDay = day
            synchronized = true
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.settledPage, state.isScrollInProgress, synchronized) }.distinctUntilChanged().collect { (page, moving, ready) ->
            val settled = pageDay(page)
            if (ready && !moving && synchronized && synchronizedDay == currentDay && !state.isScrollInProgress && page == state.settledPage && settled != currentDay) select(settled)
        }
    }
    // The same pointer route drives the parent page in real time at today's boundary.
    // A rightward drag still belongs to the date pager and reveals yesterday.
    val todayGesture = Modifier.forwardMainPageSwipe(
        enabled = { todaySwipe != null && currentDay == latestDay && !state.isScrollInProgress &&
            state.settledPage == dayPage(latestDay) },
        onDrag = onPageDrag, onDragEnd = onPageDragEnd, onSwipe = onSwipePastToday
    )
    HorizontalPager(state = state, modifier = modifier.then(todayGesture).testTag(tag).clip(RoundedCornerShape(18.dp))
        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .25f)),
        verticalAlignment = Alignment.Top,
        pageSpacing = 14.dp, beyondViewportPageCount = 1,
        flingBehavior = PagerDefaults.flingBehavior(state, pagerSnapDistance = PagerSnapDistance.atMost(1), snapAnimationSpec = DaySnapSpec),
        key = { it }) { page -> content(pageDay(page)) }
}

/** Starts only a deliberate leftward drag, so vertical lists and yesterday remain independent. */
fun Modifier.forwardMainPageSwipe(
    enabled: () -> Boolean,
    onDrag: ((Float) -> Unit)?,
    onDragEnd: ((Float) -> Unit)?,
    onSwipe: (() -> Unit)? = null,
    startAllowed: (Offset) -> Boolean = { true }
): Modifier = composed {
    val allowed by rememberUpdatedState(enabled)
    val start by rememberUpdatedState(startAllowed)
    val drag by rememberUpdatedState(onDrag)
    val finish by rememberUpdatedState(onDragEnd)
    val swipe by rememberUpdatedState(onSwipe)
    pointerInput(Unit) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!allowed() || !start(first.position) || (drag == null && swipe == null)) return@awaitEachGesture
            val dragHandler = drag
            val finishHandler = finish
            val swipeHandler = swipe
            val tracker = VelocityTracker().apply { addPosition(first.uptimeMillis, Offset.Zero) }
            val threshold = maxOf(viewConfiguration.touchSlop * 3, size.width * .2f)
            var claimed = false
            var ended = false
            var travel = Offset.Zero
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size != 1) return@awaitEachGesture
                    val change = event.changes.firstOrNull { it.id == first.id } ?: return@awaitEachGesture
                    // Both positions are transformed through this frame's page location.
                    // Subtracting a cached local coordinate counted the moving page itself
                    // as finger motion, which alternated between stuck and doubled steps.
                    val step = change.position - change.previousPosition
                    travel += step
                    tracker.addPosition(change.uptimeMillis, travel)
                    var justClaimed = false
                    if (!claimed && maxOf(abs(travel.x), abs(travel.y)) > viewConfiguration.touchSlop) {
                        if (-travel.x <= abs(travel.y)) return@awaitEachGesture
                        claimed = true
                        justClaimed = true
                    }
                    if (claimed) {
                        change.consume()
                        val delta = if (justClaimed) travel.x else step.x
                        if (delta != 0f) dragHandler?.invoke(delta)
                    }
                    if (!change.pressed) {
                        if (claimed) {
                            if (dragHandler != null) finishHandler?.invoke(tracker.calculateVelocity().x)
                            else if (-travel.x >= threshold && -travel.x > abs(travel.y)) swipeHandler?.invoke()
                        }
                        ended = true
                        break
                    }
                } while (true)
            } finally {
                // Cancellation, multi-touch and disposal settle the parent rather than leaving
                // an intermediate page offset visible after the finger has disappeared.
                if (claimed && !ended && dragHandler != null) finishHandler?.invoke(0f)
            }
        }
    }
}
