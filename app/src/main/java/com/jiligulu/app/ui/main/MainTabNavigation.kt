package com.jiligulu.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Cottage
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Cottage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** The thumb and the actual main pages move together while the user scrubs the bar. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun MainTabNavigation(
    pager: PagerState,
    selectedTab: Int,
    onSelect: (Int) -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit
) {
    // Only this small bar observes each animation frame; the data-heavy pages don't recompose.
    val progress = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, (MainPageCount - 1).toFloat())
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val soundContext = LocalContext.current.applicationContext
    val start by rememberUpdatedState(onScrubStart)
    val drag by rememberUpdatedState(onScrub)
    val end by rememberUpdatedState(onScrubEnd)
    val select by rememberUpdatedState(onSelect)
    val thumbWidth = 56.dp
    val thumbPx = with(density) { thumbWidth.toPx() }

    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxWidth().height(58.dp).testTag("main-tab-scrubber")
                .pointerInput(pager, thumbPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        // Own physical touch; selectable below still supplies accessibility/keyboard clicks.
                        // DOWN freezes an in-flight spring without selecting another destination.
                        down.consume()
                        val pressedProgress = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, (MainPageCount - 1).toFloat())
                        val width = size.width.toFloat()
                        val grab = TabScrubPosition.grabOffset(down.position.x, pressedProgress, width, thumbPx)
                        val slop = viewConfiguration.touchSlop
                        var dragging = false
                        var finished = false
                        start()
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.size != 1) break
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val distance = change.position - down.position
                                if (!dragging && abs(distance.y) > slop && abs(distance.y) > abs(distance.x)) break
                                if (!dragging && TabScrubPosition.dragged(distance.x, distance.y, slop)) {
                                    dragging = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                change.consume()
                                if (dragging) {
                                    // Absolute position includes the movement before crossing touch slop.
                                    drag(TabScrubPosition.fromTrack(change.position.x, width, grab))
                                }
                                if (!change.pressed) {
                                    if (dragging) end() else {
                                        com.jiligulu.app.core.audio.UiSound.tap(soundContext)
                                        select(TabScrubPosition.tappedTab(change.position.x, width))
                                    }
                                    finished = true
                                    break
                                }
                            }
                        } finally {
                            if (!finished) end()
                        }
                    }
                }
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            Box(Modifier.padding(top = 5.dp).size(thumbWidth, 29.dp)
                .graphicsLayer { translationX = TabScrubPosition.center(progress, widthPx) - thumbPx / 2f }
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .76f), RoundedCornerShape(50)))
            Row(Modifier.fillMaxWidth().height(58.dp)) {
                listOf("账本", "小窝", "统计").forEachIndexed { index, label ->
                    val proximity = (1f - abs(progress - index)).coerceIn(0f, 1f)
                    val tint = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.onSurfaceVariant,
                        MaterialTheme.colorScheme.primary, proximity)
                    Column(Modifier.weight(1f).height(58.dp)
                        .selectable(selected = selectedTab == index, onClick = com.jiligulu.app.ui.components.uiTap { onSelect(index) }, role = Role.Tab)
                        .testTag(when (index) { 0 -> "main-tab-home"; 1 -> "main-tab-world"; else -> "main-tab-stats" })
                        .padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(when (index) {
                            0 -> Icons.AutoMirrored.Outlined.ReceiptLong
                            1 -> if (proximity > .5f) Icons.Filled.Cottage else Icons.Outlined.Cottage
                            else -> if (proximity > .5f) Icons.Filled.BarChart else Icons.Outlined.BarChart
                        },
                            contentDescription = null, modifier = Modifier.size(22.dp), tint = tint)
                        Text(label, Modifier.padding(top = 2.dp), color = tint,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
