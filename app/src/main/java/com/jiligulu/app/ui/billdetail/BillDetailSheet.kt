package com.jiligulu.app.ui.billdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.util.Formatters
import com.jiligulu.app.data.local.entity.BillSource
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.ui.components.BillDateTimeField
import com.jiligulu.app.ui.components.LedgerCard

/** The ledger and statistics both open this entry point so their details and editing stay identical. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillDetailSheet(billId: Long, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as JiliguluApp
    val owner = remember(billId) {
        object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm: BillDetailViewModel = viewModel(viewModelStoreOwner = owner, factory = viewModelFactory {
        initializer { BillDetailViewModel(billId, app.container.billRepository, app.container.categoryRepository) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by rememberUpdatedState(state.isSaving)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !busy })
    var initialized by rememberSaveable(billId) { mutableStateOf(false) }
    var amount by rememberSaveable(billId) { mutableStateOf("") }
    var detail by rememberSaveable(billId) { mutableStateOf("") }
    var timestamp by rememberSaveable(billId) { mutableStateOf<Long?>(null) }
    var confirmDelete by rememberSaveable(billId) { mutableStateOf(false) }
    val bill = state.bill

    LaunchedEffect(bill) {
        if (!initialized && bill != null) {
            amount = Formatters.fenToYuanText(bill.amountFen)
            detail = bill.detail.ifBlank { state.categoryName }
            timestamp = bill.timestamp
            initialized = true
        }
    }
    LaunchedEffect(state.isComplete) { if (state.isComplete) onDismiss() }

    ModalBottomSheet(onDismissRequest = { if (!state.isSaving) onDismiss() }, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("这一笔小账", style = MaterialTheme.typography.headlineSmall)
                    Text("把生活的小细节，好好收起来。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onDismiss, enabled = !state.isSaving) { Text("关闭") }
            }
            if (state.isLoading) {
                CircularProgressIndicator()
            } else if (bill == null && !state.isComplete && !state.isSaving) {
                Text(state.error ?: "这条账单已不存在。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (bill != null) {
                LedgerCard {
                    Text("${state.icon}  ${state.categoryName} · ${if (bill.type == BillType.EXPENSE) "支出" else "收入"}",
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(when (bill.source) {
                        BillSource.MANUAL -> "手动记账"
                        BillSource.AI_CHAT -> "对话记账"
                        BillSource.SCREEN -> "识屏记账"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(value = amount, onValueChange = { amount = it },
                    label = { Text("金额") }, prefix = { Text("¥ ") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(), enabled = !state.isSaving)
                OutlinedTextField(value = detail, onValueChange = { detail = it },
                    label = { Text("账单名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), enabled = !state.isSaving)
                BillDateTimeField(timestamp = timestamp, onTimestampChange = { timestamp = it },
                    allowCurrentTime = false, enabled = !state.isSaving)
                if (bill.note.isNotBlank() || bill.rawText.isNotBlank() || bill.photoUri != null) {
                    LedgerCard {
                        if (bill.note.isNotBlank()) {
                            Text("备注", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(bill.note, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (bill.rawText.isNotBlank()) {
                            if (bill.note.isNotBlank()) Spacer(Modifier.height(12.dp))
                            Text("记账时说的话", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(bill.rawText, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (bill.photoUri != null) {
                            Spacer(Modifier.height(8.dp))
                            Text("📎 这笔账附有图片", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Button(onClick = { timestamp?.let { vm.save(amount, detail, it) } },
                    enabled = !state.isSaving && initialized, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (state.isSaving) "正在保存…" else "保存修改")
                }
                TextButton(onClick = { confirmDelete = true }, enabled = !state.isSaving,
                    modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("删除这笔账", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text("删除这笔账？") },
            text = { Text("${detail.ifBlank { state.categoryName }}  ¥$amount\n删除后无法恢复。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) {
                Text("确认删除", color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("保留") } })
    }
}
