package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Close
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Ask → choose → a saved reading. Visiting this page never causes a new paid request. */
@Composable
internal fun XiaoLiuRenPane(store: XiaoLiuRenStore,
    rewriteStateFor: (LiuRenCast) -> LiuRenRewriteState = { LiuRenRewriteState() }, onRewrite: (LiuRenCast) -> Unit = {},
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
                    .background(accent.copy(alpha = .055f), RoundedCornerShape(16.dp)).padding(12.dp).padding(end = 26.dp)
                    .heightIn(min = 52.dp).testTag("liuren-question"), maxLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = SecretWoodInk), cursorBrush = SolidColor(accent),
                    decorationBox = { inner -> Box {
                        if (question.text.isBlank()) Text("比如：明天见面，我该怎样准备？",
                            style = MaterialTheme.typography.bodyMedium, color = ChessLobbyColors.muted)
                        inner()
                        if (question.text.isNotEmpty()) IconButton(onClick = { question = TextFieldValue(); error = null },
                            modifier = Modifier.align(Alignment.TopEnd).offset(x = 26.dp).size(24.dp).testTag("liuren-question-clear")) {
                            Icon(Icons.Outlined.Close, "清空问题", Modifier.size(14.dp), tint = ChessLobbyColors.muted)
                        }
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
                            if (digits.text.isNotEmpty()) IconButton(onClick = { digits = TextFieldValue(); error = null },
                                modifier = Modifier.align(Alignment.CenterEnd).offset(x = 24.dp).size(24.dp).testTag("liuren-digits-clear")) {
                                Icon(Icons.Outlined.Close, "清空报数", Modifier.size(14.dp), tint = ChessLobbyColors.muted)
                            }
                        } })
                    Text("想到的三个数字 · 本版 0 按 10 计", style = MaterialTheme.typography.labelSmall, color = ChessLobbyColors.muted)
                } else Text("起课时锁定农历月、日和本地时辰", style = MaterialTheme.typography.labelSmall, color = ChessLobbyColors.muted)
                TextButton(onClick = { UiSound.paper(context); cast() }, modifier = Modifier.testTag("liuren-cast")) { Text("阿噜，帮我数一数") }
                TextButton(onClick = { update(session.copy(step = LiuRenStep.QUESTION)) }) { Text("再说清楚一点") }
            }
            LiuRenStep.RESULT -> session.cast?.let { snapshot ->
                val response by remember(snapshot) { analysis.state(snapshot) }.collectAsStateWithLifecycle()
                val palaces = LiuRenReadingPolicy.palaces(snapshot)
                val rewrite = rewriteStateFor(snapshot)
                var details by remember(snapshot) { mutableStateOf(false) }
                var original by remember(snapshot, rewrite.applied) { mutableStateOf(false) }
                val reading = response.reading ?: LiuRenReadingPolicy.local(snapshot)
                val receipt = rewrite.receipt.takeUnless { original }
                LiuRenQuestionLine(snapshot.question)
                Text(if (snapshot.mode == LiuRenMode.NUMBERS) "灵感 ${snapshot.digits} · ${snapshot.counts.joinToString(" / ")}" else {
                    val clock = Instant.ofEpochMilli(snapshot.capturedAtMillis).atZone(ZoneId.of(snapshot.zoneId))
                    clock.format(DateTimeFormatter.ofPattern("M/d HH:mm")) + " · " +
                        "${if (snapshot.leapMonth) "闰" else ""}${snapshot.lunarMonth}月${snapshot.lunarDay}日 ${XiaoLiuRen.branches[snapshot.shichen - 1]}时"
                }, style = MaterialTheme.typography.labelSmall, color = ChessLobbyColors.muted, modifier = Modifier.testTag("liuren-snapshot"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    palaces.forEachIndexed { i, palace ->
                        if (i > 0) Text("→", color = accent.copy(alpha = .5f), modifier = Modifier.padding(top = 15.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(LiuRenReadingPolicy.roles[i], style = MaterialTheme.typography.labelSmall, color = ChessLobbyColors.muted)
                            Text(palace.title, color = accent, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
                Text(receipt?.title ?: if (response.remote) "阿噜的回答" else if (response.loading) "本地简答 · 阿噜正在看" else "本地简答",
                    Modifier.testTag("liuren-result"), style = MaterialTheme.typography.labelMedium, color = accent)
                Column(Modifier.fillMaxWidth().testTag("liuren-analysis"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(receipt?.conclusion ?: reading.summary, style = MaterialTheme.typography.titleMedium, color = SecretWoodInk,
                        modifier = Modifier.testTag(if (receipt == null) "liuren-summary" else "liuren-rewrite-answer"))
                    if (receipt == null) Text(reading.reason, style = MaterialTheme.typography.bodyMedium, color = SecretWoodInk,
                        modifier = Modifier.testTag("liuren-reason"))
                    Text(receipt?.action ?: reading.advice, style = MaterialTheme.typography.bodySmall, color = ChessLobbyColors.muted,
                        modifier = Modifier.testTag("liuren-advice"))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { UiSound.paper(context); details = !details }, modifier = Modifier.weight(1f).testTag("liuren-details-toggle")) {
                        Text(if (details) "收起推演" else "看看推演")
                    }
                    if (rewrite.applied) TextButton(onClick = { UiSound.paper(context); original = !original }, modifier = Modifier.weight(1f).testTag("liuren-original-toggle")) {
                        Text(if (original) "看阿噜改命版" else "看原解读")
                    } else Spacer(Modifier.weight(1f))
                }
                if (details) SpringScrollColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                    reading.stages.forEachIndexed { index, stage ->
                        Spacer(Modifier.height(12.dp))
                        Text("${index + 1} · ${LiuRenReadingPolicy.roles[index]} · ${stage.palace}（${LiuRenReadingPolicy.element(palaces[index]).label}）",
                            style = MaterialTheme.typography.titleSmall, color = accent, modifier = Modifier.testTag("liuren-stage-$index"))
                        Text(stage.text, style = MaterialTheme.typography.bodyMedium, color = SecretWoodInk)
                        reading.links.getOrNull(index)?.let { link ->
                            Spacer(Modifier.height(8.dp))
                            Text("${link.from} → ${link.to} · ${link.relation.label}", style = MaterialTheme.typography.labelSmall,
                                color = ChessLobbyColors.muted, modifier = Modifier.testTag("liuren-link-$index"))
                            Text(link.text, style = MaterialTheme.typography.bodySmall, color = SecretWoodInk)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { UiSound.paper(context); analysis.request(snapshot) },
                        enabled = !response.loading && !response.remote, modifier = Modifier.weight(1f)) {
                        Text(if (response.remote) "已听阿噜解读" else if (response.loading) "阿噜正在看" else if (response.error == null) "听阿噜解读" else "再听阿噜讲讲")
                    }
                    TextButton(onClick = { update(session.copy(step = LiuRenStep.QUESTION, cast = null)) }, modifier = Modifier.weight(1f)) { Text("换个问题") }
                }
                TextButton(onClick = { onRewrite(snapshot) }, enabled = rewrite.available && !rewrite.applied,
                    modifier = Modifier.testTag("liuren-rewrite")) { Text(rewrite.buttonLabel) }
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
        Text("本版用起点、过程、趋向串看三宫；相邻五行辅助解释转折。同宫也保留三个阶段，不是所有流派统一的断法。留连与空亡采用土属性，不混入九宫。",
            style = MaterialTheme.typography.bodySmall, color = ChessLobbyColors.muted)
        Text("联网解读使用你选的 AI 服务，只发送这一问和本地盘；已有解读会保存，重看不再请求。",
            style = MaterialTheme.typography.bodySmall, color = ChessLobbyColors.muted)
        Text("改命是阿噜的鼓励彩蛋：盖大吉章，切到这件事的积极结论和行动。原课、原解读仍可看，不重新起课、不额外请求 AI。",
            style = MaterialTheme.typography.bodySmall, color = ChessLobbyColors.muted)
    }
}

@Composable private fun LiuRenQuestionLine(question: String) {
    Text(question, Modifier.fillMaxWidth().background(Color(0xFF8B74A4).copy(alpha = .04f), RoundedCornerShape(12.dp))
        .padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = SecretWoodInk, maxLines = 3)
}
