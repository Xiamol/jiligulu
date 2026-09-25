package com.jiligulu.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

@Composable
fun CompactCalendarDialog(selected: Long, onDismiss: () -> Unit, onSelect: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(selected).atZone(zone).toLocalDate()
    var month by remember { mutableStateOf(YearMonth.from(date)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.88f).widthIn(max = 320.dp), shape = RoundedCornerShape(26.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("挑一个日子 ♡", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { month = month.minusMonths(1) }, enabled = month.year > 1900) { Text("‹") }
                    Text("${month.year}年${month.monthValue}月", Modifier.weight(1f), textAlign = TextAlign.Center)
                    TextButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.now(zone)) { Text("›") }
                }
                Row { listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                    Text(it, Modifier.weight(1f).padding(vertical = 6.dp), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                val offset = month.atDay(1).dayOfWeek.value - 1
                repeat((offset + month.lengthOfMonth() + 6) / 7) { week ->
                    Row {
                        repeat(7) { weekday ->
                            val day = week * 7 + weekday - offset + 1
                            val valid = day in 1..month.lengthOfMonth()
                            val chosen = valid && month.atDay(day) == date
                            Box(Modifier.weight(1f).height(36.dp)
                                .background(if (chosen) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable(enabled = valid) { onSelect(month.atDay(day).atStartOfDay(zone).toInstant().toEpochMilli()) }, contentAlignment = Alignment.Center) {
                                if (valid) Text(day.toString(), style = MaterialTheme.typography.bodySmall,
                                    color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
                TextButton(onClick = { onSelect(java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()) },
                    modifier = Modifier.align(Alignment.End)) { Text("回到今天") }
            }
        }
    }
}
