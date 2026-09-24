package com.jiligulu.app.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.*

private val firstDay = LocalDate.of(1900, 1, 1).toEpochDay()
internal fun dayPage(day: Long): Int = (Instant.ofEpochMilli(day).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay() - firstDay).toInt()
internal fun pageDay(page: Int): Long = LocalDate.ofEpochDay(firstDay + page).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** Adjacent real pages stay visible while dragging. Only settled pages update the shared date. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DayPager(day: Long, latest: Long, onSelect: (Long) -> Unit, modifier: Modifier = Modifier,
    tag: String = "day-pager", content: @Composable PagerScope.(Long) -> Unit) {
    val state = rememberPagerState(initialPage = dayPage(day).coerceIn(0, dayPage(latest))) { dayPage(latest) + 1 }
    val currentDay by rememberUpdatedState(day)
    val select by rememberUpdatedState(onSelect)
    var synchronized by remember { mutableStateOf(false) }
    var synchronizedDay by remember { mutableLongStateOf(day) }
    LaunchedEffect(day) {
        synchronized = false
        val target = dayPage(day).coerceIn(0, state.pageCount - 1)
        try {
            if (target != state.settledPage) state.animateScrollToPage(target, animationSpec = tween(420))
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
    HorizontalPager(state = state, modifier = modifier.testTag(tag).clip(RoundedCornerShape(18.dp))
        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .25f)),
        pageSpacing = 14.dp, beyondViewportPageCount = 1,
        flingBehavior = PagerDefaults.flingBehavior(state, pagerSnapDistance = PagerSnapDistance.atMost(1), snapAnimationSpec = tween(420)),
        key = { it }) { page -> content(pageDay(page)) }
}
