package com.jiligulu.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.ai.*
import com.jiligulu.app.data.prefs.AiCostGroup
import com.jiligulu.app.data.prefs.AiProviderState
import com.jiligulu.app.ui.components.CompactFormField
import com.jiligulu.app.ui.components.GuluDialog
import com.jiligulu.app.ui.components.LedgerCard
import com.jiligulu.app.ui.components.SpringScrollColumn
import com.jiligulu.app.ui.theme.GuluBrandFont
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

internal data class CostView(val daily: List<AiCostGroup> = emptyList(), val total: List<AiCostGroup> = emptyList(),
    val failed: Boolean = false, val undatedCalls: Long = 0)

/** Cache coverage is separate from price coverage: an unpriced call can still report a complete split. */
internal data class AiCostSummary(
    val knownPico: Long?, val calls: Long, val unknownCalls: Long, val legacyCalls: Long,
    val inputTokens: BigDecimal, val outputTokens: BigDecimal, val tokensComplete: Boolean,
    val cacheRate: BigDecimal?, val cacheComplete: Boolean, val hasCacheReport: Boolean,
    val displayPico: Long?
) {
    val cacheText: String get() = cacheRate?.setScale(1, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString()?.plus("%") ?: "—"
    val cacheNote: String get() = when {
        calls == 0L -> "暂无请求"
        cacheRate != null && cacheComplete -> "完整报告"
        cacheRate != null -> "仅已报告部分"
        cacheComplete -> "无输入 tokens"
        hasCacheReport -> "报告不完整"
        else -> "未报告缓存"
    }
}

internal fun summarizeAiCost(rows: List<AiCostGroup>): AiCostSummary {
    fun sum(selector: (AiCostGroup) -> Long) = rows.fold(BigDecimal.ZERO) { amount, row -> amount + BigDecimal.valueOf(selector(row)) }
    val hit = sum { it.cacheHit }
    val miss = sum { it.cacheMiss }
    val split = hit + miss
    // A missing side is not a zero. Aggregates with one-sided reports cannot
    // supply a ratio; still count their reported tokens in the usage totals.
    val cacheRows = rows.filter {
        (it.cacheReportedCalls > 0 && it.cacheHitReportedCalls == it.cacheReportedCalls &&
            it.cacheMissReportedCalls == it.cacheReportedCalls) ||
            (it.legacyCalls > 0 && it.cacheHit > 0 && it.cacheMiss > 0)
    }
    val ratioHit = cacheRows.fold(BigDecimal.ZERO) { amount, row -> amount + BigDecimal.valueOf(row.cacheHit) }
    val ratioInput = cacheRows.fold(BigDecimal.ZERO) { amount, row -> amount + BigDecimal.valueOf(row.cacheHit) + BigDecimal.valueOf(row.cacheMiss) }
    val calls = rows.sumOf { it.calls }
    return AiCostSummary(
        known(rows), calls, unknown(rows), rows.sumOf { it.legacyCalls },
        split + sum { it.unclassifiedInput }, sum { it.output },
        calls > 0 && rows.all { it.inputReportedCalls == it.calls && it.outputReportedCalls == it.calls },
        if (ratioInput.signum() > 0) ratioHit.multiply(BigDecimal.valueOf(100)).divide(ratioInput, 6, RoundingMode.HALF_UP) else null,
        calls > 0 && rows.all { it.cacheHitReportedCalls == it.calls && it.cacheMissReportedCalls == it.calls && it.unclassifiedInput == 0L },
        rows.any { it.cacheHitReportedCalls > 0 || it.cacheMissReportedCalls > 0 || it.cacheHit > 0 || it.cacheMiss > 0 },
        estimated(rows)
    )
}

internal data class AiCostCategory(val purpose: AiUsagePurpose, val label: String, val summary: AiCostSummary,
    val priceIncomplete: Boolean)

internal fun costPurposeLabel(rows: List<AiCostGroup>): String {
    val purpose = rows.firstOrNull()?.purpose ?: AiUsagePurpose.UNSPECIFIED
    if (purpose != AiUsagePurpose.UNSPECIFIED) return purpose.label
    val legacy = rows.sumOf { it.legacyCalls }
    return when {
        legacy > 0 && rows.sumOf { it.calls } > legacy -> "历史及其它"
        legacy > 0 -> "历史汇总"
        else -> "其它调用"
    }
}

/** Keep historical attribution intact and compute each rate from its own reported counters. */
internal fun costCategories(rows: List<AiCostGroup>): List<AiCostCategory> = AiUsagePurpose.entries.mapNotNull { purpose ->
    val group = rows.filter { it.purpose == purpose }
    if (group.isEmpty()) null else AiCostCategory(purpose, costPurposeLabel(group), summarizeAiCost(group), missingPrices(group) > 0)
}

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
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var scope by rememberSaveable { mutableIntStateOf(0) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    val month = YearMonth.parse(monthText)
    val start = month.atDay(1)
    val end = month.atEndOfMonth()
    val defaultDay = if (month == YearMonth.now()) LocalDate.now() else end
    val group = if (scope >= 1) runCatching { AiUsageTicket.providerGroup(profile) }.getOrDefault("unconfigured") else null
    val filterKey = if (scope == 2) key ?: "unconfigured" else null
    val view by produceState<CostView?>(null, revision, start, end, group, filterKey) {
        value = null
        value = try { CostView(repo.daily(start, end, group, filterKey), repo.total(group, filterKey),
            undatedCalls = repo.undatedCalls(group, filterKey)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CostView(failed = true) }
    }
    LaunchedEffect(active) { if (!active) { show = false; priceEditor = false; help = false } }
    Row(Modifier.fillMaxWidth().testTag("ai-usage-entry").clickable { show = true }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("AI 花销小账本", style = MaterialTheme.typography.bodyLarge)
            Text(view?.let { if (it.failed) "本地记录暂时不可读" else "累计 ${money(estimated(it.total))}" }
                ?: "正在读取本地记录…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("查看", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
    if (show && active) AiCostOverviewDialog(
        view = view, month = month, scope = scope,
        currentModelAvailable = key != null, selectedDay = selectedDay?.let(LocalDate::parse) ?: defaultDay,
        onMonth = { monthText = it.toString(); selectedDay = null },
        onScope = { scope = it; selectedDay = null },
        onSelectDay = { selectedDay = it.toString() }, onPrices = { priceEditor = true },
        onHelp = { help = true }, onDismiss = { show = false }
    ) { day -> DayCostDetails(day, revision, group, filterKey) }
    if (priceEditor && active) AiPriceEditor(profile, onDismiss = { priceEditor = false })
    if (help && active) GuluDialog("费用怎样记录", { help = false }, compact = true, dense = true) {
        Text("用途页和日明细只显示各类花费与缓存命中率；图片识别、记账聊天等按实际调用用途分别记录。旧版没有保存用途，所以旧费用单列为历史汇总；不会猜测分到图片或聊天。未标注用途的新请求归为其它调用，两者合并时显示历史及其它。")
        Text("每次真正发起的 HTTP 尝试各计一次，包括重试与多轮查询。回复 JSON 不合格也不会重复计量；响应没报告用量时保留未知。")
        Text("只按请求开始时保存的供应商、模型和单价快照计算已知部分。改价只影响之后的请求。缺少缓存拆分时，不擅自把全部输入当未命中；图上的问号表示该日存在未知费用。缓存率只用已报告的命中 /（命中 + 未命中），报告不全会单独标出。")
        Text("单价单位是人民币元 / 每百万 tokens。每个供应商与模型独立，保存只影响之后的请求。DeepSeek Flash 默认采用你提供的 0.02 / 1 / 4 元参考单价；其它模型与自定义服务需单独配置。美元报价请填写人民币参考值。服务商可能按时段、折扣或其它规则实扣，此处不是账单，也不额外查余额。")
        Text("柱图展示所选整月；累计由数据库聚合。旧版本的参考估算按旧版固定算法保留，不套当前单价。最后记录日可展示每日费用，更早未分日的记录保留在累计与历史说明中，不补造日期。仅保存用量、用途、模型与价格元数据，不保存消息、照片、接口地址或密钥。")
    }
}

private enum class CostPane { OVERVIEW, PURPOSES, DAY }

/** A dedicated fixed paper panel: only the chosen pane scrolls; its actions always stay visible. */
@Composable
internal fun AiCostOverviewDialog(
    view: CostView?, month: YearMonth, scope: Int,
    currentModelAvailable: Boolean, selectedDay: LocalDate,
    onMonth: (YearMonth) -> Unit, onScope: (Int) -> Unit,
    onSelectDay: (LocalDate) -> Unit, onPrices: () -> Unit, onHelp: () -> Unit, onDismiss: () -> Unit,
    dayDetails: @Composable ColumnScope.(LocalDate) -> Unit
) {
    var pane by rememberSaveable { mutableStateOf(CostPane.OVERVIEW) }
    val start = month.atDay(1)
    val days = month.lengthOfMonth()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        com.jiligulu.app.ui.capture.DialogGlassBackdrop()
        val height = minOf(480, (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(200)).dp
        LedgerCard(Modifier.widthIn(max = 360.dp).fillMaxWidth().height(height).testTag("ai-cost-panel"), contentPadding = 12.dp) {
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pane != CostPane.OVERVIEW) IconButton(onClick = { pane = CostPane.OVERVIEW }, modifier = Modifier.size(40.dp).testTag("ai-cost-back")) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "返回费用总览", tint = MaterialTheme.colorScheme.primary)
                } else Spacer(Modifier.width(40.dp))
                Text(when (pane) { CostPane.OVERVIEW -> "AI 花销"; CostPane.PURPOSES -> "用途汇总"; CostPane.DAY -> "${selectedDay.monthValue}月${selectedDay.dayOfMonth}日明细" },
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(fontFamily = GuluBrandFont), color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                Spacer(Modifier.width(40.dp))
            }
            val bodyScroll = rememberScrollState()
            LaunchedEffect(pane, scope, month, days) { bodyScroll.scrollTo(0) }
            SpringScrollColumn(Modifier.weight(1f).fillMaxWidth().testTag("ai-cost-body"), state = bodyScroll,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (pane) {
                    CostPane.OVERVIEW -> {
                        CostScopePicker(scope, currentModelAvailable, onScope)
                        CostPeriodPicker(month, onMonth)
                        if (view == null) CostStatus("正在读取本地记录…")
                        else if (view.failed) CostStatus("暂时无法读取本地费用\n未把读取失败当作零。", error = true)
                        else CostOverview(view, start, days, selectedDay, onSelectDay)
                    }
                    CostPane.PURPOSES -> view?.takeUnless { it.failed }?.let {
                        PurposeDetails(it.total, it.undatedCalls)
                    } ?: CostStatus(if (view == null) "正在读取…" else "暂时无法读取本地费用。", error = view?.failed == true)
                    CostPane.DAY -> dayDetails(selectedDay)
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
            Row(Modifier.fillMaxWidth().height(44.dp).testTag("ai-cost-actions"), verticalAlignment = Alignment.CenterVertically) {
                CostAction("用途", "ai-cost-purpose-action", enabled = view != null && !view.failed) { pane = CostPane.PURPOSES }
                CostAction("日明细", "ai-cost-day-action", enabled = view != null && !view.failed) { pane = CostPane.DAY }
                CostAction("单价", "ai-price-editor", enabled = currentModelAvailable, onClick = onPrices)
                CostAction("说明", "ai-cost-help", onClick = onHelp)
            }
        }
    }
}

@Composable
private fun RowScope.CostAction(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f).fillMaxHeight().testTag(tag), contentPadding = PaddingValues(horizontal = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun CostScopePicker(scope: Int, currentModelAvailable: Boolean, onScope: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .55f), RoundedCornerShape(8.dp)).padding(3.dp)) {
        listOf("全部", "该供应商", "当前模型").forEachIndexed { index, label ->
            val selected = scope == index
            Surface(modifier = Modifier.weight(1f).fillMaxHeight().testTag("ai-cost-scope-$index"),
                shape = RoundedCornerShape(6.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                onClick = { onScope(index) }, enabled = index != 2 || currentModelAvailable) {
                Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun CostPeriodPicker(month: YearMonth, onMonth: (YearMonth) -> Unit) {
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onMonth(month.minusMonths(1)) }, modifier = Modifier.size(36.dp).testTag("ai-cost-month-prev")) { Icon(Icons.Default.ChevronLeft, contentDescription = "上个月") }
        Text("${month.year}年${month.monthValue}月", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, textAlign = TextAlign.Center)
        IconButton(onClick = { onMonth(month.plusMonths(1)) }, enabled = month < YearMonth.now(), modifier = Modifier.size(36.dp).testTag("ai-cost-month-next")) { Icon(Icons.Default.ChevronRight, contentDescription = "下个月") }
    }
}

@Composable
private fun CostOverview(view: CostView, start: LocalDate, days: Int, selectedDay: LocalDate, onSelectDay: (LocalDate) -> Unit) {
    val total = remember(view.total) { summarizeAiCost(view.total) }
    val period = remember(view.daily) { summarizeAiCost(view.daily) }
    val selected = remember(view.daily, selectedDay) { summarizeAiCost(view.daily.filter { it.day == selectedDay.toString() }) }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("累计花费", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(money(total.displayPico), modifier = Modifier.testTag("ai-cost-total"), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary, maxLines = 1)
            Text(when { view.total.any { it.legacyEstimatePico != null } -> "含旧版参考估算"
                view.undatedCalls > 0 -> "历史 ${view.undatedCalls} 次未分日"
                total.unknownCalls > 0 -> "${total.unknownCalls} 次费用不完整"
                else -> "人民币 · 参考估算" }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Column(Modifier.weight(1f).testTag("ai-cost-selected-day"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${selectedDay.monthValue}/${selectedDay.dayOfMonth} 花费", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(money(selected.displayPico), Modifier.testTag("ai-cost-daily"), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary, maxLines = 1)
            Text("${selected.calls} 次${if (missingPrices(view.daily.filter { it.day == selectedDay.toString() }) > 0) " · 含未知" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("缓存命中率", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(total.cacheText, Modifier.testTag("ai-cost-cache-rate"), fontSize = 20.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        if (!total.cacheComplete) Text(total.cacheNote, Modifier.testTag("ai-cost-cache-coverage"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("本月 ${money(period.displayPico)}", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            val end = start.plusDays((days - 1).toLong())
            Text("${start.monthValue}/${start.dayOfMonth}—${end.monthValue}/${end.dayOfMonth}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        CostChart(start, days, view.daily, selectedDay.toString(), onSelectDay)
        CostPurposeLegend(view.daily)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CostPurposeLegend(rows: List<AiCostGroup>) {
    val categories = remember(rows) { costCategories(rows.filter { it.calls > 0 }) }
    FlowRow(Modifier.fillMaxWidth().testTag("ai-cost-purpose-legend"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        categories.forEach { category ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.size(6.dp).background(purposeColor(category.purpose), RoundedCornerShape(2.dp)))
                Text(when (category.purpose) {
                    AiUsagePurpose.LEDGER_CHAT -> "聊天"; AiUsagePurpose.IMAGE_RECOGNITION -> "识图"; AiUsagePurpose.CLASSIFICATION -> "分类"
                    AiUsagePurpose.HEART_LETTER -> "回信"; AiUsagePurpose.LIU_REN -> "小六壬"; AiUsagePurpose.UNSPECIFIED -> when (category.label) {
                        "历史汇总" -> "历史"; "历史及其它" -> "历史/其它"; else -> "其它"
                    }
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CostStatus(message: String, error: Boolean = false) {
    Box(Modifier.fillMaxWidth().height(210.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PurposeDetails(rows: List<AiCostGroup>, undatedCalls: Long) {
    val summary = remember(rows) { summarizeAiCost(rows) }
    Text("累计 ${money(summary.displayPico)} · ${summary.calls} 次", style = MaterialTheme.typography.titleSmall)
    if (rows.isEmpty()) Text("还没有记录的请求。", style = MaterialTheme.typography.bodyMedium)
    else CostCategoryTable(rows)
    if (undatedCalls > 0) Text("历史 $undatedCalls 次未分日，已计入累计", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("ai-cost-undated-history"))
}

private data class DayView(val rows: List<AiCostGroup> = emptyList(), val failed: Boolean = false)

@Composable
private fun DayCostDetails(day: LocalDate, revision: Long, group: String?, key: String?) {
    val repo = (LocalContext.current.applicationContext as JiliguluApp).container.aiUsage
    val view by produceState<DayView?>(null, day, revision, group, key) {
        value = null
        value = try { DayView(repo.details(day, group, key)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DayView(failed = true) }
    }
    val ready = view
    if (ready == null) Text("正在读取明细…", style = MaterialTheme.typography.bodyMedium)
    else if (ready.failed) Text("明细暂时不可读，未把未知计为零。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    else CostDayDetails(ready.rows)
}

@Composable
internal fun CostDayDetails(rows: List<AiCostGroup>) {
    val summary = remember(rows) { summarizeAiCost(rows) }
    Text("当日 ${money(summary.displayPico)} · ${summary.calls} 次", style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.testTag("ai-cost-day-details"))
    if (rows.isEmpty()) Text("这一天没有记录的请求。", style = MaterialTheme.typography.bodyMedium)
    else CostCategoryTable(rows)
}

@Composable
private fun CostCategoryTable(rows: List<AiCostGroup>) {
    val categories = remember(rows) { costCategories(rows) }
    Column(Modifier.fillMaxWidth().testTag("ai-cost-category-table")) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text("用途", Modifier.weight(1.05f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("花费", Modifier.weight(1.15f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("缓存命中率", Modifier.weight(.95f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        categories.forEach { category ->
            val summary = category.summary
            Row(Modifier.fillMaxWidth().heightIn(min = 42.dp).padding(vertical = 3.dp).testTag("ai-cost-category-${category.purpose.name}"),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1.05f).alignBy(FirstBaseline), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).background(purposeColor(category.purpose), RoundedCornerShape(2.dp)))
                    Text(category.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                }
                Column(Modifier.weight(1.15f).alignBy(FirstBaseline), horizontalAlignment = Alignment.End) {
                    Text(money(summary.displayPico), Modifier.testTag("ai-cost-category-money-${category.purpose.name}"),
                        style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    if (category.priceIncomplete && summary.displayPico != null) Text("含未知", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(.95f).alignBy(FirstBaseline), horizontalAlignment = Alignment.End) {
                    Text(summary.cacheText, Modifier.testTag("ai-cost-category-rate-${category.purpose.name}"), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    if (!summary.cacheComplete || summary.cacheRate == null) Text(when {
                        summary.cacheRate != null -> "部分"
                        summary.cacheComplete -> "无输入"
                        summary.hasCacheReport -> "不完整"
                        else -> "未报告"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CostChart(start: LocalDate, days: Int, rows: List<AiCostGroup>, selected: String?, onSelect: (LocalDate) -> Unit) {
    val dates = remember(start, days) { (0 until days).map { start.plusDays(it.toLong()) } }
    val grouped = remember(rows) { rows.groupBy { it.day } }
    val maximum = dates.maxOfOrNull { estimated(grouped[it.toString()].orEmpty()) ?: 0L }?.coerceAtLeast(1) ?: 1L
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val guide = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.fillMaxWidth().height(86.dp)) {
        Canvas(Modifier.fillMaxSize().testTag("ai-cost-chart")
            .semantics { contentDescription = "按用途堆叠的整月花费估算图，问号表示未知部分；点选日期查看每日花费" }
            .pointerInput(dates) { detectTapGestures { point ->
                onSelect(dates[(point.x / size.width * days).toInt().coerceIn(0, days - 1)])
            } }) {
            val slot = size.width / days
            val width = minOf(slot * .45f, 12.dp.toPx())
            val baseline = size.height - 5.dp.toPx()
            val available = baseline - 10.dp.toPx()
            listOf(.0f, .5f, 1f).forEach { fraction -> drawLine(guide.copy(alpha = .4f), Offset(0f, baseline - available * fraction), Offset(size.width, baseline - available * fraction), 1.dp.toPx()) }
            dates.forEachIndexed { index, date ->
                val data = grouped[date.toString()].orEmpty()
                var bottom = baseline
                if (selected == date.toString()) drawRect(tint.copy(alpha = .08f), Offset(index * slot, 0f), Size(slot, size.height))
                data.forEach { row ->
                    val value = estimated(listOf(row)) ?: 0
                    if (value > 0) {
                        val height = (value.toDouble() / maximum * available).toFloat()
                        drawRect(purposeColor(row.purpose), Offset(index * slot + (slot - width) / 2, bottom - height), Size(width, height))
                        bottom -= height
                    }
                }
                if (missingPrices(data) > 0) {
                    val markerHeight = if (data.none { (estimated(listOf(it)) ?: 0) > 0 }) 16.dp.toPx() else 7.dp.toPx()
                    drawRect(tint, Offset(index * slot + (slot - width) / 2, bottom - markerHeight),
                        Size(width, markerHeight), style = Stroke(1.dp.toPx()))
                }
            }
        }
        if (rows.isEmpty()) Text("这段时间还没有请求", Modifier.align(Alignment.Center).testTag("ai-cost-empty-chart"), style = MaterialTheme.typography.bodySmall, color = tint)
        else if (rows.all { (estimated(listOf(it)) ?: 0) == 0L } && missingPrices(rows) > 0) Text("已有请求，费用仍有未知部分", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodySmall, color = tint)
        else if (rows.all { it.knownPico == 0L }) Text("这段时间的已知费用为 ¥0", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodySmall, color = tint)
    }
    Row(Modifier.fillMaxWidth()) { dates.forEachIndexed { index, date ->
        val pending = missingPrices(grouped[date.toString()].orEmpty())
        val dateLabel = if (index % 5 == 0 || index == days - 1 || selected == date.toString()) date.dayOfMonth.toString() else ""
        val label = if (pending > 0 && dateLabel.isEmpty()) "?" else dateLabel
        // The canvas owns selection; enlarged per-day targets would overlap at 30 days.
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
            textAlign = TextAlign.Center, maxLines = 1, color = if (selected == date.toString()) MaterialTheme.colorScheme.primary else tint)
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
        Text("人民币元 / 每百万 tokens", style = MaterialTheme.typography.bodySmall)
        CompactFormField("缓存命中", cache, { cache = it.take(18) }, prefix = "¥", singleLine = true)
        CompactFormField("未命中输入", miss, { miss = it.take(18) }, prefix = "¥", singleLine = true)
        CompactFormField("输出", output, { output = it.take(18) }, prefix = "¥", singleLine = true)
        Text("保存只影响之后的请求", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun missingPrices(rows: List<AiCostGroup>) = rows.sumOf {
    (it.unknownCalls - if (it.legacyEstimatePico != null) it.legacyCalls else 0).coerceAtLeast(0)
}
private fun estimated(rows: List<AiCostGroup>): Long? {
    if (rows.isEmpty()) return 0L
    val amounts = rows.flatMap { listOfNotNull(it.knownPico, it.legacyEstimatePico) }
    return if (amounts.isEmpty()) null else runCatching { amounts.fold(0L, Math::addExact) }.getOrNull()
}
internal fun money(value: Long?): String {
    if (value == null) return "未知"
    if (value in 1..999_999) return "<¥0.000001"
    return "¥" + BigDecimal.valueOf(value, 12).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
