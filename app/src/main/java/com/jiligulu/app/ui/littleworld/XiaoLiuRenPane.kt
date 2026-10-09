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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Ask → choose → a saved reading. Visiting this page never causes a new paid request. */
@Composable
internal fun XiaoLiuRenPane(store: XiaoLiuRenStore,
    rewriteStateFor: (LiuRenCast) -> LiuRenRewriteState = { LiuRenRewriteState() }, onRewrite: (LiuRenCast) -> Unit = {},
    repository: XiaoLiuRenAnalysisRepository? = null, modifier: Modifier = Modifier) {
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
    val snapshot = session.cast.takeIf { session.step == LiuRenStep.RESULT }
    val response = if (snapshot != null) {
        val value by remember(analysis, snapshot) { analysis.state(snapshot) }.collectAsStateWithLifecycle()
        value
    } else LiuRenAnalysisState()
    val rewrite = snapshot?.let(rewriteStateFor) ?: LiuRenRewriteState()
    val reading = snapshot?.let { response.reading ?: LiuRenReadingPolicy.local(it) }
    var details by remember(snapshot) { mutableStateOf(false) }
    var original by remember(snapshot, rewrite.applied) { mutableStateOf(false) }
    var errorDetails by remember(snapshot, response.error) { mutableStateOf(false) }
    val receipt = rewrite.receipt.takeUnless { original }
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp)
    FortunePageLayout(modifier = modifier.fillMaxSize(), body = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.gulu_idle), null, Modifier.size(40.dp))
            Text(when (session.step) {
                LiuRenStep.QUESTION -> "你想问哪一件小事呀？"
                LiuRenStep.METHOD -> "阿噜记住啦，我们怎么起课？"
                LiuRenStep.RESULT -> "这一课，阿噜陪你慢慢看。"
            }, Modifier.weight(1f).padding(start = 8.dp), style = bodyStyle, color = SecretWoodInk)
            IconButton(onClick = { UiSound.paper(context); help = true }, Modifier.size(44.dp)) {
                Icon(Icons.Outlined.HelpOutline, "起课说明", Modifier.size(20.dp), tint = accent)
            }
        }
        when (session.step) {
            LiuRenStep.QUESTION -> {
                BasicTextField(question, { question = it; error = null }, Modifier.fillMaxWidth()
                    .background(accent.copy(alpha = .055f), RoundedCornerShape(16.dp)).padding(12.dp)
                    .heightIn(min = 52.dp).testTag("liuren-question"), minLines = 2,
                    textStyle = bodyStyle.copy(color = SecretWoodInk), cursorBrush = SolidColor(accent),
                    decorationBox = { inner -> Box {
                        Column(Modifier.fillMaxWidth().padding(end = 32.dp)) {
                            if (question.text.isBlank()) Text("比如：明天见面，我该怎样准备？",
                                style = bodyStyle, color = ChessLobbyColors.muted)
                            inner()
                        }
                        if (question.text.isNotEmpty()) IconButton(onClick = { question = TextFieldValue(); error = null },
                            modifier = Modifier.align(Alignment.TopEnd).size(32.dp).testTag("liuren-question-clear")) {
                            Icon(Icons.Outlined.Close, "清空问题", Modifier.size(18.dp), tint = ChessLobbyColors.muted)
                        }
                    } })
            }
            LiuRenStep.METHOD -> {
                LiuRenQuestionLine(session.question)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(LiuRenMode.TIME to "按此刻", LiuRenMode.NUMBERS to "三位灵感").forEach { (mode, title) ->
                        OutlinedButton(onClick = { UiSound.select(context); update(session.copy(mode = mode)) },
                            modifier = Modifier.weight(1f).height(44.dp).testTag("liuren-mode-${mode.name}"),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (mode == session.mode) accent.copy(alpha = .12f) else Color.Transparent,
                                contentColor = if (mode == session.mode) accent else ChessLobbyColors.muted)) {
                            Text(title, style = bodyStyle)
                        }
                    }
                }
                if (session.mode == LiuRenMode.NUMBERS) {
                    BasicTextField(digits, { digits = it; error = null }, Modifier.width(180.dp)
                        .background(accent.copy(alpha = .055f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 32.dp)
                        .testTag("liuren-digits"), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = MaterialTheme.typography.titleLarge.copy(color = accent, letterSpacing = 9.sp, fontSize = 24.sp),
                        cursorBrush = SolidColor(accent), decorationBox = { inner -> Box {
                            if (digits.text.isBlank()) Text("137", color = accent.copy(alpha = .35f), letterSpacing = 9.sp)
                            inner()
                            if (digits.text.isNotEmpty()) IconButton(onClick = { digits = TextFieldValue(); error = null },
                                modifier = Modifier.align(Alignment.CenterEnd).size(32.dp).testTag("liuren-digits-clear")) {
                                Icon(Icons.Outlined.Close, "清空报数", Modifier.size(18.dp), tint = ChessLobbyColors.muted)
                            }
                        } })
                    Text("想到的三个数字 · 0 按 10 计", style = bodyStyle, color = ChessLobbyColors.muted)
                } else Text("按起课时的农历日期和本地时辰来数", style = bodyStyle, color = ChessLobbyColors.muted)
            }
            LiuRenStep.RESULT -> if (snapshot != null && reading != null) {
                val palaces = LiuRenReadingPolicy.palaces(snapshot)
                LiuRenQuestionLine(snapshot.question)
                Text(receipt?.title ?: if (response.remote) "阿噜的回答" else if (response.loading) "本地简答 · 阿噜正在看" else "本地简答",
                    Modifier.testTag("liuren-result"), style = bodyStyle, color = accent)
                Column(Modifier.fillMaxWidth().testTag("liuren-analysis"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(receipt?.conclusion ?: reading.summary,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 27.sp), color = SecretWoodInk,
                        modifier = Modifier.testTag(if (receipt == null) "liuren-summary" else "liuren-rewrite-answer"))
                    if (receipt == null) Text(reading.reason, style = bodyStyle, color = SecretWoodInk,
                        modifier = Modifier.testTag("liuren-reason"))
                    Text(receipt?.action ?: reading.advice, style = bodyStyle, color = ChessLobbyColors.muted,
                        modifier = Modifier.testTag("liuren-advice"))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    palaces.forEachIndexed { i, palace ->
                        if (i > 0) Text("→", color = accent.copy(alpha = .5f), fontSize = 14.sp)
                        Text("${LiuRenReadingPolicy.roles[i]} ${palace.title}", color = accent, fontSize = 14.sp)
                    }
                }
                Text(if (snapshot.mode == LiuRenMode.NUMBERS) "灵感 ${snapshot.digits} · ${snapshot.counts.joinToString(" / ")}" else {
                    val clock = Instant.ofEpochMilli(snapshot.capturedAtMillis).atZone(ZoneId.of(snapshot.zoneId))
                    clock.format(DateTimeFormatter.ofPattern("M/d HH:mm")) + " · " +
                        "${if (snapshot.leapMonth) "闰" else ""}${snapshot.lunarMonth}月${snapshot.lunarDay}日 ${XiaoLiuRen.branches[snapshot.shichen - 1]}时"
                }, fontSize = 12.sp, color = ChessLobbyColors.muted, modifier = Modifier.testTag("liuren-snapshot"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { UiSound.paper(context); details = !details },
                        modifier = Modifier.weight(1f).height(44.dp).testTag("liuren-details-toggle")) {
                        Text(if (details) "收起推演" else "看看推演", style = bodyStyle)
                    }
                    if (rewrite.applied) TextButton(onClick = { UiSound.paper(context); original = !original },
                        modifier = Modifier.weight(1f).height(44.dp).testTag("liuren-original-toggle")) {
                        Text(if (original) "看阿噜改命版" else "看原解读", style = bodyStyle)
                    } else Spacer(Modifier.weight(1f))
                }
                if (details) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    reading.stages.forEachIndexed { index, stage ->
                        Text("${index + 1} · ${LiuRenReadingPolicy.roles[index]} · ${stage.palace}（${LiuRenReadingPolicy.element(palaces[index]).label}）",
                            style = bodyStyle, color = accent, modifier = Modifier.testTag("liuren-stage-$index"))
                        Text(stage.text, style = bodyStyle, color = SecretWoodInk)
                        reading.links.getOrNull(index)?.let { link ->
                            Text("${link.from} → ${link.to} · ${link.relation.label}", style = bodyStyle,
                                color = ChessLobbyColors.muted, modifier = Modifier.testTag("liuren-link-$index"))
                            Text(link.text, style = bodyStyle, color = SecretWoodInk)
                        }
                    }
                }
                response.error?.let { message ->
                    TextButton(onClick = { errorDetails = !errorDetails },
                        modifier = Modifier.height(44.dp).testTag("liuren-api-error-toggle")) {
                        Text(if (errorDetails) "收起连接说明" else "解读没连上 · 查看原因", style = bodyStyle)
                    }
                    if (errorDetails) Text(message, style = bodyStyle, color = ChessLobbyColors.muted)
                }
            }
        }
        error?.let { Text(it, style = bodyStyle, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("liuren-validation-error")) }
    }, footer = {
        Column(Modifier.fillMaxWidth().testTag("liuren-footer")) {
            when (session.step) {
                LiuRenStep.QUESTION -> {
                    Spacer(Modifier.height(44.dp))
                    Button(onClick = {
                        val q = question.text.trim()
                        if (q.isBlank() || q.length > 180) error = "先留一句想问的事，180字以内就好"
                        else { UiSound.select(context); update(session.copy(question = q, step = LiuRenStep.METHOD, cast = null)) }
                    }, modifier = Modifier.fillMaxWidth().height(44.dp).testTag("liuren-next"),
                        colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("说给阿噜听", style = bodyStyle) }
                }
                LiuRenStep.METHOD -> {
                    TextButton(onClick = { update(session.copy(step = LiuRenStep.QUESTION)) },
                        modifier = Modifier.fillMaxWidth().height(44.dp).testTag("liuren-back")) { Text("再说清楚一点", style = bodyStyle) }
                    Button(onClick = { UiSound.paper(context); cast() },
                        modifier = Modifier.fillMaxWidth().height(44.dp).testTag("liuren-cast"),
                        colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("阿噜，帮我数一数", style = bodyStyle) }
                }
                LiuRenStep.RESULT -> if (snapshot != null) {
                    Row(Modifier.fillMaxWidth().height(44.dp)) {
                        TextButton(onClick = { UiSound.paper(context); analysis.request(snapshot) },
                            enabled = !response.loading && !response.remote,
                            modifier = Modifier.weight(1f).fillMaxHeight().testTag("liuren-api")) {
                            Text(if (response.remote) "已听阿噜解读" else if (response.loading) "阿噜正在看" else if (response.error == null) "听阿噜解读" else "再听阿噜讲讲",
                                style = bodyStyle)
                        }
                        TextButton(onClick = { update(session.copy(step = LiuRenStep.QUESTION, cast = null)) },
                            modifier = Modifier.weight(1f).fillMaxHeight().testTag("liuren-change-question")) { Text("换个问题", style = bodyStyle) }
                    }
                    OutlinedButton(onClick = { onRewrite(snapshot) }, enabled = rewrite.available && !rewrite.applied,
                        modifier = Modifier.fillMaxWidth().height(44.dp).testTag("liuren-rewrite")) { Text(rewrite.buttonLabel, style = bodyStyle) }
                }
            }
        }
    })
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
        .padding(horizontal = 12.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp), color = SecretWoodInk)
}
