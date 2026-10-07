package com.jiligulu.app.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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

private val SettingsTabs = listOf("外观", "互动", "提醒", "数据", "关于")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
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
    val pagerState = rememberPagerState(initialPage = if (checkUpdatesOnOpen) SettingsTabs.lastIndex else 0) { SettingsTabs.size }
    val tabsScroll = rememberLazyListState()
    var editingField by remember { mutableStateOf<ProfileSettingField?>(null) }
    var fieldText by remember { mutableStateOf("") }
    fun edit(field: ProfileSettingField, value: String) { vm.clearError(); fieldText=value; editingField=field }
    editingField?.takeIf { (if (it == ProfileSettingField.API_KEY) 3 else 1) == pagerState.currentPage }?.let { field ->
        GuluDialog(field.title, { if(!state.isSaving) {editingField=null;fieldText=""} }, confirmLabel="保存", compact=true,
            dense=true,compactWidth=280.dp,busy=state.isSaving,
            confirmEnabled=field!=ProfileSettingField.NAME || fieldText.isNotBlank(),
            onConfirm={ fieldScope.launch { if(vm.saveField(field,fieldText)) {editingField=null;fieldText=""} } }) {
            CompactFormField(if(field==ProfileSettingField.API_KEY) "密钥" else field.title,
                fieldText,{fieldText=it;vm.clearError()},enabled=!state.isSaving,
                visualTransformation=if(field==ProfileSettingField.API_KEY) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
            if(field==ProfileSettingField.SUFFIX) Text("留空时使用「大人」",style=MaterialTheme.typography.bodySmall)
            if(field==ProfileSettingField.API_KEY) Text(
                if (com.jiligulu.app.core.ai.AiConfig.DEFAULT_API_KEY.isNotBlank()) "留空使用内置配置"
                else "此包需要填写你的 DeepSeek API Key", style=MaterialTheme.typography.bodySmall)
            state.error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
        }
    }

    // Permission launchers belong to the UI; preference writes and scheduling belong to the VM.
    val context = LocalContext.current
    val settingsPrefs = remember(context.applicationContext) { UserPrefs(context.applicationContext) }
    val selectedSkin by settingsPrefs.littleWorldSkin.collectAsStateWithLifecycle(initialValue = null)
    var showSkinSelector by rememberSaveable { mutableStateOf(false) }
    var feedbackSound by remember(context) { mutableStateOf(UiSound.enabled(context)) }
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
    val appearanceScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val interactionScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val reminderScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val dataScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val aboutScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val pageScrollStates = listOf(appearanceScroll, interactionScroll, reminderScroll, dataScroll, aboutScroll)
    LaunchedEffect(pagerState.currentPage) {
        editingField = null
        fieldText = ""
        showSkinSelector = false
        showHandbook = false
        showFontLicense = false
        showIntervalPicker = false
        showQuietStart = false
        showQuietEnd = false
        tabsScroll.animateScrollToItem(pagerState.currentPage)
    }
    LaunchedEffect(checkUpdatesOnOpen, pagerState.currentPage, aboutScroll.maxValue) {
        if (checkUpdatesOnOpen && pagerState.currentPage == SettingsTabs.lastIndex && aboutScroll.maxValue in 1 until Int.MAX_VALUE) {
            aboutScroll.scrollTo(aboutScroll.maxValue)
        }
    }

    if (showIntervalPicker && pagerState.currentPage == 2) {
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
    if (showQuietStart && pagerState.currentPage == 2) {
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
    if (showQuietEnd && pagerState.currentPage == 2) {
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
            LazyRow(Modifier.fillMaxWidth().testTag("settings-tabs"), state = tabsScroll,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                itemsIndexed(SettingsTabs, key = { _, tab -> tab }) { index, tab ->
                    FilterChip(selected=pagerState.currentPage==index,
                        onClick=uiTap {fieldScope.launch {pagerState.animateScrollToPage(index)}},enabled=editable,
                        modifier=Modifier.testTag("settings-tab-$tab"), label={Text(tab, maxLines=1)})
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
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().padding(padding).testTag("settings-pages"),
                userScrollEnabled = editable, beyondViewportPageCount = 1,
                verticalAlignment = Alignment.Top, key = { SettingsTabs[it] }) { page ->
            val settingsTab = SettingsTabs[page]
            val pageActive = page == pagerState.currentPage && !pagerState.isScrollInProgress
            CompositionLocalProvider(LocalSettingPageActive provides pageActive) {
            SpringScrollColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(if (page == pagerState.currentPage) "settings-list" else "settings-list-$settingsTab")
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                state = pageScrollStates[page],
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (settingsTab == "互动") SettingsSection("你的称呼", "💌",
                        help = "名字和称呼后缀分别点击修改，分别保存。后缀留空时，阿噜会称你为“大人”。") {
                        ProfileSettingRow("名字",state.nickname,editable) { edit(ProfileSettingField.NAME,state.nickname) }
                        ProfileSettingRow("称呼后缀",state.suffix,editable) { edit(ProfileSettingField.SUFFIX,state.suffix) }
                    }

                    if (settingsTab == "互动") SettingsSection("阿噜的记性", "🌱") {
                        CompanionMemorySettings(enabled = editable)
                    }

                    if (settingsTab == "外观") SettingsSection("主题与皮肤", "🎨") {
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
                        ProfileSettingRow("全局皮肤", selectedSkin?.title?.let { "$it · 预览" } ?: "打开预览", editable) {
                            showSkinSelector = true
                        }
                    }
                    if (settingsTab == "外观") SettingsSection("显示", "🖼️") {
                        DisplayPerformanceSettings(enabled = editable)
                        StatsDisplaySettings(enabled = editable)
                    }
                    if (settingsTab == "互动") SettingsSection("声音", "🔊") {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text("按键与棋子音效",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                            Switch(checked=feedbackSound,enabled=editable,onCheckedChange={enabled->
                                feedbackSound=enabled;UiSound.setEnabled(context,enabled)
                                if(enabled) UiSound.toggle(context)
                            })
                        }
                    }
                    if (settingsTab == "互动") SettingsSection("悬浮与玻璃", "📷") {
                        com.jiligulu.app.ui.capture.FloatingCaptureSettings()
                    }

                    if (settingsTab == "提醒") SettingsSection("喝水提醒", "💧",
                        help = "提醒间隔可设为 1 分钟到 12 小时 59 分钟。免打扰支持跨午夜；开始与结束相同则关闭免打扰。所有改动自动保存。通知、准时提醒与后台运行权限会影响提醒是否及时出现。") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("让阿噜提醒我", style = MaterialTheme.typography.bodyMedium)
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
                                enabled = editable,
                                onClick = uiTap { showIntervalPicker = true }
                            )
                            Text("免打扰时段", style = MaterialTheme.typography.labelLarge)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TimePickerField(label = "开始", value = state.quietStartText.ifBlank { "23:00" },
                                    modifier = Modifier.weight(1f), enabled = editable, onClick = uiTap { showQuietStart = true })
                                TimePickerField(label = "结束", value = state.quietEndText.ifBlank { "08:00" },
                                    modifier = Modifier.weight(1f), enabled = editable, onClick = uiTap { showQuietEnd = true })
                            }
                            ReminderPermissionHint()
                        }
                    }

                    if (settingsTab == "数据") SettingsSection("AI 服务", "✨",
                        help = "自定义 API Key 保存在当前设备，调用 DeepSeek 时用于身份验证。AI 对话会发送你的消息、最近对话及部分账本上下文，用于理解请求；开启阿噜的记性时，也会带上已保存的小记忆。API Key 单独保存，留空时使用可用的内置配置。") {
                        AiUsageSettings()
                        ProfileSettingRow("自定义 API Key",if(state.apiKey.isBlank()) {
                            if (com.jiligulu.app.core.ai.AiConfig.DEFAULT_API_KEY.isNotBlank()) "使用内置配置" else "未填写 · 点击设置"
                        } else "已设置 · 点击修改",editable) {
                            edit(ProfileSettingField.API_KEY,state.apiKey)
                        }
                    }

                    if (settingsTab == "数据") SettingsSection("数据管理", "🗂️") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("回收站", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "已删账单与草稿，可恢复",
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

                    if (settingsTab == "关于") SettingsSection("关于叽里咕噜", "🌱",
                        help = "叽里咕噜是一个本地优先的 AI 记账小助手，也是会唠叨你好好吃饭的小搭子。\n\n账单、对话和设置保存在这台手机。AI 对话会把消息、最近对话及部分账本上下文发送给 DeepSeek，用于理解请求；启用记性时会附带小记忆。无需登录，暂不支持云同步或应用内备份。\n\n阿噜想说的话：谢谢你愿意把每天的花销交给我。我不会评判你买了什么，但如果你连着两天只吃面，我可能会念叨一句要记得吃肉。") {
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
                        AboutLine(
                            title = "制作人",
                            value = "路陌",
                            brand = true
                        )
                        AboutLine(title = "AI 助手", value = "DeepSeek")
                        AboutLine(title = "数据保存", value = "本机")
                        AboutLine(title = "账号同步", value = "无需登录 · 暂无云同步")

                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = uiTap { showHandbook = true }) { Text("阿噜使用手册") }
                            TextButton(onClick = uiTap { showFontLicense = true }) { Text("字体与开源许可") }
                        }
                    }
                    if (settingsTab == "关于") SettingsCompanionHeader(state.nickname, state.suffix, active = pageActive)
                    if (settingsTab == "关于") SettingsSection("版本与更新", "🎁") { UpdateSettingsCard(checkOnOpen = checkUpdatesOnOpen && pageActive) }
                    }
                    Text("慢慢记，日子也会慢慢发光 ♡", modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            }
            }
        }
    }

    if (showSkinSelector && pagerState.currentPage == 0) GuluDialog("小世界换装", { showSkinSelector = false }, compact = true,
        dense = true, compactWidth = 320.dp, confirmLabel = "好啦") {
        LittleWorldSkinSettings(enabled = editable)
    }
    if (showHandbook && pagerState.currentPage == SettingsTabs.lastIndex) HandbookDialog(onDismiss = { showHandbook = false })
    if (showFontLicense && pagerState.currentPage == SettingsTabs.lastIndex) {
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
private fun SettingsCompanionHeader(nickname: String, suffix: String, active: Boolean) {
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
            GuluMascot(modifier = Modifier.padding(start=8.dp).size(64.dp), onClick = uiTap(nextNote), active = active)
        }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: String,
    help: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(icon, style = MaterialTheme.typography.titleMedium)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            help?.let { SettingHelpButton(title, it) }
        }
        content()
    }
}
