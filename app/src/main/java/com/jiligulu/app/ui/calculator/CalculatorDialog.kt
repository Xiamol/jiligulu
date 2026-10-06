package com.jiligulu.app.ui.calculator

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 轻量时序探针：只记「输入 → 画面更新 → 播放请求」三个时刻，用于确认连按卡顿的真正瓶颈。
 *
 * 默认关闭（release 与日常使用都不写日志）；需要排查时把 [enabled] 打开即可，
 * 避免"先优化再测量"这种本末倒置的做法。
 */
internal object CalculatorTrace {
    const val TAG = "CalcTrace"
    var enabled: Boolean = false

    fun mark(stage: String) {
        if (!enabled) return
        Log.d(TAG, "$stage @ ${SystemClock.uptimeMillis()}")
    }
}

/** 长按删除：每 [REPEAT_STEP_MS] 删一位，持续到 [CLEAR_HOLD_MS] 就整体清空。 */
private const val REPEAT_STEP_MS = 70L
private const val CLEAR_HOLD_MS = 450L

/** 同一个手势在极短时间内重复回调时，只认第一次。 */
private const val DEBOUNCE_MS = 24L

private val KEYS = listOf(
    listOf("C", "(", ")", "⌫"), listOf("7", "8", "9", "÷"),
    listOf("4", "5", "6", "×"), listOf("1", "2", "3", "−"),
    listOf("0", ".", "=", "+")
)

private const val OPERATORS = "()⌫÷×−+="

@Composable
fun CalculatorDialog(initial: String = "", onDismiss: () -> Unit, onUse: (String) -> Unit) {
    val soundContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var expression by rememberSaveable { mutableStateOf(initial.take(160)) }
    var showError by rememberSaveable { mutableStateOf(false) }

    val result = remember(expression) {
        val started = SystemClock.uptimeMillis()
        DecimalCalculator.evaluate(expression).also { CalculatorTrace.mark("evaluate ${SystemClock.uptimeMillis() - started}ms") }
    }
    val amount = result.amountText
    val visibleError = result.error?.takeIf { showError ||
        (it != "继续输入，阿噜帮你算～" && it != "再补上一个右括号就好啦") }

    // 同一手势的重复回调在这里收敛：既不吞掉真实的连续输入，也不让一次点击响两遍。
    var lastPressAt by remember { mutableStateOf(0L) }
    fun press(key: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPressAt < DEBOUNCE_MS) return
        lastPressAt = now
        CalculatorTrace.mark("press $key")
        UiSound.calculator(soundContext)
        when (key) {
            "C" -> { expression = ""; showError = false }
            "=" -> {
                if (result.value != null) {
                    // Keep the evaluated decimal, not its shorter display: a
                    // display rounding near half a cent must not change money.
                    val exact = result.value.stripTrailingZeros().toPlainString()
                    if (exact.length <= 160) expression = exact
                } else showError = true
            }
            else -> if (expression.length < 160) { expression += key; showError = false }
        }
        CalculatorTrace.mark("state updated")
    }

    fun deleteOne() {
        if (expression.isNotEmpty()) { expression = expression.dropLast(1); showError = false }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        com.jiligulu.app.ui.capture.DialogGlassBackdrop()
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.setGravity(android.view.Gravity.CENTER) }
        Surface(
            Modifier.padding(horizontal = 12.dp, vertical = 16.dp).widthIn(max = 284.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .14f))
        ) {
            // 固定布局：不再用弹簧滚动容器。算盘是单手工具，滚动会让"跟着手点"变得不确定，
            // 高度直接按屏幕可用比例定死，键盘区吃掉剩余空间（见 [CalculatorKeypad]）。
            Column(
                Modifier
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * .66f).dp)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("阿噜小算盘 ✿", fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal,
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = uiTap(onDismiss)) { Text("收起") }
                }
                // 表达式仍可用系统键盘直接输入：算盘键只是更快，不是唯一入口。
                OutlinedTextField(
                    value = expression,
                    onValueChange = { value ->
                        if (value.length <= 160 && value.all { it.isDigit() || it in ".+-−×÷*/()（） " }) {
                            expression = value; showError = false
                        }
                    },
                    Modifier.fillMaxWidth(),
                    placeholder = { Text("比如 (12 + 9) × 2", style = MaterialTheme.typography.bodyMedium) },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = RoundedCornerShape(14.dp), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
                Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f),
                    shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                        Text("= ${result.display ?: "0"}", style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                            color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (visibleError != null) visibleError
                            else if (amount != null) "带入金额 ¥$amount · 保留到分"
                            else if (result.value != null) "记账金额需要大于 0，并且在有效范围内"
                            else "加减乘除都可以，算完直接记一笔 ♡",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (visibleError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                CalculatorKeypad(
                    onPress = ::press,
                    onDeleteOne = ::deleteOne,
                    onClear = { press("C") },
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = uiTap { amount?.let(onUse) }, enabled = amount != null,
                    shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("¥${amount ?: "0"} · 带入记账")
                }
            }
        }
    }
}

/**
 * 算盘键盘，独立组件。
 *
 * 拆出来的唯一目的：**按一次键时，二十个按键不跟着一起重组**。
 * 按键只吃 `Modifier.weight` 与自身配色，回调通过 [rememberUpdatedState] 保持最新引用，
 * 因此父层重组时按键本身是"跳过"的，只有真正变化的显示区与表达式在动。
 *
 * 高度用 [modifier] 分配（父列的 weight），键有 44dp 的最小触控高度保证单手可点。
 */
@Composable
private fun CalculatorKeypad(
    onPress: (String) -> Unit,
    onDeleteOne: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KEYS.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    CalculatorKey(
                        key = key,
                        onPress = { onPress(key) },
                        onDelete = onDeleteOne,
                        onClear = onClear,
                        modifier = Modifier.weight(1f).fillMaxWidth().heightIn(min = 44.dp)
                    )
                }
            }
        }
    }
}

/**
 * 单个按键。
 *
 * 删除键是特例：点按删一位，**持续按住**每 [REPEAT_STEP_MS] 连删，到 [CLEAR_HOLD_MS] 整体清空，
 * 松手不再多删一次（长按之后的那次 onClick 只负责收尾，不重复删字符）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalculatorKey(
    key: String,
    onPress: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val latestPress by rememberUpdatedState(onPress)
    val latestDelete by rememberUpdatedState(onDelete)
    val latestClear by rememberUpdatedState(onClear)
    var holdJob by remember { mutableStateOf<Job?>(null) }
    var held by remember { mutableStateOf(false) }
    val isBackspace = key == "⌫"
    val operator = key in OPERATORS

    fun stopHold() {
        holdJob?.cancel()
        holdJob = null
    }

    Surface(
        modifier = modifier.combinedClickable(
            onClick = {
                if (isBackspace) {
                    stopHold()
                    // 长按已经处理过了，松手这次点击只收尾，不再删字符。
                    if (held) held = false else latestDelete()
                } else latestPress()
            },
            onLongClick = {
                if (isBackspace) {
                    held = true
                    stopHold()
                    holdJob = scope.launch {
                        var elapsed = 0L
                        while (elapsed < CLEAR_HOLD_MS) {
                            delay(REPEAT_STEP_MS)
                            elapsed += REPEAT_STEP_MS
                            if (elapsed >= CLEAR_HOLD_MS) { latestClear(); return@launch }
                            latestDelete()
                        }
                    }
                }
            }
        ),
        shape = RoundedCornerShape(12.dp),
        color = if (key == "=") MaterialTheme.colorScheme.primary
            else if (operator) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f),
        contentColor = if (key == "=") MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(key, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}
