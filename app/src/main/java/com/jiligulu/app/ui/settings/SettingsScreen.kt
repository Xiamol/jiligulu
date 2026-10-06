package com.jiligulu.app.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.BuildConfig
import com.jiligulu.app.ui.components.PaperNote
import com.jiligulu.app.ui.components.GuluDialog
import com.jiligulu.app.ui.components.SpringScrollColumn
import com.jiligulu.app.ui.components.TimePickerDialog
import com.jiligulu.app.ui.components.TimePickerField
import com.jiligulu.app.ui.components.CompactFormField
import com.jiligulu.app.ui.components.uiTap
import com.jiligulu.app.core.audio.UiSound
import com.jiligulu.app.ui.persona.GuluMascot
import com.jiligulu.app.ui.theme.GuluBrandFont
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onPreviewWelcome: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    checkUpdatesOnOpen: Boolean = false,
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val fieldScope = rememberCoroutineScope()
    var editingField by remember { mutableStateOf<ProfileSettingField?>(null) }
    var fieldText by remember { mutableStateOf("") }
    fun edit(field: ProfileSettingField, value: String) { vm.clearError(); fieldText=value; editingField=field }
    editingField?.let { field ->
        GuluDialog(field.title, { if(!state.isSaving) {editingField=null;fieldText=""} }, confirmLabel="保存", compact=true,
            dense=true,compactWidth=280.dp,busy=state.isSaving,
            confirmEnabled=field!=ProfileSettingField.NAME || fieldText.isNotBlank(),
            onConfirm={ fieldScope.launch { if(vm.saveField(field,fieldText)) {editingField=null;fieldText=""} } }) {
            CompactFormField(if(field==ProfileSettingField.API_KEY) "密钥" else field.title,
                fieldText,{fieldText=it;vm.clearError()},enabled=!state.isSaving,
                visualTransformation=if(field==ProfileSettingField.API_KEY) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
            if(field==ProfileSettingField.SUFFIX) Text("留空时使用「大人」",style=MaterialTheme.typography.bodySmall)
            if(field==ProfileSettingField.API_KEY) Text("留空使用内置配置",style=MaterialTheme.typography.bodySmall)
            state.error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
        }
    }

    // Permission launchers belong to the UI; preference writes and scheduling belong to the VM.
    var settingsTab by rememberSaveable { mutableStateOf(if (checkUpdatesOnOpen) "关于" else "日常") }
    val context = LocalContext.current
    var feedbackSound by remember(context) { mutableStateOf(UiSound.enabled(context)) }
    var showSoundSamples by remember { mutableStateOf(false) }
    LaunchedEffect(context) { UiSound.warmup(context) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The in-app reminder still works when notification permission is declined. */ }
    val editable = state.isLoaded && !state.isSaving
    var showFontLicense by rememberSaveable { mutableStateOf(false) }
    var showHandbook by rememberSaveable { mutableStateOf(false) }
    // 时间设置一律走「点击输入框 → 弹窗滚轮 → 确认」，不做页面内常驻滚轮。
    var showIntervalPicker by rememberSaveable { mutableStateOf(false) }
    var showQuietStart by rememberSaveable { mutableStateOf(false) }
    var showQuietEnd by rememberSaveable { mutableStateOf(false) }
    val settingsScroll = remember(settingsTab) { ScrollState(0) }
    LaunchedEffect(checkUpdatesOnOpen, settingsTab, settingsScroll.maxValue) {
        if (checkUpdatesOnOpen && settingsTab == "关于" && settingsScroll.maxValue in 1 until Int.MAX_VALUE) {
            settingsScroll.scrollTo(settingsScroll.maxValue)
        }
    }

    if (showIntervalPicker) {
        val total = state.waterInterval
        TimePickerDialog(
            title = "提醒间隔",
            hour = total / 60,
            minute = total % 60,
            hourRange = 0..12,
            minuteStep = 1,
            // 不再设下限提示：1 分钟到 12 小时 59 分都允许，让用户自己决定。
            onDismiss = { showIntervalPicker = false },
            onConfirm = { h, m ->
                vm.setWaterInterval(h * 60 + m)
                showIntervalPicker = false
            }
        )
    }
    if (showQuietStart) {
        TimePickerDialog(
            title = "免打扰开始时间",
            hour = state.quietStartText.minutesOfDayOr(23 * 60) / 60,
            minute = state.quietStartText.minutesOfDayOr(23 * 60) % 60,
            onDismiss = { showQuietStart = false },
            onConfirm = { h, m ->
                vm.setQuietStart("%02d:%02d".format(h, m))
                showQuietStart = false
            }
        )
    }
    if (showQuietEnd) {
        TimePickerDialog(
            title = "免打扰结束时间",
            hour = state.quietEndText.minutesOfDayOr(8 * 60) / 60,
            minute = state.quietEndText.minutesOfDayOr(8 * 60) % 60,
            onDismiss = { showQuietEnd = false },
            onConfirm = { h, m ->
                vm.setQuietEnd("%02d:%02d".format(h, m))
                showQuietEnd = false
            }
        )
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = uiTap(onBack), enabled = !state.isSaving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
            FlowRow(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("日常", "提醒", "数据", "关于").forEach { tab ->
                    FilterChip(selected=settingsTab==tab,onClick=uiTap {settingsTab=tab},enabled=state.isLoaded,label={Text(tab)})
                }
            }
            }
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            SpringScrollColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .testTag("settings-list")
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                state = settingsScroll,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                state.error?.let { error ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                            if (!state.isLoaded) TextButton(onClick = uiTap(vm::load)) { Text("重新读取") }
                        }
                    }
                }

                if (state.isLoaded) {
                    SettingsCompanionHeader(state.nickname, state.suffix)

                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (settingsTab == "日常") SettingsSection("你的称呼", "让阿噜用你喜欢的方式叫你", "💌") {
                        ProfileSettingRow("名字",state.nickname,editable) { edit(ProfileSettingField.NAME,state.nickname) }
                        ProfileSettingRow("称呼后缀",state.suffix,editable) { edit(ProfileSettingField.SUFFIX,state.suffix) }
                    }

                    if (settingsTab == "日常") SettingsSection("外观", "给小账本换个喜欢的模样", "🎨") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                UserPrefs.THEME_SYSTEM to "跟随系统",
                                UserPrefs.THEME_LIGHT to "浅色",
                                UserPrefs.THEME_DARK to "深色"
                            ).forEach { (mode, label) ->
                                FilterChip(
                                    selected = state.themeMode == mode,
                                    onClick = uiTap { vm.setThemeMode(mode) },
                                    enabled = editable,
                                    label = { Text(label) }
                                )
                            }
                        }
                        LittleWorldSkinSettings(enabled = editable)
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text("按键与棋子音效",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                            TextButton(onClick={showSoundSamples=true},enabled=feedbackSound) {Text("试听",style=MaterialTheme.typography.labelSmall)}
                            Switch(checked=feedbackSound,onCheckedChange={enabled->
                                feedbackSound=enabled;UiSound.setEnabled(context,enabled)
                                if(enabled) UiSound.toggle(context)
                            })
                        }
                    }

                    if (settingsTab == "提醒") SettingsSection("喝水提醒", "工作再忙，也记得照顾自己", "💧") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("让阿噜提醒我", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "提醒设置会自动保存",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = state.waterEnabled,
                                enabled = editable,
                                onCheckedChange = { enabled ->
                                    UiSound.toggle(context)
                                    vm.setWaterEnabled(enabled)
                                    if (enabled && Build.VERSION.SDK_INT >= 33 &&
                                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                        PackageManager.PERMISSION_GRANTED
                                    ) {
                                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                            )
                        }
                        if (state.waterEnabled) {
                            val intervalHours = state.waterInterval / 60
                            val intervalMinutes = state.waterInterval % 60
                            TimePickerField(
                                label = "提醒间隔",
                                value = intervalText(intervalHours, intervalMinutes),
                                supporting = "点右边选个时长，1 分钟到 12 小时 59 分都行",
                                enabled = editable,
                                onClick = uiTap { showIntervalPicker = true }
                            )
                            Text("免打扰时段", style = MaterialTheme.typography.labelLarge)
                            TimePickerField(
                                label = "开始",
                                value = state.quietStartText.ifBlank { "23:00" },
                                enabled = editable,
                                onClick = uiTap { showQuietStart = true }
                            )
                            TimePickerField(
                                label = "结束",
                                value = state.quietEndText.ifBlank { "08:00" },
                                enabled = editable,
                                onClick = uiTap { showQuietEnd = true }
                            )
                            Text(
                                "支持跨午夜；开始和结束相同则不免打扰。喝水时间到了，阿噜会提醒你；账本页也能点水杯开始。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            ReminderPermissionHint()
                        }
                    }

                    if (settingsTab == "提醒") SettingsSection("悬浮记账", "阿噜陪你跨应用记一笔", "📷") { com.jiligulu.app.ui.capture.FloatingCaptureSettings() }

                    if (settingsTab == "数据") SettingsSection("AI 服务", "让每一句生活，都有回应", "✨") {
                        AiUsageSettings()
                        ProfileSettingRow("自定义 API Key",if(state.apiKey.isBlank()) "使用内置配置" else "已设置 · 点击修改",editable) {
                            edit(ProfileSettingField.API_KEY,state.apiKey)
                        }
                        Text(
                            "保存在当前设备，调用 DeepSeek 时用于身份验证。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (settingsTab == "数据") SettingsSection("数据管理", "删掉的东西，先放在手边", "🗂️") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("回收站", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "删掉的账单会先收在这儿，后悔了能捞回来",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = uiTap(onOpenTrash), modifier = Modifier.testTag("settings-trash-entry")) {
                                Text("去看看")
                            }
                        }
                        ConversationSettingsCard(vm)
                    }

                    if (settingsTab == "关于") SettingsSection("关于叽里咕噜", "小小的账本，大大的生活", "🌱") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("叽里咕噜", style = MaterialTheme.typography.titleLarge.copy(fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal),
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            Surface(shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)) {
                                Text("v${BuildConfig.VERSION_NAME}", modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Text("一个本地优先的 AI 记账小助手，也是一个会唠叨你好好吃饭的小搭子。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        AboutLine(
                            title = "制作人",
                            value = "路陌",
                            brand = true
                        )
                        AboutLine(title = "AI 助手", value = "DeepSeek")
                        AboutLine(title = "本地保存", value = "账单、对话和设置保存在这台手机")
                        AboutLine(title = "AI 对话", value = "消息、最近对话及部分账本上下文会发送给 DeepSeek，用于理解请求")
                        AboutLine(title = "账号同步", value = "无需登录，暂不支持云同步或应用内备份")

                        Text(
                            "阿噜想说的话：谢谢你愿意把每天的花销交给我。我不会评判你买了什么，" +
                                "但如果你连着两天只吃面，我可能会念叨一句要记得吃肉。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                        )

                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = uiTap { showHandbook = true }) { Text("阿噜使用手册") }
                            TextButton(onClick = uiTap { showFontLicense = true }) { Text("字体与开源许可") }
                        }
                    }
                    if (settingsTab == "关于") SettingsSection("版本与更新", "查看版本、检查新消息", "🎁") { UpdateSettingsCard(checkOnOpen = checkUpdatesOnOpen) }
                    }
                    Text("慢慢记，日子也会慢慢发光 ♡", modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showSoundSamples) GuluDialog("听听小声音", { showSoundSamples=false }, compact=true,
        dense=true, compactWidth=260.dp) {
        // 2026-10-06 细分后：纸张拆成三种材质分别试听（拆信/翻页/信笺），
        // 「翻页」不再借用导航音；顺带把棋局与撤销/提示也列出来，方便逐个比对。
        val samples = listOf(
            "拆信" to com.jiligulu.app.core.audio.UiCue.ENVELOPE,
            "翻页" to com.jiligulu.app.core.audio.UiCue.PAGE_TURN,
            "信笺" to com.jiligulu.app.core.audio.UiCue.LETTER,
            "纸张" to com.jiligulu.app.core.audio.UiCue.PAPER,
            "开关" to com.jiligulu.app.core.audio.UiCue.TOGGLE,
            "收好" to com.jiligulu.app.core.audio.UiCue.CONFIRM,
            "悔棋" to com.jiligulu.app.core.audio.UiCue.UNDO,
            "提示" to com.jiligulu.app.core.audio.UiCue.HINT,
            "木棋" to com.jiligulu.app.core.audio.UiCue.WOOD_MOVE,
            "棋石" to com.jiligulu.app.core.audio.UiCue.STONE_MOVE,
            "吃子" to com.jiligulu.app.core.audio.UiCue.CAPTURE,
            "将军" to com.jiligulu.app.core.audio.UiCue.CHECK)
        samples.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (label,cue) ->
                    TextButton(onClick={UiSound.play(context,cue)},modifier=Modifier.weight(1f)) { Text(label) }
                }
            }
        }
    }
    if (showHandbook) HandbookDialog(onDismiss = { showHandbook = false })
    if (showFontLicense) {
        GuluDialog(
            onDismiss = { showFontLicense = false },
            title = "字体与开源许可"
        ) {
            Text("Noto Sans SC\nCopyright 2014–2021 Adobe", style = MaterialTheme.typography.bodyMedium)
            Text("ZCOOL KuaiLe（站酷快乐体）\nCopyright 2018 The ZCOOL KuaiLe Project Authors",
                style = MaterialTheme.typography.bodyMedium)
            Text("以上字体使用 SIL Open Font License 1.1。完整版权声明与许可证已随应用内置。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 关于页的一行：左边浅色标签，右边内容。brand=true 时用卡通字体（制作人署名）。 */
@Composable
private fun AboutLine(title: String, value: String, brand: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            title,
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = if (brand) {
                MaterialTheme.typography.titleMedium.copy(
                    fontFamily = GuluBrandFont,
                    fontWeight = FontWeight.Normal
                )
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = if (brand) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 「2 小时 30 分钟」这样读起来比 02:30 更自然。 */
private fun intervalText(hours: Int, minutes: Int): String = buildString {
    if (hours > 0) append("$hours 小时")
    if (minutes > 0) append("$minutes 分钟")
    if (isEmpty()) append("0 分钟")
}

private fun String.minutesOfDayOr(fallback: Int): Int {
    val parts = split(":")
    if (parts.size != 2) return fallback
    val h = parts[0].toIntOrNull() ?: return fallback
    val m = parts[1].toIntOrNull() ?: return fallback
    if (h !in 0..23 || m !in 0..59) return fallback
    return h * 60 + m
}

@Composable
private fun SettingsCompanionHeader(nickname: String, suffix: String) {
    var noteIndex by rememberSaveable { mutableStateOf(CompanionCornerNotes.randomIndex()) }
    val nextNote = { noteIndex = CompanionCornerNotes.nextIndex(noteIndex) }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("阿噜的小角落", style = MaterialTheme.typography.titleSmall.copy(fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal),
                    color = MaterialTheme.colorScheme.primary)
                PaperNote(CompanionCornerNotes.render(noteIndex, nickname, suffix),
                    modifier = Modifier.padding(vertical = 2.dp))
                TextButton(onClick = uiTap(nextNote), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("再听一句悄悄话 ♡", style = MaterialTheme.typography.labelSmall)
                }
            }
            GuluMascot(modifier = Modifier.padding(start=8.dp).size(76.dp), onClick = uiTap(nextNote))
        }
}

@Composable
private fun SettingsSection(
    title: String,
    description: String,
    icon: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(icon, style = MaterialTheme.typography.titleMedium)
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
