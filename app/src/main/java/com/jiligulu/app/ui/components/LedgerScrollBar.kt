package com.jiligulu.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.jiligulu.app.ui.theme.GuluBlushPink

@Composable
fun LedgerScrollBar(state: LazyListState, modifier: Modifier = Modifier, forceVisible: Boolean = false) {
    val opacity = scrollBarOpacity(state.isScrollInProgress, forceVisible)
    val ink = MaterialTheme.colorScheme.primary
    Canvas(modifier.width(8.dp).fillMaxHeight().padding(vertical = 8.dp)) {
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        if (items.isEmpty() || info.totalItemsCount == 0 || (!state.canScrollBackward && !state.canScrollForward)) return@Canvas
        val first = items.first(); val last = items.last()
        val start = first.index + ((info.viewportStartOffset - first.offset).toFloat() / first.size.coerceAtLeast(1)).coerceIn(0f, 1f)
        val end = last.index + ((info.viewportEndOffset - last.offset).toFloat() / last.size.coerceAtLeast(1)).coerceIn(0f, 1f)
        drawScrollBar(scrollBarGeometry(start, end, info.totalItemsCount, !state.canScrollForward), ink, opacity.value)
    }
}

/** Grid rows are the progress units, so a three-column wall does not jump three times per row. */
@Composable
fun LedgerScrollBar(state: LazyGridState, columns: Int, modifier: Modifier = Modifier, forceVisible: Boolean = false) {
    val opacity = scrollBarOpacity(state.isScrollInProgress, forceVisible)
    val ink = MaterialTheme.colorScheme.primary
    Canvas(modifier.width(8.dp).fillMaxHeight().padding(vertical = 8.dp)) {
        val info = state.layoutInfo
        val items = info.visibleItemsInfo.filter { it.row >= 0 }
        if (items.isEmpty() || info.totalItemsCount == 0 || (!state.canScrollBackward && !state.canScrollForward)) return@Canvas
        val first = items.minBy { it.row }
        val last = items.maxBy { it.row }
        val start = first.row + ((info.viewportStartOffset - first.offset.y).toFloat() / first.size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
        val end = last.row + ((info.viewportEndOffset - last.offset.y).toFloat() / last.size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
        val totalRows = (info.totalItemsCount + columns.coerceAtLeast(1) - 1) / columns.coerceAtLeast(1)
        drawScrollBar(scrollBarGeometry(start, end, totalRows, !state.canScrollForward), ink, opacity.value)
    }
}

@Composable
fun LedgerScrollBar(state: ScrollState, modifier: Modifier = Modifier, forceVisible: Boolean = false) {
    val opacity = scrollBarOpacity(state.isScrollInProgress, forceVisible)
    val ink = MaterialTheme.colorScheme.primary
    Canvas(modifier.width(8.dp).fillMaxHeight().padding(vertical = 8.dp)) {
        val range = state.maxValue
        if (range <= 0 || range == Int.MAX_VALUE) return@Canvas
        val viewport = size.height.coerceAtLeast(1f)
        val visibleFraction = (viewport / (viewport + range)).coerceIn(0f, 1f)
        drawScrollBar(ScrollBarGeometry(state.value.toFloat().div(range).coerceIn(0f, 1f), visibleFraction), ink, opacity.value)
    }
}

@Composable
private fun scrollBarOpacity(isScrolling: Boolean, forceVisible: Boolean): State<Float> {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(isScrolling) {
        if (isScrolling) visible = true else { delay(800); visible = false }
    }
    // Read this state in the drawing phase: fading the thumb never recomposes the page contents.
    return animateFloatAsState(if (visible || forceVisible) 1f else 0f, label = "scrollbar")
}

internal data class ScrollBarGeometry(val progress: Float, val visibleFraction: Float)

internal fun scrollBarGeometry(start: Float, end: Float, total: Int, atEnd: Boolean): ScrollBarGeometry {
    if (total <= 0 || !start.isFinite() || !end.isFinite()) return ScrollBarGeometry(0f, 1f)
    val visible = (end - start).coerceIn(0f, total.toFloat())
    val progress = if (atEnd) 1f else (start / (total - visible).coerceAtLeast(1f)).coerceIn(0f, 1f)
    return ScrollBarGeometry(progress, visible / total)
}

private fun DrawScope.drawScrollBar(geometry: ScrollBarGeometry, ink: Color, opacity: Float) {
    if (size.height <= 0 || opacity <= 0) return
    val thumb = (size.height * geometry.visibleFraction).coerceIn(28.dp.toPx().coerceAtMost(size.height), size.height)
    val y = (size.height - thumb) * geometry.progress
    drawRoundRect(ink.copy(alpha = .10f * opacity), Offset(size.width / 3, 0f), Size(size.width / 3, size.height), CornerRadius(size.width))
    drawRoundRect(ink.copy(alpha = .70f * opacity), Offset(size.width / 4, y), Size(size.width / 2, thumb), CornerRadius(size.width))
    drawCircle(GuluBlushPink.copy(alpha = opacity), radius = size.width / 2, center = Offset(size.width / 2, y + thumb / 2))
}
