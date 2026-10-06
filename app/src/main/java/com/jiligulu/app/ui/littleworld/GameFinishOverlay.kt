package com.jiligulu.app.ui.littleworld

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jiligulu.app.core.audio.UiSound
import com.jiligulu.app.ui.theme.GuluBrandFont
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job

internal const val GAME_FINISH_HOLD_MS = 1_000L
internal const val GAME_FINISH_FADE_MS = 300
internal const val GAME_FINISH_BOARD_EFFECT_MS = 1_200

private enum class FinishOverlayPhase { HIDDEN, WATERMARK, QUESTION }

/** Lifecycle interruption cancels the timer immediately, even if Compose never observes a paused frame. */
internal class GameFinishInterruption {
    private var generation = 0L
    private var active: Job? = null

    fun attach(job: Job, expectedGeneration: Long): Long? {
        if (generation != expectedGeneration || !job.isActive) return null
        if (active !== job) active?.cancel()
        active = job
        return generation
    }

    fun interrupt(): Long {
        generation++
        active?.cancel()
        active = null
        return generation
    }

    fun owns(ticket: Long, job: Job): Boolean = generation == ticket && active === job && job.isActive
    fun release(job: Job) { if (active === job) active = null }
}

/** Fresh events are issued by a completed move, never inferred from a restored final board. */
@Composable
internal fun GameFinishOverlay(
    result: GameFinishPresentation?,
    roundIdentity: Any,
    terminalIdentity: Any?,
    freshEvent: Int,
    promptRequest: Int,
    onAgain: () -> Unit,
    onExit: (() -> Unit)? = null,
    myRematchRequested: Boolean = false,
    suppressPrompt: Boolean = false,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var phase by remember(roundIdentity) { mutableStateOf(FinishOverlayPhase.HIDDEN) }
    var presentedTerminal by remember(roundIdentity) { mutableStateOf<Any?>(null) }
    var seenFresh by remember(roundIdentity) { mutableIntStateOf(0) }
    var seenPrompt by remember(roundIdentity) { mutableIntStateOf(0) }
    var dismissed by remember(roundIdentity) { mutableIntStateOf(0) }
    val opacity = remember(roundIdentity) { Animatable(0f) }
    val context = LocalContext.current
    val interruption = remember { GameFinishInterruption() }
    var interruptGeneration by remember { mutableLongStateOf(0L) }
    val latestFresh by rememberUpdatedState(freshEvent)
    val latestPrompt by rememberUpdatedState(promptRequest)
    val latestRound by rememberUpdatedState(roundIdentity)
    val latestTerminal by rememberUpdatedState(terminalIdentity)
    val latestSuppressed by rememberUpdatedState(suppressPrompt)
    val interruptLatest by rememberUpdatedState({
        interruptGeneration = interruption.interrupt()
        seenFresh = latestFresh
        seenPrompt = latestPrompt
        phase = FinishOverlayPhase.HIDDEN
        presentedTerminal = null
    })
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY)
                interruptLatest()
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); interruptLatest() }
    }

    // Every new position, round, pause or user dismissal cancels the previous timer. Events
    // received while inactive are consumed, so returning cannot reopen an old result dialog.
    val effectGeneration = interruptGeneration
    LaunchedEffect(roundIdentity, terminalIdentity, freshEvent, promptRequest, resumed, suppressPrompt, dismissed, effectGeneration) {
        val job = currentCoroutineContext().job
        val ticket = interruption.attach(job, effectGeneration) ?: return@LaunchedEffect
        fun owns() = interruption.owns(ticket, job) && resumed &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !latestSuppressed &&
            latestRound == roundIdentity && latestTerminal == terminalIdentity &&
            latestFresh == freshEvent && latestPrompt == promptRequest
        try {
            if (latestRound != roundIdentity || latestTerminal != terminalIdentity ||
                latestFresh != freshEvent || latestPrompt != promptRequest) return@LaunchedEffect
            val fresh = freshEvent > seenFresh
            val requested = promptRequest > seenPrompt
            seenFresh = freshEvent
            seenPrompt = promptRequest
            phase = FinishOverlayPhase.HIDDEN
            presentedTerminal = null
            opacity.snapTo(0f)
            if (!owns() || result == null || terminalIdentity == null) return@LaunchedEffect
            presentedTerminal = terminalIdentity
            if (requested) {
                phase = FinishOverlayPhase.QUESTION
            } else if (fresh) {
                opacity.snapTo(1f)
                if (!owns()) return@LaunchedEffect
                phase = FinishOverlayPhase.WATERMARK
                delay(GAME_FINISH_HOLD_MS)
                if (!owns()) return@LaunchedEffect
                opacity.animateTo(0f, tween(GAME_FINISH_FADE_MS))
                if (!owns()) return@LaunchedEffect
                phase = FinishOverlayPhase.QUESTION
            }
        } finally {
            interruption.release(job)
        }
    }
    fun close() { interruptLatest(); dismissed++ }

    if (result != null && terminalIdentity != null && presentedTerminal == terminalIdentity && resumed && !suppressPrompt) {
        when (phase) {
            FinishOverlayPhase.WATERMARK -> Dialog(
                onDismissRequest = { close() },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
                    dismissOnClickOutside = false),
            ) {
                // This is a transparent screen overlay; no dim rectangle or framed result card.
                val view = LocalView.current
                DisposableEffect(view) {
                    val window = (view.parent as? DialogWindowProvider)?.window
                    window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    window?.setDimAmount(0f)
                    onDispose { }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    InkFinishWatermark(result, Modifier.alpha(opacity.value).testTag("game-finish-watermark"))
                }
            }
            FinishOverlayPhase.QUESTION -> Dialog(onDismissRequest = { close() }) {
                Surface(Modifier.widthIn(max = 292.dp).fillMaxWidth().testTag("game-rematch-question"),
                    shape = RoundedCornerShape(20.dp), color = Color(0xFFF5F1E9), tonalElevation = 0.dp) {
                    Column(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("再来一局？", style = MaterialTheme.typography.titleLarge, color = SecretWoodInk)
                        Text(result.headline, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                            color = SecretWoodInk.copy(alpha = .72f))
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
                            TextButton(onClick = { UiSound.tap(context); close() },
                                colors = ButtonDefaults.textButtonColors(contentColor = SecretWoodInk)) { Text("先看棋盘") }
                            TextButton(onClick = { UiSound.select(context); close(); onAgain() }, enabled = !myRematchRequested,
                                colors = ButtonDefaults.textButtonColors(contentColor = SecretWoodInk)) {
                                Text(if (myRematchRequested) "等棋友点头" else "再来一局")
                            }
                        }
                        onExit?.let { exit ->
                            TextButton(onClick = { UiSound.navigate(context); close(); exit() },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                colors = ButtonDefaults.textButtonColors(contentColor = SecretWoodInk.copy(alpha = .72f))) { Text("收桌") }
                        }
                    }
                }
            }
            FinishOverlayPhase.HIDDEN -> Unit
        }
    }
}

@Composable
private fun InkFinishWatermark(result: GameFinishPresentation, modifier: Modifier = Modifier) {
    val word = when (result.mood) {
        FinishMood.LOSE -> "败北"
        FinishMood.DRAW -> "和局"
        FinishMood.WIN, FinishMood.SHARED -> "胜利"
    }
    val ink = Color(0xFF4F514A)
    Box(modifier.width(292.dp).height(168.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            // A few dry brush fibres and a pale paper wash keep the board visible behind the ink.
            repeat(16) { band ->
                val y = size.height * (.28f + band * .029f)
                val inset = (band % 5) * size.width * .018f
                drawLine(Color(0xFFF5F1E9).copy(alpha = .37f), Offset(size.width * .12f + inset, y),
                    Offset(size.width * .88f - inset, y - size.height * .04f),
                    size.height * .040f, cap = StrokeCap.Round)
            }
            repeat(9) { fibre ->
                val y = size.height * (.72f + fibre * .006f)
                drawLine(ink.copy(alpha = .035f), Offset(size.width * .16f, y),
                    Offset(size.width * (.77f + fibre % 3 * .016f), y - size.height * .03f), .65.dp.toPx())
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(word, fontFamily = GuluBrandFont, fontSize = 70.sp, letterSpacing = 9.sp,
                color = ink.copy(alpha = .88f), textAlign = TextAlign.Center)
            if (result.mood == FinishMood.SHARED) Text(result.headline, fontSize = 13.sp,
                color = ink.copy(alpha = .76f), textAlign = TextAlign.Center)
        }
    }
}

/** Quiet, persistent actions after the transient watermark, also used for restored games. */
@Composable
internal fun GameFinishActions(result: GameFinishPresentation, onQuestion: () -> Unit, onExit: (() -> Unit)?,
    network: Boolean = false, roomEnded: Boolean = false, secondsLeft: Int = 0,
    myRematchRequested: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("本局已结束 · ${result.headline}", style = MaterialTheme.typography.bodySmall, color = SecretWoodInk)
        Text(result.detail, style = MaterialTheme.typography.labelSmall, color = SecretWoodInk.copy(alpha = .70f),
            maxLines = 2, textAlign = TextAlign.Center)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            onExit?.let { exit -> TextButton(onClick = { UiSound.navigate(context); exit() },
                colors = ButtonDefaults.textButtonColors(contentColor = SecretWoodInk)) { Text("收桌") } }
            TextButton(onClick = { UiSound.tap(context); onQuestion() }, enabled = !myRematchRequested,
                colors = ButtonDefaults.textButtonColors(contentColor = SecretWoodInk)) {
                Text(if (myRematchRequested) "等棋友点头" else "再来一局")
            }
            if (network) Text(if (roomEnded) "棋桌已收好" else "${secondsLeft.coerceAtLeast(0)} 秒",
                style = MaterialTheme.typography.labelSmall, color = SecretWoodInk.copy(alpha = .62f))
        }
    }
}
