package com.jiligulu.app.ui.add

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        // 轻提示（"收纳箱删不得"、删除结果）走 Snackbar，不打断填写。
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("分类", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { category ->
                        // 自绘 chip 而不是 FilterChip：FilterChip 自带 onClick，外层再套
                        // combinedClickable 会抢手势（长按不触发 / 单击被吞），两个手势必须落在同一层。
                        CategoryChip(
                            label = "${category.iconValue} ${CategoryLabels.displayName(category.name)}",
                            selected = effectiveCategoryId == category.id,
                            enabled = editable,
                            onClick = { selectedCategoryId = category.id; userPickedCategory = true },
                            onLongClick = {
                                val displayName = CategoryLabels.displayName(category.name)
                                if (!category.deletable) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("「$displayName」是收纳箱，删不得哦～")
                                    }
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
                            snackbarHostState.showSnackbar("「${pending.name}」删掉啦$moved")
                        }

                        is CategoryDeletionResult.Refused ->
                            snackbarHostState.showSnackbar(result.reason)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (pending.liveCount > 0) "删除「${pending.name}」这个分类？"
                else "「${pending.name}」下面还没有账单，删掉它吗？"
            )
        },
        text = {
            Text(
                if (pending.liveCount > 0)
                    "这个分类下的 ${pending.liveCount} 笔账单会移到「${CategoryDefaults.VACUUM_NAME}」，不会丢。" +
                        "以后同类消费阿噜可能会再帮你建一个新分类。"
                else
                    "删掉它不会影响任何已有记录。以后同类消费阿噜可能会再帮你建一个新分类。"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("再想想") }
        }
    )
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
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surface
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.large
            )
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/** 等待确认删除的分类（含会被挪走的活账单条数）。 */
private data class PendingCategoryDelete(val id: Long, val name: String, val liveCount: Int)
