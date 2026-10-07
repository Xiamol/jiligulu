package com.jiligulu.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.domain.persona.CompanionFact
import com.jiligulu.app.domain.persona.CompanionMemoryState
import com.jiligulu.app.domain.persona.CompanionMemoryPolicy
import com.jiligulu.app.ui.components.GuluDialog
import com.jiligulu.app.ui.components.CompactFormField
import com.jiligulu.app.core.audio.UiSound
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
internal fun CompanionMemorySettings(enabled: Boolean, prefs: UserPrefs = rememberUserPrefs()) {
    val context = LocalContext.current
    val nullableMemory = remember(prefs) { prefs.companionMemory.map<CompanionMemoryState, CompanionMemoryState?> { it } }
    val memory by nullableMemory.collectAsStateWithLifecycle(initialValue = null)
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CompanionFact?>(null) }
    var editText by remember { mutableStateOf("") }
    var clearing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun write(action: suspend () -> Unit, after: () -> Unit = {}) {
        if (busy) return
        busy = true; error = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { withContext(NonCancellable) { action() }; after() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "这次没保存好，再试一下吧" }
            finally { busy = false }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("聊天时慢慢记住我", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        SettingHelpButton("阿噜的记性", "只记你在聊天中亲口说的身份、学业和喜好，不从花销推测。小记忆保存在这台手机，启用时会随对话发送给 DeepSeek；可随时关闭、逐条更正、删除或全部清空。关闭后，已有小记忆会保留，但不再带进新的 AI 请求。")
        Switch(memory?.enabled ?: false, enabled = enabled && memory != null && !busy,
            onCheckedChange = { checked -> UiSound.toggle(context); write({ prefs.setCompanionMemoryEnabled(checked) }) },
            modifier = Modifier.testTag("companion-memory-enabled"))
    }
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled && !busy) { open = true; error = null }
        .padding(vertical = 8.dp).testTag("companion-memory-open"), verticalAlignment = Alignment.CenterVertically) {
        Text("阿噜记得的小事", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("${memory?.facts?.size ?: 0} 条 · 可删改", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
    }
    error?.takeIf { !open }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (open && editing == null && !clearing) {
        val facts = memory?.facts.orEmpty()
        GuluDialog("阿噜记得的小事", onDismiss = { open = false }, compact = true, dense = true,
            confirmLabel = "收好啦", busy = busy) {
            if (facts.isEmpty()) Text("聊到你的爱好和学业时，阿噜会慢慢记住，暂时不用填表 ♡",
                style = MaterialTheme.typography.bodyMedium)
            else facts.forEach { fact ->
                Row(Modifier.fillMaxWidth().testTag("companion-fact-${fact.id}"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(CompanionMemoryPolicy.label(fact.kind), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(fact.value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { editing = fact; editText = fact.value; error = null }, enabled = !busy, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Edit, "更正${fact.value}", Modifier.size(18.dp))
                    }
                    IconButton(onClick = { write({ prefs.removeCompanionMemory(fact.id) }) }, enabled = !busy,
                        modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, "忘记${fact.value}", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (memory?.enabled == false) Text("记性已关闭，这些小事不再带进新的 AI 请求。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (facts.isNotEmpty()) TextButton(onClick = { clearing = true; error = null }, enabled = !busy) { Text("全部忘记") }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
    editing?.let { fact ->
        GuluDialog("更正这条小记忆", onDismiss = { editing = null }, compact = true, dense = true, compactWidth = 280.dp,
            busy = busy, confirmLabel = "保存这一条", dismissLabel = "先等等",
            confirmEnabled = CompanionMemoryPolicy.validValue(editText.trim()),
            onConfirm = { write({ prefs.correctCompanionMemory(fact.id, editText) }, { editing = null }) }) {
            CompactFormField(CompanionMemoryPolicy.label(fact.kind), editText, { if (it.length <= CompanionMemoryPolicy.MAX_VALUE_LENGTH) editText = it }, enabled = !busy)
            if (fact.evidence.isNotBlank()) Text("你曾说：「${fact.evidence}」", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (clearing) GuluDialog("让阿噜重新认识你？", onDismiss = { clearing = false }, compact = true, dense = true,
        busy = busy, confirmLabel = "全部忘记", dismissLabel = "留着吧",
        onConfirm = { write({ prefs.clearCompanionMemories() }, { clearing = false }) }) {
        Text("清空这些小记忆，账单和聊天记录会保留。", style = MaterialTheme.typography.bodyMedium)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable private fun rememberUserPrefs(): UserPrefs {
    val context = LocalContext.current
    return remember(context) { UserPrefs(context) }
}
