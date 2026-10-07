package com.jiligulu.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.ai.AiUsageSnapshot
import com.jiligulu.app.core.ai.AiUsageTotals
import com.jiligulu.app.ui.components.GuluDialog
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun AiUsageSettings() {
    val app = LocalContext.current.applicationContext as? JiliguluApp ?: return
    val pageActive = LocalSettingPageActive.current
    val snapshot: AiUsageSnapshot? by app.container.aiUsage.snapshots.collectAsStateWithLifecycle(initialValue = null)
    var show by rememberSaveable { mutableStateOf(false) }
    var lifetime by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pageActive) { if (!pageActive) show = false }
    val lifecycleOwner = LocalLifecycleOwner.current
    val day by produceState(LocalDate.now(), lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = LocalDate.now()
                delay(60_000L)
            }
        }
    }
    val today = snapshot?.forDay(day.toString())
    Row(
        Modifier.fillMaxWidth().testTag("ai-usage-entry").clickable { show = true }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("阿噜的用量小账本", style = MaterialTheme.typography.bodyLarge)
            Text(today?.let { "今日约 ${money(it.estimatedYuan)} · 缓存 ${rate(it)}" } ?: "正在读取本地用量…",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "查看 AI 用量", tint = MaterialTheme.colorScheme.primary)
    }
    if (show && pageActive) GuluDialog("用量小账本 ✨", onDismiss = { show = false }, compact = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !lifetime, onClick = com.jiligulu.app.ui.components.uiTap(com.jiligulu.app.core.audio.UiCue.SELECT) { lifetime = false }, label = { Text("今天") })
            FilterChip(selected = lifetime, onClick = com.jiligulu.app.ui.components.uiTap(com.jiligulu.app.core.audio.UiCue.SELECT) { lifetime = true }, label = { Text("累计") })
        }
        val totals = if (lifetime) snapshot?.total else today
        if (totals != null) {
            Text(money(totals.estimatedYuan), style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("本机预估费用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            UsageLine("缓存命中", "${tokens(totals.cacheHit)} · ${rate(totals)}")
            UsageLine("未命中输入", tokens(totals.cacheMiss))
            UsageLine("输出", tokens(totals.output))
            if (totals.unclassifiedInput > 0) UsageLine("缓存情况未提供的输入", tokens(totals.unclassifiedInput))
            UsageLine("请求次数（含重试）", "${totals.calls} 次")
            if (totals.calls > totals.reportedCalls) Text(
                "${totals.calls - totals.reportedCalls} 次请求未收到用量，费用未计入。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("估算单价（元 / 百万 tokens）\n缓存输入 0.02 · 未命中输入 1 · 输出 4",
            style = MaterialTheme.typography.bodySmall)
        Text("${snapshot?.since?.takeIf { it.isNotBlank() }?.let { "自 $it 起，" }.orEmpty()}仅汇总这台设备收到的用量；不保存聊天内容。缓存率按输入 token 加权，缓存情况未知时按未命中估算。失败或中断请求可能缺少用量，实际扣费以服务商账单为准。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UsageLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

private fun money(value: Double) = "¥" + String.format(Locale.ROOT, "%.5f", value)
private fun rate(value: AiUsageTotals) = value.hitRate?.let { String.format(Locale.ROOT, "%.1f%%", it * 100) } ?: "暂无"
private fun tokens(value: Long) = String.format(Locale.ROOT, "%,d", value)
