package com.jiligulu.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jiligulu.app.core.audio.UiCue
import com.jiligulu.app.ui.components.uiTap
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal data class StatsCalendarChoice(val month: YearMonth, val selected: LocalDate? = null) {
    fun browse(target: YearMonth) = StatsCalendarChoice(target)
    fun choose(day: Int) = copy(selected = month.atDay(day))
    val confirmed: LocalDate get() = selected?.takeIf { YearMonth.from(it) == month } ?: month.atDay(1)
}

/** Statistics apply a date only after confirmation; other calendar consumers keep their own policy. */
@Composable
internal fun StatsCalendarDialog(selected: Long, initialMonth: YearMonth, onDismiss: () -> Unit, onSelect: (Long, Boolean) -> Unit) {
    val zone = ZoneId.systemDefault()
    val original = Instant.ofEpochMilli(selected).atZone(zone).toLocalDate()
    var choice by remember(initialMonth) { mutableStateOf(StatsCalendarChoice(initialMonth)) }
    val month = choice.month
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        com.jiligulu.app.ui.capture.DialogGlassBackdrop()
        Surface(Modifier.widthIn(max = 300.dp).fillMaxWidth().testTag("stats-calendar-dialog"), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(12.dp)) {
                Text("挑一个日子 ♡", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = uiTap(UiCue.PAPER) { choice = choice.browse(month.minusMonths(1)) },
                        enabled = month > YearMonth.of(1, 1)) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一个月")
                    }
                    Text("${month.year}年${month.monthValue}月", Modifier.weight(1f), textAlign = TextAlign.Center)
                    IconButton(onClick = uiTap(UiCue.PAPER) { choice = choice.browse(month.plusMonths(1)) },
                        enabled = month < YearMonth.of(9999, 12)) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一个月")
                    }
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
                            val date = if (valid) month.atDay(day) else null
                            val chosen = date != null && date == choice.selected
                            Box(Modifier.weight(1f).height(36.dp)
                                .background(if (chosen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(12.dp))
                                .then(if (valid) Modifier.testTag("stats-calendar-day-$date") else Modifier)
                                .clickable(enabled = valid, onClick = uiTap(UiCue.SELECT) { choice = choice.choose(day) }),
                                contentAlignment = Alignment.Center) {
                                if (valid) Text(day.toString(), style = MaterialTheme.typography.bodySmall,
                                    color = if (chosen || date == original) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onClick = uiTap(UiCue.CONFIRM) {
                        onSelect(choice.confirmed.atStartOfDay(zone).toInstant().toEpochMilli(), choice.selected == null)
                    }) { Text("确认") }
                }
            }
        }
    }
}
