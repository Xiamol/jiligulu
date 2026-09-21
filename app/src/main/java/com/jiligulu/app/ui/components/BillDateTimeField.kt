package com.jiligulu.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Null remains null until explicitly changed: the repository can use the time of confirmation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillDateTimeField(
    timestamp: Long?,
    onTimestampChange: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    allowCurrentTime: Boolean = true,
    label: String = "账单时间"
) {
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val dateTime = timestamp?.let { Instant.ofEpochMilli(it).atZone(zone) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (allowCurrentTime && timestamp != null) {
                TextButton(onClick = { onTimestampChange(null) }, enabled = enabled) { Text("使用此刻") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showDate = true }, enabled = enabled, modifier = Modifier.weight(1.25f)) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
                Text(dateTime?.format(DateTimeFormatter.ofPattern("yyyy/M/d")) ?: "选择日期",
                    modifier = Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(onClick = { showTime = true }, enabled = enabled && (allowCurrentTime || timestamp != null),
                modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Schedule, contentDescription = null)
                Text(dateTime?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "选择时间",
                    modifier = Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        if (timestamp == null) {
            Text(if (allowCurrentTime) "此刻 · 确认入账时记录" else "尚未选择日期和时间", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showDate) {
        val initialDate = (dateTime ?: Instant.now().atZone(zone)).toLocalDate()
        val yearRange = billDatePickerYearRange(initialDate.year)
        // Material DatePicker represents calendar days at UTC midnight, independently of the user's zone.
        // AI/imported dates may predate 1900 or exceed 2100, outside Material's default range.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initialDate.takeIf { it.year in yearRange }
                ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            yearRange = yearRange
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { selected ->
                        val day = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate()
                        onTimestampChange(withBillDate(timestamp ?: System.currentTimeMillis(), day, zone))
                    }
                    showDate = false
                }, enabled = state.selectedDateMillis != null) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("取消") } }
        ) { DatePicker(state = state, showModeToggle = true) }
    }
    if (showTime) {
        val initial = dateTime ?: Instant.now().atZone(zone)
        val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text("选择时间 · 24 小时制") },
            text = { TimeInput(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onTimestampChange(withBillTime(timestamp ?: System.currentTimeMillis(), state.hour, state.minute, zone))
                    showTime = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("取消") } }
        )
    }
}

internal fun withBillDate(timestamp: Long, day: LocalDate, zone: ZoneId): Long {
    val time = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalTime()
    return day.atTime(time).atZone(zone).toInstant().toEpochMilli()
}

internal fun withBillTime(timestamp: Long, hour: Int, minute: Int, zone: ZoneId): Long =
    Instant.ofEpochMilli(timestamp).atZone(zone).withHour(hour).withMinute(minute)
        .withSecond(0).withNano(0).toInstant().toEpochMilli()

/** Preserve editable historical dates while keeping invalid/extreme years out of Material's state. */
internal fun billDatePickerYearRange(year: Int): IntRange =
    if (year in 1..9999) minOf(1900, year)..maxOf(2100, year) else 1900..2100
