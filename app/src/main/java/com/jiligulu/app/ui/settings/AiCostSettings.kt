package com.jiligulu.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.ai.*
import com.jiligulu.app.data.prefs.AiCostGroup
import com.jiligulu.app.data.prefs.AiProviderState
import com.jiligulu.app.ui.components.CompactFormField
import com.jiligulu.app.ui.components.GuluDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

private data class CostView(val daily: List<AiCostGroup> = emptyList(), val total: List<AiCostGroup> = emptyList(), val failed: Boolean = false)

@Composable
fun AiUsageSettings() {
    val app = LocalContext.current.applicationContext as? JiliguluApp ?: return
    val repo = app.container.aiUsage
    val provider by app.container.aiProviders.state.collectAsStateWithLifecycle(initialValue = AiProviderState())
    val profile = provider.selectedProfile
    val key = remember(profile) { runCatching { AiUsageTicket.providerKey(profile) }.getOrNull() }
    val revision by repo.revisions.collectAsStateWithLifecycle()
    val active = LocalSettingPageActive.current
    var show by rememberSaveable { mutableStateOf(false) }
    var priceEditor by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var days by rememberSaveable { mutableIntStateOf(7) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var scope by rememberSaveable { mutableIntStateOf(0) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    val month = YearMonth.parse(monthText)
    val end = if (month == YearMonth.now()) LocalDate.now() else month.atEndOfMonth()
    val start = end.minusDays((days - 1).toLong())
    val group = if (scope >= 1) runCatching { AiUsageTicket.providerGroup(profile) }.getOrDefault("unconfigured") else null
    val filterKey = if (scope == 2) key ?: "unconfigured" else null
    val view by produceState<CostView?>(null, revision, start, end, group, filterKey) {
        value = try { CostView(repo.daily(start, end, group, filterKey), repo.total(group, filterKey)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CostView(failed = true) }
    }
    LaunchedEffect(active) { if (!active) { show = false; priceEditor = false; help = false } }
    Row(Modifier.fillMaxWidth().testTag("ai-usage-entry").clickable { show = true }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("AI 花销小账本", style = MaterialTheme.typography.bodyLarge)
            Text(view?.let { if (it.failed) "本地记录暂时不可读" else "累计已知估算 ${money(known(it.total))} · ${unknown(it.total)} 次未能完整估算" }
                ?: "正在读取本地记录…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("查看 ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
    }
    if (show && active) GuluDialog("AI 花销", onDismiss = { show = false }, compact = true, dense = true, compactWidth = 340.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("全部", "该供应商", "当前模型").forEachIndexed { index, text ->
                FilterChip(selected = scope == index, onClick = { scope = index; selectedDay = null },
                    enabled = index != 2 || key != null, label = { Text(text, fontSize = 11.sp) })
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { monthText = month.minusMonths(1).toString(); selectedDay = null }) { Text("‹") }
            Text(monthText, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { monthText = month.plusMonths(1).toString(); selectedDay = null }, enabled = month < YearMonth.now()) { Text("›") }
            listOf(7, 30).forEach { count -> FilterChip(days == count, { days = count; selectedDay = null }, label = { Text("${count}日", fontSize = 11.sp) }) }
        }
        Text("${start.monthValue}/${start.dayOfMonth}—${end.monthValue}/${end.dayOfMonth} · 按已配置单价估算（人民币）",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val ready = view
        if (ready == null) Text("正在读取…")
        else if (ready.failed) Text("暂时无法读取本地费用，未把失败当作零。", color = MaterialTheme.colorScheme.error)
        else {
            CostChart(start, days, ready.daily, selectedDay) { selectedDay = it.toString() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("区间已知 ${money(known(ready.daily))}", style = MaterialTheme.typography.labelSmall)
                Text("${unknown(ready.daily)} 次含未知部分", style = MaterialTheme.typography.labelSmall)
            }
            PurposeLegend()
            HorizontalDivider()
            Text("累计 · 已知估算 ${money(known(ready.total))}", style = MaterialTheme.typography.titleSmall)
            Text("${ready.total.sumOf { it.calls }} 次实际尝试（含重试） · ${unknown(ready.total)} 次费用不完整", style = MaterialTheme.typography.labelSmall)
            ready.total.forEach { PurposeCostLine(it) }
            if (ready.total.sumOf { it.legacyCalls } > 0) Text("旧汇总没有用途与当时单价，保留为用途未知；未套新价重算。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            selectedDay?.let { day -> DayCostDetails(LocalDate.parse(day), revision, group, filterKey) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { priceEditor = true }, enabled = key != null, modifier = Modifier.testTag("ai-price-editor")) { Text("单价") }
            TextButton(onClick = { help = true }) { Text("说明") }
        }
    }
    if (priceEditor && active) AiPriceEditor(profile, onDismiss = { priceEditor = false })
    if (help && active) GuluDialog("费用怎样记录", { help = false }, compact = true, dense = true) {
        Text("每次真正发起的 HTTP 尝试各计一次，包括重试与多轮查询。回复 JSON 不合格也不会重复计量；响应没报告用量时保留未知。")
        Text("只按请求开始时保存的供应商、模型和单价快照计算已知部分。改价只影响之后的请求。缺少缓存拆分时，不擅自把全部输入当未命中；图上的问号表示该日存在未知费用。")
        Text("DeepSeek Flash 默认采用你提供的 0.02 / 1 / 4 元参考单价；其它模型与自定义服务需单独配置。服务商可能按时段、折扣或其它规则实扣，此处不是账单，也不额外查余额。")
        Text("柱图只查询所选月份附近的 7/30 日，累计由数据库聚合；仅保存用量、用途、模型与价格元数据，不保存消息、照片、接口地址或密钥。")
    }
}

@Composable
private fun DayCostDetails(day: LocalDate, revision: Long, group: String?, key: String?) {
    val repo = (LocalContext.current.applicationContext as JiliguluApp).container.aiUsage
    val rows by produceState<List<AiCostGroup>?>(null, day, revision, group, key) {
        value = try { repo.details(day, group, key) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    }
    HorizontalDivider()
    Text("${day.monthValue}月${day.dayOfMonth}日明细", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("ai-cost-day-details"))
    rows?.let { data ->
        if (data.isEmpty()) Text("这一天没有记录的请求。", style = MaterialTheme.typography.bodySmall)
        data.forEach { row ->
            PurposeCostLine(row)
            Text("${row.providerName} · ${row.model}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CostPart("缓存命中", row.cacheHit, row.cachePico, row.cacheHitReportedCalls > 0 || row.cacheHit > 0)
            CostPart("未命中输入", row.cacheMiss, row.missPico, row.cacheMissReportedCalls > 0 || row.cacheMiss > 0)
            CostPart("输出", row.output, row.outputPico, row.outputReportedCalls > 0 || row.output > 0)
            if (row.unclassifiedInput > 0) CostPart("缓存未分类输入", row.unclassifiedInput, row.flatPico)
            if (row.unknownCalls > 0) Text("${row.unknownCalls} 次用量或计价有未知部分，以上仅为已知小计。", style = MaterialTheme.typography.labelSmall)
        }
    } ?: Text("明细暂未读取，未知不计为零。", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun CostPart(label: String, tokens: Long, amount: Long?, reported: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$label · ${if (reported) "$tokens tokens" else "未报告"}", style = MaterialTheme.typography.labelSmall)
        Text(money(amount), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PurposeCostLine(row: AiCostGroup) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(8.dp).background(purposeColor(row.purpose)))
        Text("${row.purpose.label} · ${row.calls}次", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(money(row.knownPico) + if (row.unknownCalls > 0) " + ?" else "", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PurposeLegend() {
    AiUsagePurpose.entries.chunked(3).forEach { entries -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { purpose -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.size(6.dp).background(purposeColor(purpose))); Text(purpose.label, fontSize = 10.sp)
        } }
    } }
}

@Composable
private fun CostChart(start: LocalDate, days: Int, rows: List<AiCostGroup>, selected: String?, onSelect: (LocalDate) -> Unit) {
    val dates = remember(start, days) { (0 until days).map { start.plusDays(it.toLong()) } }
    val grouped = remember(rows) { rows.groupBy { it.day } }
    val maximum = dates.maxOfOrNull { known(grouped[it.toString()].orEmpty()) ?: 0L }?.coerceAtLeast(1) ?: 1L
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(150.dp).testTag("ai-cost-chart")
        .semantics { contentDescription = "按用途堆叠的每日已知估算费用图，问号表示未知部分" }
        .pointerInput(dates) { detectTapGestures { point ->
            onSelect(dates[(point.x / size.width * days).toInt().coerceIn(0, days - 1)])
        } }) {
        val slot = size.width / days
        val width = slot * .7f
        dates.forEachIndexed { index, date ->
            val data = grouped[date.toString()].orEmpty()
            var bottom = size.height - 9.dp.toPx()
            data.forEach { row ->
                val value = row.knownPico ?: 0
                if (value > 0) {
                    val height = (value.toDouble() / maximum * (size.height - 25.dp.toPx())).toFloat()
                    drawRect(purposeColor(row.purpose), Offset(index * slot + (slot - width) / 2, bottom - height), Size(width, height))
                    bottom -= height
                }
            }
            if (unknown(data) > 0) drawRect(tint, Offset(index * slot + (slot - width) / 2, bottom - 8.dp.toPx()), Size(width, 8.dp.toPx()), style = Stroke(1.dp.toPx()))
            if (selected == date.toString()) drawLine(tint, Offset(index * slot + slot / 2, 0f), Offset(index * slot + slot / 2, size.height), 1.dp.toPx())
        }
    }
    Row(Modifier.fillMaxWidth()) { dates.forEachIndexed { index, date ->
        val pending = unknown(grouped[date.toString()].orEmpty())
        val dateLabel = if (days == 7 || index % 5 == 0 || index == days - 1) date.dayOfMonth.toString() else "·"
        val label = if (pending > 0 && days == 7) "$dateLabel?" else if (pending > 0 && dateLabel == "·") "?" else dateLabel
        // The canvas owns day selection. Thirty separately enlarged tap targets shift
        // and overlap narrow date slots, so labels stay centered over their bars.
        Text(label, Modifier.weight(1f), fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 1,
            color = if (pending > 0) tint else MaterialTheme.colorScheme.primary)
    } }
}

@Composable
private fun AiPriceEditor(profile: AiProviderProfile, onDismiss: () -> Unit) {
    val repo = (LocalContext.current.applicationContext as JiliguluApp).container.aiUsage
    val scope = rememberCoroutineScope()
    var cache by remember { mutableStateOf("0.02") }; var miss by remember { mutableStateOf("1") }; var output by remember { mutableStateOf("4") }
    var loading by remember { mutableStateOf(true) }; var saving by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(profile) {
        try { repo.configuredPrice(profile)?.let { price ->
            fun text(value: Long) = BigDecimal.valueOf(value, 6).stripTrailingZeros().toPlainString()
            cache = text(price.cacheRateMicros); miss = text(price.missRateMicros); output = text(price.outputRateMicros)
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "暂未读到单价，请重试" } finally { loading = false }
    }
    GuluDialog("${profile.name} · ${profile.model} 单价", onDismiss, confirmLabel = "保存单价", compact = true, dense = true,
        busy = saving, confirmEnabled = !loading, onConfirm = { scope.launch {
            saving = true
            try { repo.setPrice(profile, AiPriceSnapshot.configured(cache, miss, output)); onDismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "未能保存单价" }
            finally { saving = false }
        } }) {
        Text("单位：人民币元 / 每百万 tokens。每个供应商与模型独立；保存只影响之后的请求。", style = MaterialTheme.typography.bodySmall)
        CompactFormField("缓存命中", cache, { cache = it.take(18) }, prefix = "¥", singleLine = true)
        CompactFormField("未命中输入", miss, { miss = it.take(18) }, prefix = "¥", singleLine = true)
        CompactFormField("输出", output, { output = it.take(18) }, prefix = "¥", singleLine = true)
        Text("这是参考单价，不是服务商实扣。美元报价请自行填入人民币参考值；未配置的服务不会套用此价。", style = MaterialTheme.typography.labelSmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun purposeColor(purpose: AiUsagePurpose): Color = when (purpose) {
    AiUsagePurpose.LEDGER_CHAT -> Color(0xFF638BD0); AiUsagePurpose.IMAGE_RECOGNITION -> Color(0xFFE49A5B)
    AiUsagePurpose.CLASSIFICATION -> Color(0xFF9979C6); AiUsagePurpose.HEART_LETTER -> Color(0xFFCF7F9C)
    AiUsagePurpose.LIU_REN -> Color(0xFF6DA78E); AiUsagePurpose.UNSPECIFIED -> Color(0xFF99979B)
}
private fun known(rows: List<AiCostGroup>): Long? {
    if (rows.isEmpty()) return 0L
    val amounts = rows.mapNotNull { it.knownPico }
    return if (amounts.isEmpty()) null else runCatching { amounts.fold(0L, Math::addExact) }.getOrNull()
}
private fun unknown(rows: List<AiCostGroup>) = rows.sumOf { it.unknownCalls }
private fun money(value: Long?): String {
    if (value == null) return "未知"
    if (value in 1..999_999) return "<¥0.000001"
    return "¥" + BigDecimal.valueOf(value, 12).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
