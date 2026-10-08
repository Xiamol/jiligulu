package com.jiligulu.app.ui.billdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import com.jiligulu.app.core.audio.UiCue
import com.jiligulu.app.core.audio.UiSound
import com.jiligulu.app.data.local.entity.BillSource
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.ui.components.SpringScrollColumn
import com.jiligulu.app.ui.components.BillDateTimeField
import com.jiligulu.app.ui.components.LedgerCard
import com.jiligulu.app.ui.components.CategoryBadge
import com.jiligulu.app.domain.color.GoldenAnglePalette
import com.jiligulu.app.ui.memories.LifePhotoField
import com.jiligulu.app.ui.memories.MemoryPosterButton
import com.jiligulu.app.ui.components.CompactFormField
import com.jiligulu.app.ui.components.uiTap

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
    var photoBusy by remember(billId) { mutableStateOf(false) }
    val busy by rememberUpdatedState(state.isSaving || photoBusy)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !busy })
    var initialized by rememberSaveable(billId) { mutableStateOf(false) }
    var amount by rememberSaveable(billId) { mutableStateOf("") }
    var detail by rememberSaveable(billId) { mutableStateOf("") }
    var note by rememberSaveable(billId) { mutableStateOf("") }
    var photoPath by rememberSaveable(billId) { mutableStateOf("") }
    var timestamp by rememberSaveable(billId) { mutableStateOf<Long?>(null) }
    var confirmDelete by rememberSaveable(billId) { mutableStateOf(false) }
    var completionCue by rememberSaveable(billId) { mutableStateOf(UiCue.CONFIRM) }
    var completionHeard by rememberSaveable(billId) { mutableStateOf(false) }
    val bill = state.bill

    LaunchedEffect(bill) {
        if (!initialized && bill != null) {
            amount = Formatters.fenToYuanText(bill.amountFen)
            detail = bill.detail.ifBlank { state.categoryName }
            note = bill.note
            photoPath = bill.photoUri.orEmpty()
            timestamp = bill.timestamp
            initialized = true
        }
    }
    LaunchedEffect(state.isComplete) {
        if (state.isComplete) {
            if (!completionHeard) { completionHeard = true; UiSound.play(app, completionCue) }
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("这一笔小账", style = MaterialTheme.typography.titleLarge)
                    Text("把生活的小细节，好好收起来。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        SpringScrollColumn(Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.isLoading) {
                CircularProgressIndicator()
            } else if (bill == null && !state.isComplete && !state.isSaving) {
                Text(state.error ?: "这条账单已不存在。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (bill != null) {
                LedgerCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryBadge(state.categoryName, state.icon, tint = GoldenAnglePalette.colorForHue(state.colorHue))
                        Text("  ${state.categoryName} · ${if (bill.type == BillType.EXPENSE) "支出" else "收入"}",
                            style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(when (bill.source) {
                        BillSource.MANUAL -> "手动记账"
                        BillSource.AI_CHAT -> "对话记账"
                        BillSource.SCREEN -> "识屏记账"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CompactFormField("金额", amount, { amount = it }, prefix = "¥",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    enabled = !busy)
                CompactFormField("细则", detail, { detail = it.take(500) }, enabled = !busy)
                BillDateTimeField(timestamp = timestamp, onTimestampChange = { timestamp = it },
                    allowCurrentTime = false, enabled = !state.isSaving)
                CompactFormField("备注", note, { note = it.take(500) }, placeholder = "留一句生活记忆", enabled = !busy)
                Column {
                    LifePhotoField(photoPath, onChange = { photoPath = it }, enabled = !state.isSaving,
                        onBusyChange = { photoBusy = it },
                        shouldRetainCopies = { vm.state.value.isSaving || vm.state.value.isComplete })
                    MemoryPosterButton(detail.ifBlank { state.categoryName }, note, photoPath,
                        amountFen = Formatters.yuanTextToFen(amount), dateMillis = timestamp)
                }
                if (bill.rawText.isNotBlank()) {
                    LedgerCard {
                        Text("记账时说的话", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(bill.rawText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }
            if (bill != null) Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = uiTap { confirmDelete = true }, enabled = !busy) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = uiTap { timestamp?.let { completionCue = UiCue.CONFIRM; vm.save(amount, detail, it, note, photoPath.takeIf(String::isNotBlank)) } },
                    enabled = !busy && initialized, modifier = Modifier.height(44.dp)) {
                    Text(if (state.isSaving) "正在保存…" else "保存修改")
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text("删除这笔账？") },
            text = { Text("${detail.ifBlank { state.categoryName }}  ¥$amount\n删除后可在「设置 → 数据管理 → 回收站」里找回。") },
            confirmButton = { TextButton(onClick = uiTap { confirmDelete = false; completionCue = UiCue.REMOVE; vm.delete() }) {
                Text("确认删除", color = MaterialTheme.colorScheme.error)
            } })
    }
}
