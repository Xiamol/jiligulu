package com.jiligulu.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 滚动时间选择器（时/分两列），仿闹钟 App 的交互。
 * 选中项居中高亮，上下各显示两项作预览。
 */
@Composable
fun TimePicker(
    hour: Int,
    minute: Int,
    onTimeChange: (hour: Int, minute: Int) -> Unit,
    modifier: Modifier = Modifier,
    hourRange: IntRange = 0..23,
    minuteRange: IntRange = 0..59
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NumberColumn(
            values = hourRange.toList(),
            selected = hour,
            onSelect = { onTimeChange(it, minute) },
            label = "时",
            modifier = Modifier.weight(1f)
        )
        Text(
            ":",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        NumberColumn(
            values = minuteRange.toList(),
            selected = minute,
            onSelect = { onTimeChange(hour, it) },
            label = "分",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun NumberColumn(
    values: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    val itemHeight = 40.dp
    val visibleCount = 5
    val listState = rememberLazyListState()

    // 初始滚动到选中项
    LaunchedEffect(Unit) {
        val index = values.indexOf(selected).coerceAtLeast(0)
        listState.scrollToItem((index - visibleCount / 2).coerceAtLeast(0))
    }

    // 监听滚动停止，吸附到最近项
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                if (!inProgress) {
                    val layoutInfo = listState.layoutInfo
                    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                    val closest = layoutInfo.visibleItemsInfo.minByOrNull {
                        kotlin.math.abs((it.offset + it.size / 2) - viewportCenter)
                    }
                    closest?.let {
                        val value = values.getOrNull(it.index) ?: return@let
                        if (value != selected) onSelect(value)
                        // 吸附对齐
                        listState.animateScrollToItem((it.index - visibleCount / 2).coerceAtLeast(0))
                    }
                }
            }
    }

    val centerIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            layoutInfo.visibleItemsInfo.minByOrNull {
                kotlin.math.abs((it.offset + it.size / 2) - viewportCenter)
            }?.index ?: -1
        }
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Box(
            modifier = Modifier
                .height(itemHeight * visibleCount)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            LazyColumn(
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                items(values.size) { index ->
                    val value = values[index]
                    val isSelected = index == centerIndex
                    Box(
                        modifier = Modifier
                            .height(itemHeight)
                            .width(72.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "%02d".format(value),
                            style = if (isSelected) MaterialTheme.typography.headlineSmall
                                else MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.alpha(if (isSelected) 1f else 0.45f)
                        )
                    }
                }
            }
            // 选中框指示线
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .height(itemHeight)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
                    val strokeWidth = 1.dp.toPx()
                    val color = androidx.compose.ui.graphics.Color(0x1F000000)
                    drawLine(color, androidx.compose.ui.geometry.Offset(0f, 0f),
                        androidx.compose.ui.geometry.Offset(size.width, 0f), strokeWidth)
                    drawLine(color, androidx.compose.ui.geometry.Offset(0f, size.height),
                        androidx.compose.ui.geometry.Offset(size.width, size.height), strokeWidth)
                }
            }
        }
    }
}
