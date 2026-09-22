package com.jiligulu.app.ui.add

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.jiligulu.app.data.repository.CategoryDeletionResult
import com.jiligulu.app.domain.category.CategoryDefaults
import com.jiligulu.app.domain.category.CategoryEngine
import com.jiligulu.app.domain.category.CategoryLabels
import com.jiligulu.app.ui.components.BillDateTimeField
import com.jiligulu.app.ui.components.LedgerCard
import com.jiligulu.app.ui.components.PaperNote
import com.jiligulu.app.ui.theme.GuluBrandFont
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBillScreen(onBack: () -> Unit, vm: AddBillViewModel = viewModel(factory = AddBillViewModel.Factory)) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val saveState by vm.saveState.collectAsStateWithLifecycle()
    val currentOnBack by rememberUpdatedState(onBack)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.saved.collect { currentOnBack() }
        }
    }
    var amountText by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(BillType.EXPENSE) }
    var selectedCategoryId by rememberSaveable { mutableStateOf(-1L) }
    var userPickedCategory by rememberSaveable { mutableStateOf(false) }
    var detail by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var timestamp by rememberSaveable { mutableStateOf<Long?>(null) }
    val suggested = CategoryEngine.suggest(detail, categories)
    val effectiveCategoryId = if (userPickedCategory) selectedCategoryId else suggested?.id ?: selectedCategoryId
    val amountFen = Formatters.yuanTextToFen(amountText)
    val amountInvalid = amountText.isNotBlank() && amountFen == null
    val editable = !saveState.isSaving
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
                    IconButton(onClick = onBack, enabled = editable) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = { PaperNote("记下生活的小事 ♡", Modifier.padding(end = 20.dp)) }
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    saveState.error?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = { amountFen?.let { vm.save(it, type, effectiveCategoryId, detail, note, timestamp) } },
                        enabled = amountFen != null && effectiveCategoryId > 0 && editable,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) { Text(if (saveState.isSaving) "正在保存…" else "保存这一笔", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth().selectableGroup().clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BillType.entries.forEach { value ->
                    val selected = type == value
                    Box(Modifier.weight(1f).clip(MaterialTheme.shapes.extraLarge)
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .selectable(selected, enabled = editable, role = Role.Tab) { type = value }
                        .heightIn(min = 44.dp).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(if (value == BillType.EXPENSE) "支出" else "收入",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("金额", style = MaterialTheme.typography.titleSmall)
                LedgerCard {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { if (it.matches(Regex("\\d{0,12}(\\.\\d{0,2})?"))) amountText = it },
                        modifier = Modifier.fillMaxWidth(),
                        prefix = { Text("¥", style = MaterialTheme.typography.headlineMedium) },
                        placeholder = { Text("0.00", style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)) },
                        textStyle = MaterialTheme.typography.displaySmall,
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent),
                        singleLine = true, enabled = editable, isError = amountInvalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)
                    )
                    if (amountInvalid) Text("请输入大于 0 的金额", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("分类", style = MaterialTheme.typography.titleSmall)
                // 三个一行、等宽铺开：不论分类几个，都不会出现「最后一行挤在左边」的参差感。
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    categories.chunked(3).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            row.forEach { category ->
                                // 自绘 chip 而不是 FilterChip：FilterChip 自带 onClick，外层再套
                                // combinedClickable 会抢手势（长按不触发 / 单击被吞），两个手势必须落在同一层。
                                CategoryChip(
                                    label = "${category.iconValue} ${CategoryLabels.displayName(category.name)}".trim(),
                                    selected = effectiveCategoryId == category.id,
                                    enabled = editable,
                                    modifier = Modifier.weight(1f),
                                    onClick = { selectedCategoryId = category.id; userPickedCategory = true },
                                    onLongClick = {
                                        val displayName = CategoryLabels.displayName(category.name)
                                        if (!category.deletable) {
                                            tip = "「$displayName」是收纳箱，删不得哦～"
                                        } else {
                                            scope.launch {
                                                pendingDelete = PendingCategoryDelete(
                                                    id = category.id,
                                                    name = displayName,
                                                    liveCount = vm.liveBillCount(category.id)
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                            // 补足空位：最后一行不足 3 个时，前面的格子宽度保持不变
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                if (suggested != null && !userPickedCategory) Text(
                    "已推荐「${CategoryLabels.displayName(suggested.name)}」",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(value = detail, onValueChange = { detail = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("细则") }, placeholder = { Text("例如：牛肉面、矿泉水") },
                shape = MaterialTheme.shapes.large, singleLine = true, enabled = editable,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            OutlinedTextField(value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("备注（可选）") }, placeholder = { Text("想补充点什么？") },
                shape = MaterialTheme.shapes.large, minLines = 2, maxLines = 3, enabled = editable)
            LedgerCard {
                BillDateTimeField(timestamp, { timestamp = it }, enabled = editable)
            }
            Spacer(Modifier.height(4.dp))
        }
    }

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
        modifier = modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
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
    label: String,
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
            .padding(horizontal = 10.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
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
