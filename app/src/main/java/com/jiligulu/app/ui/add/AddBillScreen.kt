package com.jiligulu.app.ui.add

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.jiligulu.app.domain.category.CategoryEngine
import com.jiligulu.app.domain.category.CategoryLabels
import com.jiligulu.app.ui.components.BillDateTimeField
import com.jiligulu.app.ui.components.LedgerCard
import com.jiligulu.app.ui.components.PaperNote

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

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
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
                        FilterChip(selected = effectiveCategoryId == category.id,
                            onClick = { selectedCategoryId = category.id; userPickedCategory = true },
                            label = { Text("${category.iconValue} ${CategoryLabels.displayName(category.name)}") },
                            shape = MaterialTheme.shapes.large, enabled = editable)
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
}
