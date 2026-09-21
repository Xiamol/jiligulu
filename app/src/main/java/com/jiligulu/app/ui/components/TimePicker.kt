package com.jiligulu.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 时间选择弹窗：点击输入框后弹出，滚轮选完点「确认」才回写。
 *
 * 刻意做成弹窗而非内联滚轮——内联滚轮会和页面纵向滚动抢手势，
 * 手指在滚轮上滑动即无法正常翻页（v0.5.3 的教训）。
 */
@Composable
fun TimePickerDialog(
    title: String,
    hour: Int,
    minute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
    hourRange: IntRange = 0..23,
    minuteRange: IntRange = 0..59,
    minuteStep: Int = 1,
    confirmEnabled: (hour: Int, minute: Int) -> Boolean = { _, _ -> true },
    confirmHint: String? = null
) {
    // 弹窗内的草稿值：只有点确认才向外提交，取消等于什么都没发生。
    var draftHour by rememberSaveable(title) { mutableIntStateOf(hour) }
    var draftMinute by rememberSaveable(title) { mutableIntStateOf(minute) }
    val ok = confirmEnabled(draftHour, draftMinute)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TimeWheel(
                    hour = draftHour,
                    minute = draftMinute,
                    onHourChange = { draftHour = it },
                    onMinuteChange = { draftMinute = it },
                    hourRange = hourRange,
                    minuteRange = minuteRange,
                    minuteStep = minuteStep
                )
                if (confirmHint != null && !ok) {
                    Text(
                        confirmHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(draftHour, draftMinute) },
                enabled = ok,
                modifier = Modifier.testTag("time-picker-confirm")
            ) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("time-picker-cancel")) {
                Text("取消")
            }
        }
    )
}

/** 时/分双列滚轮，带吸附对齐；选中项居中高亮。 */
@Composable
private fun TimeWheel(
    hour: Int,
    minute: Int,
    onHourChange: (Int) -> Unit,
    onMinuteChange: (Int) -> Unit,
    hourRange: IntRange,
    minuteRange: IntRange,
    minuteStep: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        WheelColumn(
            values = hourRange.toList(),
            selected = hour,
            onSelect = onHourChange,
            modifier = Modifier.weight(1f)
        )
        Text(
            ":",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        WheelColumn(
            values = minuteRange.filter { it % minuteStep == 0 },
            selected = minute,
            onSelect = onMinuteChange,
            modifier = Modifier.weight(1f)
        )
    }
}

private const val WHEEL_ITEM_HEIGHT_DP = 42
private const val WHEEL_VISIBLE = 5

@Composable
private fun WheelColumn(
    values: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val itemHeight = WHEEL_ITEM_HEIGHT_DP.dp
    val listState = rememberLazyListState()
    val initialIndex = remember(values) { values.indexOf(selected).coerceAtLeast(0) }

    // 打开时直接定位到当前值（不带动画，避免弹窗一进来就在滚）。
    LaunchedEffect(initialIndex) {
        listState.scrollToItem((initialIndex - WHEEL_VISIBLE / 2).coerceAtLeast(0))
    }

    val snapBehavior = rememberSnapFlingBehavior(listState)
    // 滚动停下后把居中项回写草稿。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                if (inProgress) return@collect
                val info = listState.layoutInfo
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                val closest = info.visibleItemsInfo.minByOrNull {
                    kotlin.math.abs((it.offset + it.size / 2) - center)
                } ?: return@collect
                values.getOrNull(closest.index)?.let { onSelect(it) }
            }
    }

    // 居中索引只读布局信息，滚动过程中不触发重组以外的工作。
    val centerIndex by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull {
                kotlin.math.abs((it.offset + it.size / 2) - center)
            }?.index ?: -1
        }
    }

    Box(
        modifier = modifier
            .height(itemHeight * WHEEL_VISIBLE)
            .clip(MaterialTheme.shapes.medium)
    ) {
        // 中央选中带
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
            content = {}
        )
        LazyColumn(
            state = listState,
            flingBehavior = snapBehavior,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            items(values.size) { index ->
                val value = values[index]
                val isSelected = index == centerIndex
                Box(
                    modifier = Modifier.height(itemHeight).width(84.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "%02d".format(value),
                        style = if (isSelected) MaterialTheme.typography.titleLarge
                        else MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.alpha(if (isSelected) 1f else 0.5f)
                    )
                }
            }
        }
    }
}

/**
 * 点击后弹出时间选择弹窗的一行：左边标题，右边显示当前值。
 * 未配对点击回调时只做展示。
 */
@Composable
fun TimePickerField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            supporting?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(
                1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            ),
            modifier = Modifier.testTag("time-field-$label")
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}
