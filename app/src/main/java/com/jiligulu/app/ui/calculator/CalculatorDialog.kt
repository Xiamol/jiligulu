package com.jiligulu.app.ui.calculator

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jiligulu.app.ui.theme.GuluBrandFont
import com.jiligulu.app.ui.components.SpringScrollColumn

@Composable
fun CalculatorDialog(initial: String = "", onDismiss: () -> Unit, onUse: (String) -> Unit) {
    val soundContext = LocalContext.current.applicationContext
    var expression by rememberSaveable { mutableStateOf(initial.take(160)) }
    var showError by rememberSaveable { mutableStateOf(false) }
    val result = remember(expression) { DecimalCalculator.evaluate(expression) }
    val amount = result.amountText
    val visibleError = result.error?.takeIf { showError ||
        (it != "继续输入，阿噜帮你算～" && it != "再补上一个右括号就好啦") }
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
            SpringScrollColumn(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .68f).dp).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("阿噜小算盘 ✿", fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal,
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = com.jiligulu.app.ui.components.uiTap(onDismiss)) { Text("收起") }
                }
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
                listOf(listOf("C", "(", ")", "⌫"), listOf("7", "8", "9", "÷"),
                    listOf("4", "5", "6", "×"), listOf("1", "2", "3", "−"),
                    listOf("0", ".", "=", "+")).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { key ->
                            val operator = key !in listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ".")
                            Surface(
                                onClick = {
                                    com.jiligulu.app.core.audio.UiSound.tap(soundContext)
                                    when (key) {
                                        "C" -> { expression = ""; showError = false }
                                        "⌫" -> { expression = expression.dropLast(1); showError = false }
                                        "=" -> {
                                            if (result.value != null) {
                                                // Keep the evaluated decimal, not its shorter display: a
                                                // display rounding near half a cent must not change money.
                                                val exact = result.value.stripTrailingZeros().toPlainString()
                                                if (exact.length <= 160) expression = exact
                                            }
                                            else showError = true
                                        }
                                        else -> if (expression.length < 160) { expression += key; showError = false }
                                    }
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
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
                    }
                }
                Button(onClick = com.jiligulu.app.ui.components.uiTap { amount?.let(onUse) }, enabled = amount != null,
                    shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("¥${amount ?: "0"} · 带入记账")
                }
            }
        }
    }
}
