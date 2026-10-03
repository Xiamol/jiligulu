package com.jiligulu.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** The thumb and the actual two pages move together while the user scrubs the bar. */
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
    val progress = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val start by rememberUpdatedState(onScrubStart)
    val drag by rememberUpdatedState(onScrub)
    val end by rememberUpdatedState(onScrubEnd)
    val position by rememberUpdatedState(progress)
    var dragStart by remember { mutableFloatStateOf(0f) }
    var dragPixels by remember { mutableFloatStateOf(0f) }
    val thumbWidth = 70.dp
    val thumbPx = with(density) { thumbWidth.toPx() }

    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxWidth().height(80.dp).testTag("main-tab-scrubber")
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            dragStart = position
                            dragPixels = 0f
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            start()
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragPixels += amount.x
                            drag(TabScrubPosition.fromPixels(dragStart, dragPixels, size.width / 2f))
                        },
                        onDragEnd = { end() },
                        onDragCancel = { end() }
                    )
                }
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            Box(Modifier.padding(top = 11.dp).size(thumbWidth, 34.dp)
                .graphicsLayer { translationX = widthPx * (.25f + progress.coerceIn(0f, 1f) * .5f) - thumbPx / 2f }
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .76f), RoundedCornerShape(50)))
            Row(Modifier.fillMaxWidth().height(80.dp)) {
                listOf("账本", "统计").forEachIndexed { index, label ->
                    val proximity = 1f - abs(progress.coerceIn(0f, 1f) - index)
                    val tint = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.onSurfaceVariant,
                        MaterialTheme.colorScheme.primary, proximity)
                    Column(Modifier.weight(1f).height(80.dp)
                        .selectable(selected = selectedTab == index, onClick = { onSelect(index) }, role = Role.Tab)
                        .testTag(if (index == 0) "main-tab-home" else "main-tab-stats")
                        .padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (index == 0) Icons.AutoMirrored.Outlined.ReceiptLong
                            else if (proximity > .5f) Icons.Filled.BarChart else Icons.Outlined.BarChart,
                            contentDescription = null, modifier = Modifier.size(25.dp), tint = tint)
                        Text(label, Modifier.padding(top = 6.dp), color = tint,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
