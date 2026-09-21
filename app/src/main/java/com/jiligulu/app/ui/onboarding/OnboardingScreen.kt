package com.jiligulu.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.audio.KeyboardSound
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.ui.components.LedgerCard
import com.jiligulu.app.ui.persona.GuluMascot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val GREETING = "你好呀，我是叽里咕噜。\n该怎么称呼你呢？"
private enum class Phase { GREETING, INPUT, REPLY }

class OnboardingViewModel(private val prefs: UserPrefs) : ViewModel() {
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _savedName = MutableStateFlow<String?>(null)
    val savedName = _savedName.asStateFlow()
    val soundEnabled = prefs.typingSoundEnabled
    val nickname = prefs.nickname
    fun setSound(enabled: Boolean) { viewModelScope.launch {
        try { prefs.setTypingSoundEnabled(enabled) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _error.value = "声音设置还没存好，请再试一次。" }
    } }
    fun saveName(name: String, preview: Boolean = false) {
        if (_saving.value || name.isBlank()) return
        _saving.value = true
        _error.value = null
        viewModelScope.launch {
            try { if (!preview) prefs.setNickname(name); _savedName.value = name.trim() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _error.value = "名字还没存好，再试一次吧。" }
            finally { _saving.value = false }
        }
    }
    companion object {
        val Factory = viewModelFactory { initializer {
            OnboardingViewModel((this[APPLICATION_KEY] as JiliguluApp).container.userPrefs)
        } }
    }
}

@Composable
fun OnboardingScreen(onDone: () -> Unit, active: Boolean = true, preview: Boolean = false,
    vm: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory)) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember { KeyboardSound(context.applicationContext) }
    DisposableEffect(player) { onDispose { player.close() } }
    val sound by vm.soundEnabled.collectAsStateWithLifecycle(initialValue = true)
    val currentSound by rememberUpdatedState(sound)
    val saving by vm.saving.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val savedName by vm.savedName.collectAsStateWithLifecycle()
    val storedName by vm.nickname.collectAsStateWithLifecycle(initialValue = "")
    val done by rememberUpdatedState(onDone)
    var phase by rememberSaveable { mutableStateOf(Phase.GREETING) }
    var shownCount by rememberSaveable { mutableIntStateOf(0) }
    var replyCount by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    val reply = "${savedName ?: name}大人，阿噜！\n以后记账，就让我陪着你吧 ♡"
    var leaving by remember { mutableStateOf(false) }
    val finish = { if (!leaving) { leaving = true; done() } }
    LaunchedEffect(preview, storedName) { if (preview && name.isBlank()) name = storedName }

    LaunchedEffect(savedName) {
        if (savedName != null && phase != Phase.REPLY) phase = Phase.REPLY
    }
    LaunchedEffect(active, phase, lifecycle) {
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            when (phase) {
                Phase.GREETING -> {
                    if (shownCount == 0) delay(160)
                    while (shownCount < GREETING.length) {
                        delay(65)
                        shownCount++
                        if (currentSound && !GREETING[shownCount - 1].isWhitespace()) player.tap()
                    }
                    phase = Phase.INPUT
                }
                Phase.REPLY -> {
                    while (replyCount < reply.length) {
                        delay(60)
                        replyCount++
                        if (currentSound && !reply[replyCount - 1].isWhitespace()) player.tap()
                    }
                    delay(500)
                    finish()
                }
                Phase.INPUT -> Unit
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.setSound(!sound) }) {
                    Icon(if (sound) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff,
                        contentDescription = if (sound) "关闭打字音效" else "开启打字音效")
                }
                Spacer(Modifier.weight(1f))
                if (phase != Phase.INPUT) TextButton(onClick = {
                    if (phase == Phase.GREETING) { shownCount = GREETING.length; phase = Phase.INPUT }
                    else finish()
                }, enabled = active) { Text("跳过动画") }
            }
            Spacer(Modifier.height(24.dp))
            GuluMascot(Modifier.size(160.dp))
            LedgerCard {
                Text(if (phase == Phase.REPLY) reply.take(replyCount) else GREETING.take(shownCount),
                    style = MaterialTheme.typography.titleMedium, minLines = 3)
            }
            if (phase == Phase.INPUT) {
                OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("你的名字") }, placeholder = { Text("咕噜应该怎么叫你？") },
                    singleLine = true, enabled = !saving, shape = MaterialTheme.shapes.large)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { vm.saveName(name, preview) }, enabled = name.isNotBlank() && !saving,
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.extraLarge) {
                    Text(if (saving) "记住啦，稍等一下…" else if (preview) "继续看动画" else "就这样称呼吧")
                }
            }
            Text(if (preview) "这是动画预览，不会改动你的称呼和账本" else "打字声跟随媒体音量，静音模式不播放", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
