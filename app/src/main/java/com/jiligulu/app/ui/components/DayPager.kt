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
    // Claim this one direction before the pager's touch-slop handler starts moving the date.
    // All other gestures still belong to the pager or its vertically scrolling bill list.
    val todayGesture = Modifier.pointerInput(state) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (todaySwipe == null || currentDay != latestDay || state.isScrollInProgress ||
                state.settledPage != dayPage(latestDay)) return@awaitEachGesture
            // Dates ascend left to right: a leftward drag beyond today opens statistics.
            val direction = -1f
            val threshold = maxOf(viewConfiguration.touchSlop * 3, size.width * .2f)
            var claimed = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                // A second finger or a cancelled pointer must never switch destinations.
                if (event.changes.size != 1) return@awaitEachGesture
                val change = event.changes.firstOrNull { it.id == first.id } ?: return@awaitEachGesture
                val distance = change.position - first.position
                if (!claimed && maxOf(abs(distance.x), abs(distance.y)) > viewConfiguration.touchSlop) {
                    if (distance.x * direction <= abs(distance.y)) return@awaitEachGesture
                    claimed = true
                }
                if (claimed) change.consume()
                if (!change.pressed) {
                    if (claimed && distance.x * direction >= threshold &&
                        distance.x * direction > abs(distance.y) && currentDay == latestDay) todaySwipe?.invoke()
                    break
                }
            } while (true)
        }
    }
    HorizontalPager(state = state, modifier = modifier.then(todayGesture).testTag(tag).clip(RoundedCornerShape(18.dp))
        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .25f)),
        verticalAlignment = Alignment.Top,
        pageSpacing = 14.dp, beyondViewportPageCount = 1,
        flingBehavior = PagerDefaults.flingBehavior(state, pagerSnapDistance = PagerSnapDistance.atMost(1), snapAnimationSpec = DaySnapSpec),
        key = { it }) { page -> content(pageDay(page)) }
}
