package com.jiligulu.app.ui.littleworld

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.jiligulu.app.R
import com.jiligulu.app.core.audio.UiSound
import com.jiligulu.app.ui.components.SpringScrollColumn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.Duration
import kotlin.random.Random

@Composable
internal fun ColumnScope.FortuneWheelGame(boardSize: Dp, foreground: Boolean,
    onOpenFuture: () -> Unit, onOpenMemories: () -> Unit, onOpenPaper: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("gulu_daily_luck", Context.MODE_PRIVATE) }
    val rewriteUsage = remember(prefs) { FortuneRewriteUsage(prefs) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var fortunePage by rememberSaveable { mutableStateOf("wheel") }
    val liuRenStore = remember(context) { XiaoLiuRenStore(context.getSharedPreferences("gulu_xiao_liuren", Context.MODE_PRIVATE)) }
    var sign by rememberSaveable { mutableStateOf(prefs.getString("sign", DailyLuckEngine.signs.first()).orEmpty()) }
    var rewrittenDay by remember { mutableStateOf(prefs.getString("rewritten_day", "").orEmpty()) }
    var liuRenRewriteRevision by remember { mutableIntStateOf(0) }
    var signPicker by remember { mutableStateOf(false) }
    var stampEvent by remember { mutableIntStateOf(0) }
    var resultText by rememberSaveable { mutableStateOf(prefs.getString("last_task", "").orEmpty()) }
    var savedAngle by rememberSaveable { mutableFloatStateOf(0f) }
    val angle = remember { Animatable(savedAngle) }
    var spinning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val luck = remember(date, sign, rewrittenDay) { DailyLuckEngine.forDate(date, sign, rewrittenDay == date.toString()) }
    val task = FortuneWheelTasks.groups.flatten().firstOrNull { it.text == resultText }
    LaunchedEffect(foreground) {
        while (foreground) {
            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            date = now.atZone(zone).toLocalDate()
            val midnight = date.plusDays(1).atStartOfDay(zone).toInstant()
            delay((Duration.between(now, midnight).toMillis() + 50).coerceAtLeast(1_000))
        }
    }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == FortuneRewriteKind.LIU_REN.preferenceKey || key == FortuneRewriteUsage.LIU_REN_RECEIPTS_KEY)
                liuRenRewriteRevision++
            if (key == FortuneRewriteKind.HOROSCOPE.preferenceKey)
                rewrittenDay = prefs.getString(FortuneRewriteKind.HOROSCOPE.preferenceKey, "").orEmpty()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(spinning, foreground) {
        if (spinning && foreground) {
            var last = (angle.value / 60f).toInt()
            snapshotFlow { (angle.value / 60f).toInt() }.collect { next ->
                if (next != last) { UiSound.wheelTick(context); last = next }
            }
        }
    }
    LaunchedEffect(foreground) {
        if (!foreground && spinning) { angle.stop(); savedAngle = angle.value % 360; spinning = false }
    }
    if (signPicker) FortunePopup("挑一颗小星座", { signPicker = false }, width = 286.dp) {
        DailyLuckEngine.signs.chunked(3).forEach { choices ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEach { value ->
                    TextButton(onClick = { UiSound.select(context); sign = value; prefs.edit().putString("sign", value).apply(); signPicker = false },
                        modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColors(contentColor = SecretWoodInk)) {
                        Text(value, fontSize = 13.sp, maxLines = 1)
                    }
                }
                repeat(3 - choices.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        val compact = maxHeight < 520.dp
        val availableHeight = maxHeight
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max = 320.dp).fillMaxWidth()) {
                listOf("wheel" to "转一转", "luck" to "今日运势", "liuren" to "小六壬").forEach { (page, title) ->
                    TextButton(onClick = { UiSound.select(context); fortunePage = page }, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = if (fortunePage == page) Color(0xFF8B74A4) else Color(0xFF9A929A))) {
                        Text(title, maxLines = 1, style = if (fortunePage == page) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(if (compact) 12.dp else 24.dp))
            if (fortunePage == "wheel") {
                SecretPrizeWheel(angle.value, minOf(boardSize, if (compact) 270.dp else 310.dp), enabled = foreground && !spinning) {
                    if (!spinning && foreground) {
                        UiSound.select(context); spinning = true
                        scope.launch {
                            try {
                                val winner = Random.nextInt(FortuneWheelTasks.groups.size)
                                val stop = 360f - (winner * 60f + 30f)
                                angle.animateTo(angle.value + 1800f + (stop - angle.value % 360f + 360f) % 360f,
                                    tween(1900, easing = FastOutSlowInEasing))
                                savedAngle = angle.value % 360f
                                resultText = FortuneWheelTasks.groups[winner].random().text
                                prefs.edit().putString("last_task", resultText).apply()
                            } finally { spinning = false }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth().height(42.dp), contentAlignment = Alignment.Center) {
                    Text(if (spinning) "好运正在绕一圈…" else resultText.ifBlank { "点点转盘，收一件小快乐" },
                        style = MaterialTheme.typography.bodyMedium, color = SecretWoodInk,
                        textAlign = TextAlign.Center, maxLines = 2)
                }
                Box(Modifier.height(46.dp), contentAlignment = Alignment.Center) {
                    when (task?.destination) {
                        "future" -> TextButton(onClick = { UiSound.envelope(context); onOpenFuture() }) { Text("去寄一封") }
                        "memories" -> TextButton(onClick = { UiSound.pageTurn(context); onOpenMemories() }) { Text("翻翻纪念册") }
                        "paper" -> TextButton(onClick = onOpenPaper) { Text("听句悄悄话") }
                    }
                }
            } else if (fortunePage == "liuren") {
                SpringScrollColumn(Modifier.fillMaxWidth().heightIn(max = (availableHeight - 72.dp).coerceAtLeast(1.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally, handOffOnRepeat = true) {
                    XiaoLiuRenPane(liuRenStore, rewriteStateFor = { cast ->
                        liuRenRewriteRevision // Observe changes from this screen or a reopened owner.
                        rewriteUsage.liuRenState(cast, date)
                    }, onRewrite = { cast ->
                        if (rewriteUsage.rewriteLiuRen(cast, date)) {
                            UiSound.pet(context); liuRenRewriteRevision++; stampEvent++
                        }
                    })
                }
            } else {
                Row(Modifier.widthIn(max = 290.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${date.monthValue}月${date.dayOfMonth}日 · 趣味小黄历", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A929A))
                    TextButton(onClick = { UiSound.select(context); signPicker = true }) { Text(sign, fontSize = 13.sp) }
                }
                Text(luck.title, Modifier.padding(vertical = 8.dp), fontFamily = com.jiligulu.app.ui.theme.GuluBrandFont,
                    fontSize = 28.sp, color = Color(0xFF8B74A4))
                LuckStars("心情", luck.mood); LuckStars("灵感", luck.inspiration); LuckStars("相遇", luck.company)
                Spacer(Modifier.height(14.dp))
                Column(Modifier.widthIn(max = 282.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LuckAdvice("宜", luck.goodFor, Color(0xFF819D89))
                    LuckAdvice("放下", luck.letGo, Color(0xFFAA9598))
                    LuckAdvice("幸运色", luck.luckyColor, Color(0xFF9183A7))
                }
                Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                    Text(luck.message, Modifier.widthIn(max = 272.dp), style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9A929A), textAlign = TextAlign.Center)
                }
                TextButton(onClick = {
                    if (rewriteUsage.mark(FortuneRewriteKind.HOROSCOPE, date)) {
                        UiSound.pet(context); rewrittenDay = date.toString(); stampEvent++
                    }
                }, enabled = rewrittenDay != date.toString()) {
                    Text(if (rewrittenDay == date.toString()) "阿噜盖过章啦 ♡" else "让阿噜逆天改命")
                }
            }
        }
        if (stampEvent > 0) FortuneStampOverlay(stampEvent, { stampEvent = 0 })
    }
}

/** This effect exists only after a successful stamp; reopening a receipt never replays it. */
@Composable
private fun BoxScope.FortuneStampOverlay(event: Int, onFinished: () -> Unit) {
    val scale = remember { Animatable(1.38f) }
    val rotation = remember { Animatable(-12f) }
    val opacity = remember { Animatable(1f) }
    LaunchedEffect(event) {
        scale.snapTo(1.38f); rotation.snapTo(-12f); opacity.snapTo(1f)
        coroutineScope {
            launch { scale.animateTo(1f, tween(230, easing = FastOutSlowInEasing)) }
            launch { rotation.animateTo(-4f, tween(230, easing = FastOutSlowInEasing)) }
        }
        delay(700)
        opacity.animateTo(0f, tween(360))
        onFinished()
    }
    Column(Modifier.matchParentSize().testTag("fortune-stamp-overlay").graphicsLayer { alpha = opacity.value }
        .background(Color(0xFFFAF7F0).copy(alpha = .94f)),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.graphicsLayer {
            scaleX = scale.value; scaleY = scale.value; rotationZ = rotation.value
        }, horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.gulu_luck_stamp), null, Modifier.size(150.dp))
            Text("大吉", Modifier.testTag("fortune-stamp-title"), fontFamily = com.jiligulu.app.ui.theme.GuluBrandFont,
                fontSize = 44.sp, color = Color(0xFF8B74A4))
        }
        Spacer(Modifier.height(12.dp))
        Text("阿噜为你逆天改命", fontFamily = com.jiligulu.app.ui.theme.GuluBrandFont,
            fontSize = 22.sp, color = Color(0xFF8B74A4))
    }
}

@Composable private fun LuckStars(label: String, count: Int) {
    Row(Modifier.width(228.dp).height(31.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = SecretWoodInk)
        Text("★".repeat(count) + "☆".repeat(5 - count), color = Color(0xFFB6A2CB), fontSize = 20.sp, letterSpacing = 4.sp)
    }
}

@Composable private fun LuckAdvice(label: String, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(54.dp), style = MaterialTheme.typography.labelMedium, color = color)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = SecretWoodInk)
    }
}
