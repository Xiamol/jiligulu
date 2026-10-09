package com.jiligulu.app.ui.littleworld

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.jiligulu.app.core.audio.UiSound
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
    Box(Modifier.fillMaxWidth().weight(1f).testTag("fortune-viewport")) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max = 340.dp).fillMaxWidth().height(48.dp).testTag("fortune-tabs")) {
                listOf("wheel" to "转一转", "luck" to "今日运势", "liuren" to "小六壬").forEach { (page, title) ->
                    TextButton(onClick = { UiSound.select(context); fortunePage = page }, modifier = Modifier.weight(1f).fillMaxHeight().testTag("fortune-tab-$page"),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = if (fortunePage == page) Color(0xFF8B74A4) else Color(0xFF9A929A))) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(title, maxLines = 1, fontSize = 16.sp, fontWeight = if (fortunePage == page) FontWeight.Medium else FontWeight.Normal)
                            Box(Modifier.padding(top = 5.dp).width(18.dp).height(2.dp)
                                .background(if (fortunePage == page) Color(0xFF8B74A4) else Color.Transparent, RoundedCornerShape(1.dp)))
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)) {
            when (fortunePage) {
            "wheel" -> FortunePageLayout(scrollBody = false, body = {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val portraitSize = minOf(104.dp, maxHeight * .24f)
                val wheelSize = minOf(boardSize, maxWidth, (maxHeight - portraitSize - 8.dp).coerceAtLeast(1.dp), 310.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SecretPrizeWheel(angle.value, wheelSize, enabled = foreground && !spinning) {
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
                Spacer(Modifier.height(8.dp))
                FortuneTellerPortrait(portraitSize, Modifier.testTag("wheel-fortune-teller"))
                }
                }
            }, footer = {
                Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                    Text(if (spinning) "好运正在绕一圈…" else resultText.ifBlank { "点点转盘，收一件小快乐" },
                        fontSize = 15.sp, lineHeight = 21.sp, color = SecretWoodInk,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                    when (task?.destination) {
                        "future" -> TextButton(onClick = { UiSound.envelope(context); onOpenFuture() }) { Text("去寄一封") }
                        "memories" -> TextButton(onClick = { UiSound.pageTurn(context); onOpenMemories() }) { Text("翻翻纪念册") }
                        "paper" -> TextButton(onClick = onOpenPaper) { Text("听句悄悄话") }
                    }
                }
            })
            "liuren" -> {
                    XiaoLiuRenPane(liuRenStore, rewriteStateFor = { cast ->
                        liuRenRewriteRevision // Observe changes from this screen or a reopened owner.
                        rewriteUsage.liuRenState(cast, date)
                    }, onRewrite = { cast ->
                        if (rewriteUsage.rewriteLiuRen(cast, date)) {
                            UiSound.pet(context); liuRenRewriteRevision++; stampEvent++
                        }
                    })
            }
            else -> FortunePageLayout(body = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${date.monthValue}月${date.dayOfMonth}日", fontSize = 14.sp, color = Color(0xFF9A929A))
                    TextButton(onClick = { UiSound.select(context); signPicker = true }) { Text(sign, fontSize = 13.sp) }
                }
                FortuneTellerPortrait(112.dp, Modifier.testTag("luck-fortune-teller"))
                Text(luck.title, Modifier.padding(vertical = 10.dp), fontFamily = com.jiligulu.app.ui.theme.GuluBrandFont,
                    fontSize = 28.sp, color = Color(0xFF8B74A4))
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    LuckStars("心情", luck.mood); LuckStars("灵感", luck.inspiration); LuckStars("相遇", luck.company)
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Color(0xFF8B74A4).copy(alpha = .12f))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    LuckAdvice("宜", luck.goodFor, Color(0xFF819D89))
                    LuckAdvice("放下", luck.letGo, Color(0xFFAA9598))
                    LuckAdvice("幸运色", luck.luckyColor, Color(0xFF9183A7))
                }
            }, footer = {
                Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                    Text(luck.message, fontSize = 13.sp, lineHeight = 19.sp,
                        color = Color(0xFF9A929A), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = {
                    if (rewriteUsage.mark(FortuneRewriteKind.HOROSCOPE, date)) {
                        UiSound.pet(context); rewrittenDay = date.toString(); stampEvent++
                    }
                }, enabled = rewrittenDay != date.toString(), modifier = Modifier.fillMaxWidth().height(44.dp).testTag("luck-rewrite")) {
                    Text(if (rewrittenDay == date.toString()) "阿噜盖过章啦 ♡" else "让阿噜逆天改命")
                }
            })
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
            FortuneTellerPortrait(150.dp, Modifier.testTag("fortune-stamp-teller"))
            Text("大吉", Modifier.testTag("fortune-stamp-title"), fontFamily = com.jiligulu.app.ui.theme.GuluBrandFont,
                fontSize = 44.sp, color = Color(0xFF8B74A4))
        }
        Spacer(Modifier.height(12.dp))
        Text("阿噜为你逆天改命", fontFamily = com.jiligulu.app.ui.theme.GuluBrandFont,
            fontSize = 22.sp, color = Color(0xFF8B74A4))
    }
}

@Composable private fun LuckStars(label: String, count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 14.sp, color = SecretWoodInk)
        Text("★".repeat(count) + "☆".repeat(5 - count), color = Color(0xFFB6A2CB), fontSize = 13.sp, letterSpacing = 1.sp)
    }
}

@Composable private fun LuckAdvice(label: String, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(60.dp), fontSize = 14.sp, color = color)
        Text(text, fontSize = 15.sp, lineHeight = 23.sp, color = SecretWoodInk)
    }
}
