package com.jiligulu.app.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
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
import com.jiligulu.app.ui.persona.GuluMascot
import com.jiligulu.app.ui.theme.GuluBrandFont

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onPreviewWelcome: () -> Unit = {},
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val currentOnBack by rememberUpdatedState(onBack)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.saved.collect { currentOnBack() }
        }
    }

    // Permission launchers belong to the UI; preference writes and scheduling belong to the VM.
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The in-app reminder still works when notification permission is declined. */ }
    val editable = state.isLoaded && !state.isSaving
    var showFontLicense by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.isSaving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        bottomBar = {
            if (state.isLoaded) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Button(
                        onClick = vm::save,
                        enabled = editable,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .heightIn(min = 52.dp),
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Text(if (state.isSaving) "正在保存…" else "保存设置")
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .testTag("settings-list")
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                state.error?.let { error ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                            if (!state.isLoaded) TextButton(onClick = vm::load) { Text("重新读取") }
                        }
                    }
                }

                if (state.isLoaded) {
                    SettingsCompanionHeader()

                    SettingsSection("你的称呼", "让阿噜用你喜欢的方式叫你", "💌") {
                        OutlinedTextField(
                            value = state.nickname,
                            onValueChange = vm::setNickname,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("名字") },
                            singleLine = true,
                            enabled = editable,
                            shape = RoundedCornerShape(14.dp)
                        )
                        OutlinedTextField(
                            value = state.suffix,
                            onValueChange = vm::setSuffix,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("称呼后缀") },
                            supportingText = { Text("留空时使用「大人」") },
                            singleLine = true,
                            enabled = editable,
                            shape = RoundedCornerShape(14.dp)
                        )
                    }

                    SettingsSection("外观", "给小账本换个喜欢的模样", "🎨") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                UserPrefs.THEME_SYSTEM to "跟随系统",
                                UserPrefs.THEME_LIGHT to "浅色",
                                UserPrefs.THEME_DARK to "深色"
                            ).forEach { (mode, label) ->
                                FilterChip(
                                    selected = state.themeMode == mode,
                                    onClick = { vm.setThemeMode(mode) },
                                    enabled = editable,
                                    label = { Text(label) }
                                )
                            }
                        }
                    }

                    SettingsSection("喝水提醒", "工作再忙，也记得照顾自己", "💧") {
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
                            Text("提醒间隔", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsViewModel.WATER_INTERVALS.forEach { minutes ->
                                    FilterChip(
                                        selected = state.waterInterval == minutes,
                                        onClick = { vm.setWaterInterval(minutes) },
                                        enabled = editable,
                                        label = { Text("$minutes 分钟") }
                                    )
                                }
                            }
                            Text("免打扰时段", style = MaterialTheme.typography.labelLarge)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                OutlinedTextField(
                                    value = state.quietStartText,
                                    onValueChange = vm::setQuietStart,
                                    modifier = Modifier.weight(1f),
                                    label = { Text("开始时间") },
                                    placeholder = { Text("23:00") },
                                    supportingText = { Text(if (state.quietStartInvalid) "请输入有效时间" else "HH:mm") },
                                    isError = state.quietStartInvalid,
                                    singleLine = true,
                                    enabled = editable,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                OutlinedTextField(
                                    value = state.quietEndText,
                                    onValueChange = vm::setQuietEnd,
                                    modifier = Modifier.weight(1f),
                                    label = { Text("结束时间") },
                                    placeholder = { Text("08:00") },
                                    supportingText = { Text(if (state.quietEndInvalid) "请输入有效时间" else "HH:mm") },
                                    isError = state.quietEndInvalid,
                                    singleLine = true,
                                    enabled = editable,
                                    shape = RoundedCornerShape(14.dp)
                                )
                            }
                            Text(
                                "支持跨午夜。账本和统计页由桌宠提醒，其他页面及后台通过通知提醒。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    SettingsSection("AI 服务", "让每一句生活，都有回应", "✨") {
                        OutlinedTextField(
                            value = state.apiKey,
                            onValueChange = vm::setApiKey,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("自定义 API Key") },
                            supportingText = { Text("留空时使用应用默认配置") },
                            singleLine = true,
                            enabled = editable,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(14.dp)
                        )
                        Text(
                            "保存在当前设备，调用 DeepSeek 时用于身份验证。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    SettingsSection("关于叽里咕噜", "小小的账本，大大的生活", "🌱") {
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
                        Text("账本与对话保存在本机，覆盖安装更新时保留记录。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { showFontLicense = true }) { Text("字体与开源许可") }
                    }
                    TypingSoundSettingsCard(onPreviewWelcome)
                    UpdateSettingsCard()
                    Text("慢慢记，日子也会慢慢发光 ♡", modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showFontLicense) {
        AlertDialog(
            onDismissRequest = { showFontLicense = false },
            title = { Text("字体与开源许可") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Noto Sans SC\nCopyright 2014–2021 Adobe", style = MaterialTheme.typography.bodyMedium)
                    Text("ZCOOL KuaiLe（站酷快乐体）\nCopyright 2018 The ZCOOL KuaiLe Project Authors",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("以上字体使用 SIL Open Font License 1.1。完整版权声明与许可证已随应用内置。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { showFontLicense = false }) { Text("知道啦") } }
        )
    }
}

@Composable
private fun SettingsCompanionHeader() {
    val notes = listOf("把日子过成\n自己喜欢的样子 ♡", "小小的账本，\n也装得下大大的生活。", "今天也要记得\n好好照顾自己呀。")
    var noteIndex by rememberSaveable { mutableStateOf(0) }
    Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 20.dp, end = 10.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("你的专属小角落", style = MaterialTheme.typography.titleLarge.copy(fontFamily = GuluBrandFont, fontWeight = FontWeight.Normal),
                    color = MaterialTheme.colorScheme.primary)
                PaperNote(notes[noteIndex], modifier = Modifier.padding(vertical = 2.dp))
                Text("戳戳阿噜，听句悄悄话", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GuluMascot(modifier = Modifier.size(116.dp), onClick = { noteIndex = (noteIndex + 1) % notes.size })
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    description: String,
    icon: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                Text(icon, style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}
