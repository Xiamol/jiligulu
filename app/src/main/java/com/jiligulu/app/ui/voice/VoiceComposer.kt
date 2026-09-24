package com.jiligulu.app.ui.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.jiligulu.app.data.prefs.UserPrefs

@Composable
fun VoiceComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    ready: Boolean,
    sending: Boolean,
    suppliedController: SpeechInputController? = null
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val offline = if (suppliedController == null) remember(context.applicationContext) {
        OfflineSpeechSession(context.applicationContext)
    } else null
    val modelState by (offline?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(OfflineModelState(OfflineModelPhase.READY)) })
        .collectAsStateWithLifecycle()
    val modelReady = suppliedController != null || modelState.phase == OfflineModelPhase.READY
    val controller = suppliedController ?: remember(offline) {
        SpeechInputController { checkNotNull(offline).engine() }
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val prefs = remember(context.applicationContext) { UserPrefs(context.applicationContext) }
    val preferredVoice by prefs.preferVoiceInput.collectAsStateWithLifecycle(false)
    val currentPreferredVoice by rememberUpdatedState(preferredVoice)
    var editingTranscript by rememberSaveable { mutableStateOf(false) }
    val voiceMode = preferredVoice && !editingTranscript
    val scope = rememberCoroutineScope()
    fun chooseMode(voice: Boolean) {
        editingTranscript = false
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try { prefs.setPreferVoiceInput(voice) }
                catch (_: Exception) { controller.showError("输入模式没能保存，请再切换一次。") }
            }
        }
    }
    fun sendEditedText() { onSend(); editingTranscript = false }
    val currentText by rememberUpdatedState(text)
    val currentChange by rememberUpdatedState(onTextChange)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) chooseMode(true)
        if (!granted) controller.showError("未允许麦克风权限，仍可用键盘输入。")
        // Never begin recording from the permission callback: the original finger press has ended.
    }
    fun canRecord() = suppliedController != null || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun begin() {
        if (!ready || sending || !modelReady) return
        if (canRecord()) controller.start() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(preferredVoice, offline) {
        if (preferredVoice && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) offline?.prepare()
        else if (!preferredVoice) { controller.cancel(); offline?.unload() }
    }
    DisposableEffect(owner, controller, offline) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { controller.cancel(); offline?.unload() }
            if (event == Lifecycle.Event.ON_START && currentPreferredVoice) offline?.prepare()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); controller.cancel(); offline?.close() }
    }
    LaunchedEffect(state.result?.id) {
        state.result?.let {
            currentChange(if (currentText.isBlank()) it.text else currentText.trimEnd() + " " + it.text)
            editingTranscript = true
            controller.consumeResult(it.id)
        }
    }
    LaunchedEffect(state.session, state.phase) {
        when (state.phase) {
            VoicePhase.LISTENING, VoicePhase.CAPTURED -> { delay(30_000); controller.stop() }
            VoicePhase.PROCESSING -> { delay(12_000); controller.timeout(state.session) }
            VoicePhase.IDLE -> Unit
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        state.error?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("voice-error").padding(bottom = 6.dp))
            if (message.contains("权限")) TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
            }) { Text("打开系统设置") }
        }
        if (voiceMode) {
            modelState.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { offline?.prepare() }) { Text("重新准备模型") }
            }
            Text(state.partial.ifBlank { "离线识别 · 松开后可修改文字 · 移出按钮取消" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(enabled = ready && !sending, onClick = {
                controller.cancel()
                if (voiceMode) chooseMode(false)
                else {
                    focus.clearFocus()
                    if (canRecord()) chooseMode(true) else permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }, modifier = Modifier.testTag("voice-toggle")) {
                Icon(if (voiceMode) Icons.Default.Keyboard else Icons.Default.Mic, if (voiceMode) "切换键盘" else "语音输入")
            }
            if (voiceMode) {
                val interactive = ready && !sending && modelReady && state.phase != VoicePhase.PROCESSING
                Box(Modifier.weight(1f).heightIn(min = 56.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraLarge)
                    .testTag("voice-hold")
                    .semantics {
                        role = Role.Button
                        onClick("开始或结束语音输入") {
                            if (state.busy) controller.stop() else if (interactive) begin()
                            true
                        }
                    }
                    .pointerInput(ready, sending, voiceMode, modelReady) {
                        detectTapGestures(onPress = {
                            if (ready && !sending && modelReady && controller.state.value.phase != VoicePhase.PROCESSING) {
                                begin()
                                if (tryAwaitRelease()) controller.stop() else controller.cancel()
                            }
                        })
                    }, contentAlignment = Alignment.Center) {
                    Text(if (!modelReady) {
                        if (modelState.phase == OfflineModelPhase.FAILED) "模型暂不可用" else "正在准备离线语音…"
                    } else when (state.phase) {
                        VoicePhase.IDLE -> "按住说话"
                        VoicePhase.LISTENING -> "正在听，松开结束"
                        VoicePhase.PROCESSING -> "正在转成文字…"
                        VoicePhase.CAPTURED -> "松开填入文字"
                    }, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            } else OutlinedTextField(value = text, onValueChange = onTextChange,
                modifier = Modifier.weight(1f).testTag("chat-input"), enabled = ready,
                placeholder = { Text(if (sending) "阿噜正在回复…" else "比如：早饭 9 元") },
                singleLine = true, shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (!sending && text.isNotBlank()) sendEditedText() }),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
            IconButton(onClick = ::sendEditedText, enabled = ready && !sending && !state.busy && !voiceMode && text.isNotBlank(),
                colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary), modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, "发送")
            }
        }
    }
}
