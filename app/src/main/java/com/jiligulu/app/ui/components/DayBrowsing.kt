package com.jiligulu.app.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.jiligulu.app.core.util.Formatters
import java.time.*

fun Modifier.daySwipe(day: Long, previous: () -> Unit, next: () -> Unit): Modifier = pointerInput(day) {
    var total = 0f
    detectHorizontalDragGestures(onDragStart = { total = 0f }, onDragCancel = { total = 0f },
        onDragEnd = { if (total > 48.dp.toPx()) previous() else if (total < -48.dp.toPx()) next() },
        onHorizontalDrag = { change, delta -> total += delta; change.consume() })
}
fun shiftLocalDay(day: Long, delta: Long): Long = Instant.ofEpochMilli(day).atZone(ZoneId.systemDefault())
    .toLocalDate().plusDays(delta).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayBrowser(day: Long, onSelect: (Long) -> Unit, latest: Long = Formatters.dayStart(System.currentTimeMillis())) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onSelect(shiftLocalDay(day, -1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "前一天") }
        TextButton(onClick = { showPicker = true }, modifier = Modifier.weight(1f)) {
            Text((if (Instant.ofEpochMilli(day).atZone(ZoneId.systemDefault()).year != LocalDate.now().year) "${Instant.ofEpochMilli(day).atZone(ZoneId.systemDefault()).year}年" else "") + Formatters.dayLabel(day), style = MaterialTheme.typography.labelLarge)
        }
        IconButton(onClick = { onSelect(shiftLocalDay(day, 1)) }, enabled = day < latest) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "后一天") }
        TextButton(onClick = { onSelect(Formatters.dayStart(System.currentTimeMillis())) }) { Text("今天") }
    }
    if (showPicker) {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(day).atZone(zone).toLocalDate()
        val maxDate = Instant.ofEpochMilli(latest).atZone(zone).toLocalDate()
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = 1900..maxDate.year, selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(maxDate)
            })
        DatePickerDialog(onDismissRequest = { showPicker = false }, confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { value ->
                onSelect(Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli())
            }; showPicker = false }) { Text("查看") }
        }, dismissButton = { TextButton(onClick = { showPicker = false }) { Text("取消") } }) { DatePicker(state) }
    }
}

@Composable
fun CompactChoice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(options[selected] + " ▾", style = MaterialTheme.typography.labelMedium) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, label -> DropdownMenuItem(text = { Text((if (index == selected) "✓ " else "") + when (label) { "时间↓" -> "时间：最新在前"; "时间↑" -> "时间：最早在前"; "金额↓" -> "金额：从高到低"; "金额↑" -> "金额：从低到高"; else -> label }) },
                onClick = { onSelect(index); expanded = false }) }
        }
    }
}
