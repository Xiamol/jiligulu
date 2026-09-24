package com.jiligulu.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiligulu.app.core.util.Formatters
import java.time.*

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
    val sorting = options.firstOrNull()?.startsWith("时间") == true
    Box(Modifier.padding(start = 5.dp)) {
        Surface(onClick = { expanded = true }, shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .4f),
            border = BorderStroke(.7.dp, MaterialTheme.colorScheme.primary.copy(alpha = .12f))) {
            Row(Modifier.padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (sorting) Icon(Icons.Outlined.Tune, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Text(options[selected], style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Icon(Icons.Default.ExpandMore, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, shape = RoundedCornerShape(22.dp),
            containerColor = MaterialTheme.colorScheme.surface) {
            Text(if (sorting) "排个顺眼的队 ♡" else "翻翻哪一边的小账本？", Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            options.forEachIndexed { index, label ->
                DropdownMenuItem(
                    modifier = Modifier.padding(horizontal = 6.dp).background(
                        if (index == selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f) else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(14.dp)),
                    text = { Text(when (label) { "时间↓" -> "最新在前"; "时间↑" -> "最早在前"; "金额↓" -> "金额从高到低"; "金额↑" -> "金额从低到高"; else -> label }, style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = { Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .4f), CircleShape), contentAlignment = Alignment.Center) {
                        Text(if (sorting) { if (index < 2) "◷" else "¥" } else listOf("♡", "−", "+")[index], color = MaterialTheme.colorScheme.primary)
                    } },
                    trailingIcon = { if (index == selected) Icon(Icons.Default.Check, "已选中", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
                    onClick = { onSelect(index); expanded = false })
            }
        }
    }
}
