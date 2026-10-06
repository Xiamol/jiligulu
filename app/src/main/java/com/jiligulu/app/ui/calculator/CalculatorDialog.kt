package com.jiligulu.app.ui.calculator

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.jiligulu.app.core.audio.UiSound
import com.jiligulu.app.ui.components.uiTap
import com.jiligulu.app.ui.theme.GuluBrandFont
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Optional diagnostics: input, state assignment, evaluation, sound request and actual draw. */
internal object CalculatorTrace {
    const val TAG = "CalcTrace"
    var enabled: Boolean = false
    fun mark(stage: String) {
        if (enabled) Log.d(TAG, "$stage @ ${SystemClock.uptimeMillis()}")
    }
}

private const val REPEAT_STEP_MS = 70L
private const val DELETE_REPEAT_START_MS = 200L
private const val CLEAR_HOLD_MS = 450L
private val KEYS = listOf(
    listOf("C", "(", ")", "⌫"), listOf("7", "8", "9", "÷"),
    listOf("4", "5", "6", "×"), listOf("1", "2", "3", "−"),
    listOf("0", ".", "=", "+")
)
private const val OPERATORS = "C()⌫÷×−+="

/** Owned by one pointer press; canceling its caller stops every pending repeat/clear. */
internal suspend fun repeatCalculatorDelete(
    repeatStartMillis: Long, onRepeat: () -> Unit, onClear: () -> Unit
) {
    val start = repeatStartMillis.coerceIn(0, CLEAR_HOLD_MS)
    delay(start)
    if (start == CLEAR_HOLD_MS) { onClear(); return }
    onRepeat()
    var remaining = CLEAR_HOLD_MS - start
    while (remaining > 0) {
        val wait = minOf(REPEAT_STEP_MS, remaining)
        delay(wait)
        remaining -= wait
        if (remaining == 0L) onClear() else onRepeat()
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CalculatorDialog(initial: String = "", onDismiss: () -> Unit, onUse: (String) -> Unit) {
    val soundContext = LocalContext.current.applicationContext
    val expressionFocus = remember { FocusRequester() }
    // This belongs to this dialog, never to an old IME flag from the previous screen.
    var keyboardMode by remember { mutableStateOf(false) }
    var expression by rememberSaveable { mutableStateOf(initial.take(160)) }
    var showError by rememberSaveable { mutableStateOf(false) }
    val result = remember(expression) {
        val started = SystemClock.uptimeMillis()
        DecimalCalculator.evaluate(expression).also {
            CalculatorTrace.mark("evaluate ${SystemClock.uptimeMillis() - started}ms")
        }
    }
    val amount = result.amountText
    val visibleError = result.error?.takeIf { showError ||
        (it != "继续输入，阿噜帮你算～" && it != "再补上一个右括号就好啦") }
    fun feedback(key: String) {
        CalculatorTrace.mark("state updated $key")
        CalculatorTrace.mark("sound requested")
        UiSound.calculator(soundContext)
    }
    fun press(key: String) {
        CalculatorTrace.mark("press $key")
        when (key) {
            "C" -> { expression = ""; showError = false }
            "=" -> {
                // Read current state, including input accepted before the next composition.
                val current = DecimalCalculator.evaluate(expression)
                if (current.value != null) {
                    val exact = current.value.stripTrailingZeros().toPlainString()
                    if (exact.length <= 160) expression = exact
                } else showError = true
            }
            else -> if (expression.length < 160) { expression += key; showError = false }
        }
        feedback(key)
    }
    fun deleteOne() {
        CalculatorTrace.mark("press backspace")
        expression = expression.dropLast(1)
        showError = false
        feedback("backspace")
    }
    // Stable event bridges let expression changes update callbacks without changing the keypad's props.
    val pressState = rememberUpdatedState<(String) -> Unit>(::press)
    val deleteState = rememberUpdatedState<() -> Unit>(::deleteOne)
    val clearState = rememberUpdatedState<() -> Unit>({ press("C") })
    val pressKey = remember { { key: String -> pressState.value(key) } }
    val deleteKey = remember { { deleteState.value() } }
    val clearKey = remember { { clearState.value() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Dialog owns a different text-input/focus service from the underlying Activity.
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        LaunchedEffect(keyboardMode) {
            if (keyboardMode) { expressionFocus.requestFocus(); keyboard?.show() }
            else { focusManager.clearFocus(force = true); keyboard?.hide() }
        }
        com.jiligulu.app.ui.capture.DialogGlassBackdrop()
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            if (dialogWindow?.attributes?.gravity != android.view.Gravity.CENTER) {
                dialogWindow?.setGravity(android.view.Gravity.CENTER)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Use the dialog's real available height, including landscape, split-screen and IME resize.
            val available = (maxHeight - 24.dp).coerceAtLeast(0.dp)
            val preferred = (LocalConfiguration.current.screenHeightDp * .66f).dp.coerceIn(460.dp, 492.dp)
            val height = minOf(available, if (keyboardMode) 144.dp else preferred)
            val compact = height < 440.dp
            val tiny = height < 320.dp
            val inset = if (tiny) 6.dp else if (compact) 8.dp else 10.dp
            val gap = if (tiny) 3.dp else if (compact) 4.dp else 6.dp
            Surface(
                Modifier.padding(horizontal = 12.dp, vertical = 12.dp).widthIn(max = 284.dp)
                    .fillMaxWidth().height(height).testTag("calculator-panel"),
                shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .14f))
            ) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (compact) 0.dp else 48.dp) {
                Column(Modifier.fillMaxSize().padding(inset), verticalArrangement = Arrangement.spacedBy(gap)) {
                    Row(Modifier.fillMaxWidth().height(if (tiny) 24.dp else if (compact) 28.dp else 36.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("阿噜小算盘 ✿", fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal,
                            fontSize = if (compact) 15.sp else 18.sp, maxLines = 1,
                            modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = uiTap {
                            keyboardMode = !keyboardMode
                        }, contentPadding = PaddingValues(horizontal = 6.dp),
                            modifier = Modifier.fillMaxHeight().testTag("calculator-keyboard-switch")) {
                            Text(if (keyboardMode) "算盘键" else "键盘", fontSize = 12.sp)
                        }
                        TextButton(onClick = uiTap(onDismiss), contentPadding = PaddingValues(horizontal = 6.dp),
                            modifier = Modifier.fillMaxHeight()) { Text("收起", fontSize = 12.sp) }
                    }
                    Surface(Modifier.fillMaxWidth().height(if (tiny) 28.dp else if (compact) 32.dp else 44.dp),
                        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Box(Modifier.fillMaxSize()) {
                        BasicTextField(
                            value = expression, onValueChange = { value ->
                                if (value.length <= 160 && value.all { it.isDigit() || it in ".+-−×÷*/()（） " }) {
                                    expression = value; showError = false
                                }
                            },
                            modifier = Modifier.fillMaxSize().focusRequester(expressionFocus)
                                .focusProperties { canFocus = keyboardMode }
                                .testTag("calculator-expression").padding(horizontal = 10.dp)
                                .drawWithContent { drawContent(); CalculatorTrace.mark("expression drawn") },
                            singleLine = true,
                            readOnly = !keyboardMode,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = if (tiny) 14.sp else if (compact) 16.sp else 18.sp,
                                color = MaterialTheme.colorScheme.onSurface),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { inner ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                    if (expression.isEmpty()) Text("比如 (12 + 9) × 2",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f))
                                    inner()
                                }
                            }
                        )
                        if (!keyboardMode) Box(Modifier.matchParentSize()
                            .testTag("calculator-edit-expression")
                            .clickable(role = Role.Button, onClickLabel = "用键盘编辑算式") { keyboardMode = true })
                        }
                    }
                    Surface(Modifier.fillMaxWidth().height(if (tiny) 28.dp else if (compact) 44.dp else 54.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f),
                        shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = if (tiny) 2.dp else 4.dp),
                            verticalArrangement = Arrangement.Center) {
                            Text(if (tiny && visibleError != null) visibleError else "= ${result.display ?: "0"}",
                                fontSize = if (tiny) 16.sp else if (compact) 18.sp else 20.sp,
                                lineHeight = if (tiny) 20.sp else if (compact) 22.sp else 24.sp,
                                color = if (tiny && visibleError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!tiny) Text(visibleError ?: if (amount != null) "带入金额 ¥$amount · 保留到分" else "算完直接记一笔 ♡",
                                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp),
                                color = if (visibleError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (!keyboardMode) CalculatorKeypad(pressKey, deleteKey, clearKey, compact, tiny,
                        Modifier.fillMaxWidth().weight(1f).testTag("calculator-keypad"))
                    Button(onClick = uiTap { DecimalCalculator.evaluate(expression).amountText?.let(onUse) },
                        enabled = amount != null, contentPadding = PaddingValues(horizontal = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().height(if (tiny) 32.dp else if (compact) 36.dp else 44.dp)
                            .testTag("calculator-use")) {
                        Text("¥${amount ?: "0"} · 带入记账", fontSize = if (compact) 14.sp else 16.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                }
            }
        }
    }
}

/** Five fixed rows share the actual remaining height; labels reduce before controls become cramped. */
@Composable
private fun CalculatorKeypad(onPress: (String) -> Unit, onDeleteOne: () -> Unit, onClear: () -> Unit,
    compact: Boolean, tiny: Boolean, modifier: Modifier = Modifier) {
    val gap = if (tiny) 3.dp else if (compact) 4.dp else 6.dp
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        KEYS.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { key ->
                    val press = remember(key, onPress) { { onPress(key) } }
                    CalculatorKey(key, press, onDeleteOne, onClear, compact, tiny,
                        Modifier.weight(1f).fillMaxHeight().testTag("calculator-key-$key"))
                }
            }
        }
    }
}

@Composable
private fun CalculatorKey(key: String, onPress: () -> Unit, onDelete: () -> Unit, onClear: () -> Unit,
    compact: Boolean, tiny: Boolean, modifier: Modifier = Modifier) {
    val latestDelete by rememberUpdatedState(onDelete)
    val latestClear by rememberUpdatedState(onClear)
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(if (compact) 9.dp else 12.dp)
    val color = if (key == "=") MaterialTheme.colorScheme.primary
        else if (key in OPERATORS) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f)
    val ink = if (key == "=") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val label: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(key, fontSize = if (tiny) 14.sp else if (compact) 16.sp else 18.sp, lineHeight = if (tiny) 18.sp else 22.sp)
        }
    }
    if (key != "⌫") {
        // A normal click, including a stationary long press released on the same key, commits once.
        Surface(onClick = onPress, modifier = modifier, shape = shape, color = color, contentColor = ink,
            content = label)
    } else {
        Surface(modifier = modifier.clip(shape).indication(interaction, LocalIndication.current)
            .semantics {
                role = Role.Button
                contentDescription = "删除一位，按住连删"
                onClick("删除一位") { latestDelete(); true }
                onLongClick("清空算式") { latestClear(); true }
            }
            .pointerInput(interaction) {
                detectTapGestures(onPress = { position ->
                    val press = PressInteraction.Press(position)
                    interaction.tryEmit(press)
                    coroutineScope {
                        var repeated = false
                        var released = false
                        val repeat = launch {
                            repeatCalculatorDelete(DELETE_REPEAT_START_MS,
                                onRepeat = { repeated = true; latestDelete() },
                                onClear = { repeated = true; latestClear() })
                        }
                        try {
                            released = tryAwaitRelease()
                            // Stop before dispatching any short tap; no timer survives UP or CANCEL.
                            repeat.cancelAndJoin()
                            if (released && !repeated) latestDelete()
                        } finally {
                            repeat.cancel()
                            interaction.tryEmit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                        }
                    }
                })
            }, shape = shape, color = color, contentColor = ink, content = label)
    }
}
