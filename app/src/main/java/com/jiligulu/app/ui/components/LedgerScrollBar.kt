package com.jiligulu.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.jiligulu.app.ui.theme.GuluBlushPink

@Composable
fun LedgerScrollBar(state: LazyListState, modifier: Modifier = Modifier, forceVisible: Boolean = false) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) visible = true else { delay(800); visible = false }
    }
    val opacity by animateFloatAsState(if (visible || forceVisible) 1f else 0f, label = "scrollbar")
    val ink = MaterialTheme.colorScheme.primary
    Canvas(modifier.width(8.dp).fillMaxHeight().padding(vertical = 8.dp)) {
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        if (items.isEmpty() || info.totalItemsCount == 0 || (!state.canScrollBackward && !state.canScrollForward)) return@Canvas
        val first = items.first(); val last = items.last()
        val start = first.index + ((info.viewportStartOffset - first.offset).toFloat() / first.size.coerceAtLeast(1)).coerceIn(0f, 1f)
        val end = last.index + ((info.viewportEndOffset - last.offset).toFloat() / last.size.coerceAtLeast(1)).coerceIn(0f, 1f)
        val thumb = (size.height * (end - start) / info.totalItemsCount).coerceIn(28.dp.toPx().coerceAtMost(size.height), size.height)
        val progress = if (!state.canScrollForward) 1f else (start / (info.totalItemsCount - (end - start)).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val y = (size.height - thumb) * progress
        drawRoundRect(ink.copy(alpha = .10f * opacity), Offset(size.width / 3, 0f), Size(size.width / 3, size.height), CornerRadius(size.width))
        drawRoundRect(ink.copy(alpha = .70f * opacity), Offset(size.width / 4, y), Size(size.width / 2, thumb), CornerRadius(size.width))
        drawCircle(GuluBlushPink.copy(alpha = opacity), radius = size.width / 2, center = Offset(size.width / 2, y + thumb / 2))
    }
}
