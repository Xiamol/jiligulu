package com.jiligulu.app.ui.add

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import com.jiligulu.app.core.audio.UiSound
import com.jiligulu.app.ui.components.uiTap
import com.jiligulu.app.ui.memories.MemoryPhoto
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import com.jiligulu.app.ui.components.GuluDialog
import com.jiligulu.app.ui.components.CompactCalendarDialog
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.awaitCancellation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.littleworld.Sticker
import com.jiligulu.app.ui.calculator.CalculatorDialog
import com.jiligulu.app.data.repository.CategoryDeletionResult
import com.jiligulu.app.domain.category.CategoryDefaults
import com.jiligulu.app.domain.category.CategoryEngine
import com.jiligulu.app.domain.category.CategoryLabels
import com.jiligulu.app.ui.components.SpringScrollColumn
import com.jiligulu.app.ui.components.BillDateTimeField
import com.jiligulu.app.ui.components.LedgerCard
import com.jiligulu.app.ui.components.CategoryBadge
import com.jiligulu.app.domain.color.GoldenAnglePalette
import com.jiligulu.app.ui.components.PaperNote
import com.jiligulu.app.ui.theme.GuluBrandFont
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBillScreen(onBack: () -> Unit, vm: AddBillViewModel = viewModel(factory = AddBillViewModel.Factory),
    initialSticker: Sticker? = null, onSaved: () -> Unit = onBack) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val saveState by vm.saveState.collectAsStateWithLifecycle()
    val categoryPreview by vm.categoryPreview.collectAsStateWithLifecycle()
    val photoState by vm.photo.collectAsStateWithLifecycle()
    val reclassification by vm.reclassification.collectAsStateWithLifecycle()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importPhoto(uri)
    }
    BackHandler(enabled = saveState.isSaving) {}
    val context = LocalContext.current
    val entryPrefs = remember(context) { context.getSharedPreferences("manual_bill_entry", android.content.Context.MODE_PRIVATE) }
    var automaticCategory by rememberSaveable(initialSticker?.id) { mutableStateOf(initialSticker == null && entryPrefs.getBoolean("automatic_category", true)) }
    val setAutomaticCategory: (Boolean) -> Unit = { enabled ->
        automaticCategory = enabled
        entryPrefs.edit().putBoolean("automatic_category", enabled).apply()
    }
    val currentOnSaved by rememberUpdatedState(onSaved)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.saved.collect { UiSound.confirm(context); currentOnSaved() }
        }
    }
    var amountText by rememberSaveable(initialSticker?.id) { mutableStateOf(initialSticker?.amountFen
        ?.takeIf { it > 0 }?.let(Formatters::fenToYuanText).orEmpty()) }
    var type by rememberSaveable(initialSticker?.id) { mutableStateOf(if (initialSticker?.type == "INCOME") BillType.INCOME else BillType.EXPENSE) }
    var selectedCategoryId by rememberSaveable(initialSticker?.id) { mutableStateOf(initialSticker?.categoryId ?: -1L) }
    var userPickedCategory by rememberSaveable(initialSticker?.id) { mutableStateOf(false) }
    var detail by rememberSaveable(initialSticker?.id) { mutableStateOf(initialSticker?.title.orEmpty()) }
    var note by rememberSaveable { mutableStateOf("") }
    var timestamp by rememberSaveable { mutableStateOf<Long?>(null) }
    var calculatorOpen by rememberSaveable { mutableStateOf(false) }
    // A deleted template category falls back safely; opening a sticker never writes a bill.
    LaunchedEffect(initialSticker?.id, categories) {
        if (initialSticker != null && !userPickedCategory && categories.isNotEmpty()) {
            selectedCategoryId = categories.firstOrNull { it.id == initialSticker.categoryId }?.id
                ?: CategoryEngine.suggest(initialSticker.title, categories)?.id
                ?: categories.firstOrNull { !it.deletable }?.id ?: -1L
            userPickedCategory = true
        }
    }
    val previewCurrent = categoryPreview.inputKey == manualCategoryInputKey(detail, note, type)
    val effectiveCategoryId = if (automaticCategory) categoryPreview.categoryId ?: -1L else selectedCategoryId
    val proposedCategory = categoryPreview.proposal.takeIf { automaticCategory && previewCurrent }
    val categoryReady = if (automaticCategory) previewCurrent && categoryPreview.name.isNotBlank() && !categoryPreview.resolving &&
        (proposedCategory != null || categories.any { it.id == effectiveCategoryId }) else categories.any { it.id == effectiveCategoryId }
    val amountFen = Formatters.yuanTextToFen(amountText)
    val amountInvalid = amountText.isNotBlank() && amountFen == null
    val editable = !saveState.isSaving
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(detail, note, type, automaticCategory, amountFen != null, categories, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                vm.prepareCategory(detail, note, type, automaticCategory && amountFen != null && categories.isNotEmpty())
                awaitCancellation()
            } finally { vm.cancelCategoryPreview() }
        }
    }
    // 长按分类要删它——先把「删谁、会挪走几笔」查清楚再问，不让用户自己数。
    var pendingDelete by remember { mutableStateOf<PendingCategoryDelete?>(null) }
    // 轻提示（"收纳箱删不得"、删除结果）：不用 Material 的 Snackbar 灰条，
    // 换成阿噜的胶囊小气泡，配色和圆角跟奶油风一致。
    var tip by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tip) {
        if (tip != null) {
            delay(2400)
            tip = null
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            AnimatedVisibility(
                visible = tip != null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut()
            ) {
                GuluTip(tip.orEmpty(), Modifier.padding(bottom = 12.dp))
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("记一笔", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = uiTap(com.jiligulu.app.core.audio.UiCue.NAVIGATE, onBack), enabled = editable) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    saveState.error?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = uiTap { amountFen?.let { vm.save(it, type, effectiveCategoryId, detail, note, timestamp, proposedCategory, autoCategorized = automaticCategory) } },
                        enabled = amountFen != null && categoryReady && editable && !photoState.importing,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)
                    ) { Text(if (saveState.isSaving) "正在保存…" else "确认记账", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val categoryHeight = when { maxHeight < 410.dp -> 48.dp; maxHeight < 470.dp -> 96.dp; else -> 144.dp }
            val decorationHeight = (maxHeight - categoryHeight - 330.dp).coerceIn(0.dp, 225.dp)
            SpringScrollColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("¥", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    BasicTextField(amountText,
                        onValueChange = { if (it.matches(Regex("\\d{0,12}(\\.\\d{0,2})?"))) amountText = it },
                        modifier = Modifier.weight(1f).testTag("manual-amount"), singleLine = true, enabled = editable,
                        textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        decorationBox = { field ->
                            Box {
                                if (amountText.isBlank()) Text("0.00", style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f))
                                field()
                            }
                        })
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .08f)) {
                        Row(Modifier.clickable(enabled = editable) { UiSound.toggle(context); type = if (type == BillType.EXPENSE) BillType.INCOME else BillType.EXPENSE }
                            .height(40.dp).padding(horizontal = 10.dp).testTag("manual-type"), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (type == BillType.EXPENSE) "支出" else "收入", style = MaterialTheme.typography.labelLarge,
                                color = if (type == BillType.EXPENSE) com.jiligulu.app.ui.theme.ExpenseCoral else com.jiligulu.app.ui.theme.IncomeGreen)
                            Icon(Icons.Outlined.SwapVert, null, Modifier.size(14.dp).padding(start = 2.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = uiTap { calculatorOpen = true }, enabled = editable, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Calculate, "打开阿噜小算盘", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                }
                if (amountInvalid) Text("请输入大于 0 的金额", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                ManualEntryField("细则", detail, { detail = it }, "例如：午餐、奶茶", editable, "manual-detail")
                ManualEntryField("备注", note, { note = it }, "可选，留一句话", editable, "manual-note")
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("分类", Modifier.width(42.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Checkbox(automaticCategory, onCheckedChange = { UiSound.toggle(context); setAutomaticCategory(it) }, enabled = editable, modifier = Modifier.size(28.dp))
                        Text("自动分类", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 2.dp))
                        Spacer(Modifier.weight(1f))
                        if (automaticCategory) {
                            if (categoryPreview.resolving) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            Text(when {
                                categoryPreview.resolving -> "分类中…"
                                categoryPreview.name.isNotBlank() -> CategoryLabels.displayName(categoryPreview.name) + if (proposedCategory != null) " · 新" else ""
                                categoryPreview.error != null -> "可手动选分类"
                                else -> "等待输入"
                            }, modifier = Modifier.padding(start = 5.dp).widthIn(max = 130.dp).testTag("manual-auto-status"),
                                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    SpringScrollColumn(Modifier.fillMaxWidth().height(categoryHeight).testTag("manual-category-grid"),
                        verticalArrangement = Arrangement.spacedBy(5.dp), handOffOnRepeat = true) {
                        categories.chunked(3).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { category ->
                                    CategoryChip(category, effectiveCategoryId == category.id, editable, Modifier.weight(1f),
                                        onClick = { UiSound.select(context); selectedCategoryId = category.id; userPickedCategory = true; setAutomaticCategory(false) },
                                        onLongClick = {
                                            val displayName = CategoryLabels.displayName(category.name)
                                            if (!category.deletable) vm.preparePendingReclassification()
                                            else scope.launch { pendingDelete = PendingCategoryDelete(category.id, displayName, vm.liveBillCount(category.id)) }
                                        })
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                val localTime = timestamp?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("日期", Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.weight(1f).clickable(enabled = editable) { UiSound.navigate(context); showDate = true }.padding(vertical = 10.dp)
                        .testTag("manual-date"), verticalAlignment = Alignment.CenterVertically) {
                        Text(localTime?.format(DateTimeFormatter.ofPattern("M月d日")) ?: "今天", style = MaterialTheme.typography.bodyMedium)
                        Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Row(Modifier.clickable(enabled = editable) { UiSound.navigate(context); showTime = true }.padding(vertical = 10.dp, horizontal = 6.dp)
                        .testTag("manual-time"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(localTime?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "此刻", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (timestamp != null) IconButton(onClick = uiTap { timestamp = null }, enabled = editable, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Close, "恢复此刻", modifier = Modifier.size(15.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("照片", Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.weight(1f).clickable(enabled = editable && !photoState.importing) {
                        UiSound.tap(context)
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }.testTag("manual-photo-picker"), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        when {
                            photoState.importing -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                            photoState.path.isNotBlank() -> MemoryPhoto(photoState.path, Modifier.size(32.dp).clip(RoundedCornerShape(7.dp)), maxSide = 96)
                            else -> Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(when {
                            photoState.importing -> "正在夹好…"
                            photoState.error != null -> photoState.error.orEmpty()
                            photoState.path.isNotBlank() -> "夹好啦 · 点此换一张"
                            else -> "夹一张生活照片"
                        }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (photoState.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (photoState.path.isNotBlank() || photoState.importing) IconButton(onClick = uiTap(com.jiligulu.app.core.audio.UiCue.REMOVE, vm::removePhoto),
                        enabled = editable, modifier = Modifier.size(32.dp).testTag("manual-photo-remove")) {
                        Icon(Icons.Outlined.Close, "取下照片", Modifier.size(15.dp))
                    }
                }
                // Use only the space left by the compact form; keyboard/short screens stay lean.
                if (decorationHeight >= 80.dp) ManualEntryDecoration(Modifier.height(decorationHeight),
                    enabled = editable && !photoState.importing) {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
        }
    }
    if (showDate) CompactCalendarDialog(timestamp ?: System.currentTimeMillis(), onDismiss = { showDate = false },
        onSelect = { day ->
            val date = Instant.ofEpochMilli(day).atZone(ZoneId.systemDefault()).toLocalDate()
            timestamp = com.jiligulu.app.ui.components.withBillDate(timestamp ?: System.currentTimeMillis(), date, ZoneId.systemDefault())
            showDate = false
        }, latestMonth = YearMonth.of(2100, 12))
    if (showTime) ManualTimeDialog(timestamp, { showTime = false }) { time -> timestamp = time; showTime = false }

    if (calculatorOpen) CalculatorDialog(amountText, onDismiss = { calculatorOpen = false },
        onUse = { amountText = it; calculatorOpen = false })
    if (reclassification.open) PendingReclassificationDialog(reclassification,
        onDismiss = vm::closeReclassification, onConfirm = vm::confirmPendingReclassification,
        onRetry = vm::preparePendingReclassification)

    // ---------- 删除分类的确认框 ----------
    pendingDelete?.let { pending ->
        CategoryDeleteDialog(
            pending = pending,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    when (val result = vm.deleteCategory(pending.id)) {
                        is CategoryDeletionResult.Deleted -> {
                            // 删掉的正好是当前选中项 → 回落到收纳箱，
                            // 否则用户会带着一个「不存在的分类 id」去提交，只会得到一句报错。
                            if (selectedCategoryId == pending.id) {
                                selectedCategoryId = categories.firstOrNull { !it.deletable }?.id ?: -1L
                                userPickedCategory = false
                            }
                            val moved = if (result.reassigned > 0)
                                "，${result.reassigned} 笔账挪到「${CategoryDefaults.VACUUM_NAME}」了"
                            else ""
                            tip = "「${pending.name}」删掉啦$moved"
                        }

                        is CategoryDeletionResult.Refused ->
                            tip = result.reason
                    }
                }
            }
        )
    }
}

/** Flat, compact input rows keep a whole manual bill on one ordinary portrait screen. */
@Composable
private fun ManualEntryField(
    label: String, value: String, onChange: (String) -> Unit, hint: String, enabled: Boolean, tag: String
) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(value, onChange, modifier = Modifier.weight(1f).testTag(tag), singleLine = true,
                    enabled = enabled, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), decorationBox = { field ->
                        Box {
                            if (value.isBlank()) Text(hint, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f))
                            field()
                        }
                    })
                if (value.isNotBlank()) IconButton(onClick = uiTap { onChange("") }, enabled = enabled, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Outlined.Close, "清空$label", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))
        }
    }
}

@Composable
private fun ManualTimeDialog(timestamp: Long?, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    val local = Instant.ofEpochMilli(timestamp ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault())
    var hour by rememberSaveable { mutableStateOf("%02d".format(local.hour)) }
    var minute by rememberSaveable { mutableStateOf("%02d".format(local.minute)) }
    val h = hour.toIntOrNull()?.takeIf { it in 0..23 }
    val m = minute.toIntOrNull()?.takeIf { it in 0..59 }
    GuluDialog("几点记下？", onDismiss, confirmLabel = "确定", dismissLabel = "取消", compact = true,
        compactWidth = 260.dp, confirmEnabled = h != null && m != null, onConfirm = {
            if (h != null && m != null) onSave(com.jiligulu.app.ui.components.withBillTime(
                timestamp ?: System.currentTimeMillis(), h, m, ZoneId.systemDefault()))
        }) {
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            listOf(true, false).forEachIndexed { index, isHour ->
                if (index == 1) Text(" : ", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Column(Modifier.width(62.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicTextField(if (isHour) hour else minute, onValueChange = { value ->
                        if (value.length <= 2 && value.all(Char::isDigit)) { if (isHour) hour = value else minute = value }
                    }, singleLine = true, textStyle = MaterialTheme.typography.titleLarge.copy(
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .5f))
                }
            }
        }
        if (h == null || m == null) Text("小时 0–23，分钟 0–59", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error)
    }
}

/**
 * 删除分类的确认框。
 *
 * 文案要求（PRD R2）：有账单时**必须**说明「N 笔会移到「待定」，不会丢」；
 * 并且**必须**含「以后同类消费阿噜可能会再帮你建一个新分类」——本次不做防重建黑名单，
 * 如实告知就是这条设计选择的代价（coder 明确放弃了「N 天不重建」方案）。
 */
@Composable
private fun CategoryDeleteDialog(
    pending: PendingCategoryDelete,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    // 自绘而不是 Material 的 AlertDialog：默认弹窗方正、字体生硬，
    // 跟奶油手账的圆润贴纸感完全两回事（用户报过「感觉都不可爱」）。
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            shadowElevation = 10.dp
        ) {
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🗂️", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (pending.liveCount > 0) "删除「${pending.name}」这个分类？"
                        else "删掉「${pending.name}」？",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GuluBrandFont,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    if (pending.liveCount > 0)
                        "这个分类下的 ${pending.liveCount} 笔账单会挪到「${CategoryDefaults.VACUUM_NAME}」，不会丢哦～" +
                            "\n以后同类消费，阿噜可能还会帮你建一个新分类。"
                    else
                        "它下面还没有账单，删掉不影响任何记录。" +
                            "\n以后同类消费，阿噜可能还会帮你建一个新分类。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TipButton("再想想", MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f), onDismiss)
                    TipButton("删除", MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer, Modifier.weight(1f), onConfirm)
                }
            }
        }
    }
}

/** 弹窗里的胶囊按钮：左右等分，配色跟随传入的容器色。 */
@Composable
private fun TipButton(
    text: String,
    container: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(50)).clickable(onClick = uiTap(onClick)),
        shape = RoundedCornerShape(50),
        color = container
    ) {
        Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, color = contentColor)
        }
    }
}

/**
 * 自绘分类 chip。
 *
 * 刻意不用 Material3 的 `FilterChip`：它自带 onClick，外层再套 `combinedClickable`
 * 会**抢手势**（长按不触发 / 单击被吞）。这里把「单击选择 / 长按删除」两个手势落在同一层。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryChip(
    category: com.jiligulu.app.data.local.entity.CategoryEntity,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surface
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .background(container)
            .border(
                width = 1.dp,
                // 选中的那格描边带一点品牌紫，比纯色块更有"被挑中"的感觉
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.extraLarge
            )
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .height(44.dp).padding(horizontal = 7.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            CategoryBadge(category.name, category.iconValue, size = 23.dp,
                tint = GoldenAnglePalette.colorForHue(category.colorHue))
            Text(CategoryLabels.displayName(category.name), style = MaterialTheme.typography.labelMedium,
                color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/**
 * 阿噜的胶囊小提示。
 *
 * 刻意不用 Material 默认的 Snackbar：那条灰黑长条跟「奶油手账」的圆润配色完全两个世界，
 * 在记账页里显得生硬（用户报过「提示词显示那里有点丑」）。
 */
@Composable
private fun GuluTip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🐾", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/** 等待确认删除的分类（含会被挪走的活账单条数）。 */
private data class PendingCategoryDelete(val id: Long, val name: String, val liveCount: Int)
