package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiligulu.app.core.audio.UiSound
import androidx.compose.ui.platform.LocalContext
import com.jiligulu.app.ui.theme.GuluBrandFont
import java.time.LocalDate

@Composable
internal fun XiaoLiuRenPane(input: XiaoLiuRenInput, rewritten: Boolean,
    onSelect: (XiaoLiuRenInput) -> Unit, onRewrite: () -> Unit) {
    val context = LocalContext.current
    val result = remember(input) { XiaoLiuRen.forInput(input) }
    var choosing by remember { mutableStateOf(false) }
    var method by remember { mutableStateOf(false) }
    val accent = Color(0xFF8B74A4)
    Column(Modifier.widthIn(max = 292.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (input.manualNumbers) "自选农历数字" else "${input.date.monthValue}月${input.date.dayOfMonth}日",
            style = MaterialTheme.typography.labelMedium, color = Color(0xFF9A929A))
        Text("${if (input.leapMonth) "闰" else ""}${input.lunarMonth}月${input.lunarDay}日 · ${XiaoLiuRen.branches[input.shichen - 1]}时",
            Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = SecretWoodInk)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            listOf("月" to result.month, "日" to result.day, "时" to result.hour).forEachIndexed { index, (label, palace) ->
                if (index > 0) Text("→", color = accent.copy(alpha = .5f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF9A929A))
                    Text(palace.title, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodyMedium, color = accent)
                }
            }
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LiuRenPalace.entries.forEach { palace ->
                Text(palace.title, Modifier.background(if (palace == result.hour) accent.copy(alpha = .12f) else Color.Transparent,
                    RoundedCornerShape(12.dp)).padding(horizontal = 7.dp, vertical = 7.dp), fontSize = 12.sp,
                    color = if (palace == result.hour) accent else SecretWoodInk.copy(alpha = .42f))
            }
        }
        Text("${result.hour.mark}  ${result.hour.title}", Modifier.padding(top = 16.dp).testTag("liuren-result"),
            fontFamily = GuluBrandFont, color = accent, fontSize = 30.sp)
        Box(Modifier.fillMaxWidth().height(76.dp), contentAlignment = Alignment.Center) {
            Text(if (rewritten) "阿噜已经盖好大吉章，陪你把今天过得勇敢一点 ♡" else result.hour.message,
                style = MaterialTheme.typography.bodyMedium, color = SecretWoodInk,
                textAlign = TextAlign.Center, maxLines = 3)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = { UiSound.select(context); choosing = true }, modifier = Modifier.testTag("liuren-edit")) { Text("换个日期 / 数字") }
            TextButton(onClick = { UiSound.paper(context); method = true }) { Text("起课说明") }
        }
        TextButton(onClick = onRewrite, enabled = !rewritten) {
            Text(if (rewritten) "阿噜盖过章啦 ♡" else "让阿噜逆天改命")
        }
        Text("民俗小游戏 · 仅供娱乐", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9A929A))
    }
    if (method) FortunePopup("阿噜的小六壬", { method = false }) {
        Text("按农历月、日、十二时辰顺数，每一步都把起点算作 1。", style = MaterialTheme.typography.bodyMedium)
        Text("闰月沿该月号。子时是 23:00–00:59，仍使用所选日期；时辰按设备本地时间。",
            style = MaterialTheme.typography.bodySmall, color = SecretWoodInk.copy(alpha = .7f))
        Text("今天首次条件和你自选的日期 / 数字会记住，反复打开不会随机换结果。它只是一张民俗小纸条，不替你决定生活。",
            style = MaterialTheme.typography.bodySmall, color = SecretWoodInk.copy(alpha = .7f))
    }
    if (choosing) LiuRenSelectionDialog(input, onDismiss = { choosing = false }, onSelect = {
        onSelect(it); choosing = false
    })
}

@Composable
private fun LiuRenSelectionDialog(input: XiaoLiuRenInput, onDismiss: () -> Unit, onSelect: (XiaoLiuRenInput) -> Unit) {
    var date by remember { mutableStateOf(TextFieldValue(input.date.toString())) }
    var month by remember { mutableStateOf(TextFieldValue(input.lunarMonth.toString())) }
    var day by remember { mutableStateOf(TextFieldValue(input.lunarDay.toString())) }
    var manual by remember { mutableStateOf(input.manualNumbers) }
    var shichen by remember { mutableIntStateOf(input.shichen) }
    var error by remember { mutableStateOf<String?>(null) }
    val accent = Color(0xFF8B74A4)
    SecretWoodDialog("留一个小念头", onDismiss, confirmLabel = "起一课", compactWidth = 286.dp,
        onConfirm = {
            val selectedDate = runCatching { LocalDate.parse(date.text.trim()) }.getOrNull()
            val m = month.text.trim().toIntOrNull(); val d = day.text.trim().toIntOrNull()
            if (selectedDate == null || selectedDate.year !in 1900..2100) error = "日期按 2026-10-08 填写（1900–2100年）"
            else if (manual && (m == null || m !in 1..12 || d == null || d !in 1..30)) error = "农历月填 1–12，日填 1–30"
            else {
                val result = if (manual) XiaoLiuRenInput(selectedDate, m!!, d!!, shichen, manualNumbers = true)
                    else XiaoLiuRenCalendar.forDate(selectedDate, shichen)
                onSelect(result)
            }
        }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(false to "按日期", true to "自选数字").forEach { (value, title) ->
                Text(title, Modifier.weight(1f).background(if (value == manual) accent.copy(alpha = .1f) else Color.Transparent,
                    RoundedCornerShape(12.dp)).clickable(role = Role.Tab) { manual = value; error = null }.padding(vertical = 8.dp),
                    textAlign = TextAlign.Center, color = accent, style = MaterialTheme.typography.labelLarge)
            }
        }
        Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("公历日期", Modifier.width(70.dp), style = MaterialTheme.typography.labelMedium, color = SecretWoodInk)
            BasicTextField(date, { date = it; error = null }, Modifier.weight(1f).testTag("liuren-date-input"), singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = SecretWoodInk), cursorBrush = SolidColor(accent))
        }
        if (manual) Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("农历数字", Modifier.width(70.dp), style = MaterialTheme.typography.labelMedium, color = SecretWoodInk)
            BasicTextField(month, { month = it; error = null }, Modifier.width(34.dp), singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = SecretWoodInk), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("月", style = MaterialTheme.typography.bodyMedium, color = SecretWoodInk)
            Spacer(Modifier.width(12.dp))
            BasicTextField(day, { day = it; error = null }, Modifier.width(34.dp), singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = SecretWoodInk), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("日", style = MaterialTheme.typography.bodyMedium, color = SecretWoodInk)
        }
        HorizontalDivider(color = accent.copy(alpha = .18f))
        Text("时辰", style = MaterialTheme.typography.labelMedium, color = SecretWoodInk)
        XiaoLiuRen.branches.chunked(6).forEachIndexed { row, choices ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                choices.forEachIndexed { column, branch ->
                    val number = row * 6 + column + 1
                    Text(branch, Modifier.weight(1f).height(36.dp)
                        .background(if (number == shichen) accent.copy(alpha = .14f) else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable(role = Role.RadioButton) { shichen = number }.padding(top = 8.dp),
                        textAlign = TextAlign.Center, color = if (number == shichen) accent else SecretWoodInk)
                }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    }
}
