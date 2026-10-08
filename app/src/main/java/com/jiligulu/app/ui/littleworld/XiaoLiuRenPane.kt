package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jiligulu.app.R
import com.jiligulu.app.core.audio.UiSound
import com.jiligulu.app.ui.components.SpringScrollColumn
import com.jiligulu.app.ui.theme.GuluBrandFont
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Ask → choose → a saved reading. Visiting this page never causes a new paid request. */
@Composable
internal fun XiaoLiuRenPane(store: XiaoLiuRenStore, rewritten: Boolean, onRewrite: () -> Unit,
    repository: XiaoLiuRenAnalysisRepository? = null) {
    val context = LocalContext.current
    val analysis = remember(store, repository) { repository ?: XiaoLiuRenAnalysisRepository.forApp(context, store) }
    var session by remember(store) { mutableStateOf(store.session()) }
    var question by remember { mutableStateOf(TextFieldValue(session.question)) }
    var digits by remember { mutableStateOf(TextFieldValue(session.digits)) }
    var error by remember { mutableStateOf<String?>(null) }
    var help by remember { mutableStateOf(false) }
    // Read the remembered state holders at disposal, including a final edit before recomposition.
    DisposableEffect(store) { onDispose { store.saveSession(session.copy(question = question.text, digits = digits.text)) } }
    fun update(value: LiuRenSession) { session = value; store.saveSession(value); error = null }
    fun cast() {
        val q = question.text.trim()
        if (q.isBlank() || q.length > 180) { error = "先留一句想问的事，180字以内就好"; return }
        if (session.mode == LiuRenMode.NUMBERS && XiaoLiuRen.digitCounts(digits.text.trim()) == null) {
            error = "写三个 0–9 的数字就好，比如 137"; return
        }
        try {
            val snapshot = XiaoLiuRenCalendar.cast(q, session.mode, digits.text, System.currentTimeMillis(), ZoneId.systemDefault())
            update(LiuRenSession(q, session.mode, digits.text.trim(), LiuRenStep.RESULT, snapshot))
            analysis.request(snapshot)
        } catch (_: Exception) { error = "这次时刻没数清，原问题还在，再试一次吧" }
    }
    val accent = Color(0xFF8B74A4)
    Column(Modifier.widthIn(max = 292.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.gulu_idle), null, Modifier.size(42.dp))
            Text(when (session.step) {
                LiuRenStep.QUESTION -> "你想问哪一件小事呀？"
                LiuRenStep.METHOD -> "阿噜记住啦，我们怎么起课？"
                LiuRenStep.RESULT -> "这一课，阿噜陪你慢慢看。"
            }, Modifier.weight(1f).padding(start = 7.dp), style = MaterialTheme.typography.bodyMedium, color = SecretWoodInk)
            IconButton(onClick = { UiSound.paper(context); help = true }, Modifier.size(28.dp)) {
                Icon(Icons.Outlined.HelpOutline, "起课说明", Modifier.size(15.dp), tint = accent)
            }
        }
        when (session.step) {
            LiuRenStep.QUESTION -> {
                BasicTextField(question, { question = it; error = null }, Modifier.fillMaxWidth()
                    .background(accent.copy(alpha = .055f), RoundedCornerShape(16.dp)).padding(12.dp)
                    .heightIn(min = 52.dp).testTag("liuren-question"), maxLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = SecretWoodInk), cursorBrush = SolidColor(accent),
                    decorationBox = { inner -> Box {
                        if (question.text.isBlank()) Text("比如：明天见面，我该怎样准备？",
                            style = MaterialTheme.typography.bodyMedium, color = ChessLobbyColors.muted)
                        inner()
                    } })
                TextButton(onClick = {
                    val q = question.text.trim()
                    if (q.isBlank() || q.length > 180) error = "先留一句想问的事，180字以内就好"
                    else { UiSound.select(context); update(session.copy(question = q, step = LiuRenStep.METHOD, cast = null)) }
                }, modifier = Modifier.testTag("liuren-next")) { Text("说给阿噜听") }
            }
            LiuRenStep.METHOD -> {
                LiuRenQuestionLine(session.question)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(LiuRenMode.TIME to "按此刻", LiuRenMode.NUMBERS to "三位灵感").forEach { (mode, title) ->
                        TextButton(onClick = { UiSound.select(context); update(session.copy(mode = mode)) },
                            modifier = Modifier.weight(1f).testTag("liuren-mode-${mode.name}"),
                            colors = ButtonDefaults.textButtonColors(contentColor = if (mode == session.mode) accent else ChessLobbyColors.muted)) {
                            Text(title, style = if (mode == session.mode) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (session.mode == LiuRenMode.NUMBERS) {
                    BasicTextField(digits, { digits = it; error = null }, Modifier.width(150.dp).padding(vertical = 12.dp)
                        .testTag("liuren-digits"), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = MaterialTheme.typography.titleLarge.copy(color = accent, letterSpacing = 9.sp),
                        cursorBrush = SolidColor(accent), decorationBox = { inner -> Box {
                            if (digits.text.isBlank()) Text("137", color = accent.copy(alpha = .35f), letterSpacing = 9.sp)
                            inner()
                        } })
                    Text("想到的三个数字 · 本版 0 按 10 计", style = MaterialTheme.typography.labelSmall, color = ChessLobbyColors.muted)
                } else Text("起课时锁定农历月、日和本地时辰", style = MaterialTheme.typography.labelSmall, color = ChessLobbyColors.muted)
                TextButton(onClick = { UiSound.paper(context); cast() }, modifier = Modifier.testTag("liuren-cast")) { Text("阿噜，帮我数一数") }
                TextButton(onClick = { update(session.copy(step = LiuRenStep.QUESTION)) }) { Text("再说清楚一点") }
            }
            LiuRenStep.RESULT -> session.cast?.let { snapshot ->
                val response by remember(snapshot) { analysis.state(snapshot) }.collectAsStateWithLifecycle()
                val result = snapshot.result
                LiuRenQuestionLine(snapshot.question)
                Text(if (snapshot.mode == LiuRenMode.NUMBERS) "灵感 ${snapshot.digits} · ${snapshot.counts.joinToString(" / ")}" else {
                    val clock = Instant.ofEpochMilli(snapshot.capturedAtMillis).atZone(ZoneId.of(snapshot.zoneId))
                    clock.format(DateTimeFormatter.ofPattern("M/d HH:mm")) + " · " +
                        "${if (snapshot.leapMonth) "闰" else ""}${snapshot.lunarMonth}月${snapshot.lunarDay}日 ${XiaoLiuRen.branches[snapshot.shichen - 1]}时"
                }, style = MaterialTheme.typography.labelSmall, color = ChessLobbyColors.muted, modifier = Modifier.testTag("liuren-snapshot"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(result.month, result.day, result.hour).forEachIndexed { i, palace ->
                        if (i > 0) Text("→", color = accent.copy(alpha = .5f))
                        Text(palace.title, color = accent, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(result.hour.title, Modifier.testTag("liuren-result"), fontFamily = GuluBrandFont, fontSize = 26.sp, color = accent)
                if (response.loading) Text("阿噜在想怎么说…", style = MaterialTheme.typography.labelSmall, color = ChessLobbyColors.muted)
                SpringScrollColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
                    Text(response.reply, style = MaterialTheme.typography.bodyMedium, color = SecretWoodInk,
                        modifier = Modifier.testTag("liuren-analysis"))
                }
                if (rewritten) Text("阿噜给这件事盖了大吉章，先添一点勇气 ♡", style = MaterialTheme.typography.labelSmall, color = accent)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (!response.loading && !response.remote) TextButton(onClick = { UiSound.paper(context); analysis.request(snapshot) }) {
                        Text(if (response.error == null) "听阿噜解读" else "再听阿噜讲讲")
                    }
                    TextButton(onClick = { update(session.copy(step = LiuRenStep.QUESTION, cast = null)) }) { Text("换个问题") }
                }
                TextButton(onClick = onRewrite, enabled = !rewritten) { Text(if (rewritten) "阿噜盖过章啦 ♡" else "让阿噜逆天改命") }
                response.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = ChessLobbyColors.muted) }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    }
    if (help) FortunePopup("阿噜的小六壬", { help = false }) {
        Text("这是民俗娱乐，阿噜会结合你的问题陪你整理想法，结果不替你作决定。", style = MaterialTheme.typography.bodyMedium)
        Text("此刻法按农历月、日、十二时辰顺数；报数法用你想到的三个数字。两种都把每段起点算作 1。",
            style = MaterialTheme.typography.bodySmall, color = ChessLobbyColors.muted)
        Text("本版数字 0 按 10 计。闰月沿该月号；子时 23:00–00:59 仍用本次日期。计算时刻会保留，换页不会重新起课。",
            style = MaterialTheme.typography.bodySmall, color = ChessLobbyColors.muted)
        Text("联网解读使用你选的 AI 服务，只发送这一问和本地盘；已有解读会保存，重看不再请求。",
            style = MaterialTheme.typography.bodySmall, color = ChessLobbyColors.muted)
    }
}

@Composable private fun LiuRenQuestionLine(question: String) {
    Text(question, Modifier.fillMaxWidth().background(Color(0xFF8B74A4).copy(alpha = .04f), RoundedCornerShape(12.dp))
        .padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = SecretWoodInk, maxLines = 3)
}
