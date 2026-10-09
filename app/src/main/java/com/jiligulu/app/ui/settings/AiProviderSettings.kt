package com.jiligulu.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.ai.AiConfig
import com.jiligulu.app.core.ai.AiEndpointKind
import com.jiligulu.app.core.ai.AiProviderId
import com.jiligulu.app.core.ai.AiProviderProfile
import com.jiligulu.app.data.prefs.AiProviderState
import com.jiligulu.app.ui.components.GuluDialog
import com.jiligulu.app.ui.components.uiTap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Compact selection rows; the only editable credential fields live inside an explicit editor. */
@Composable
fun AiProviderSettings() {
    val app = LocalContext.current.applicationContext as? JiliguluApp ?: return
    val prefs = app.container.aiProviders
    val state by prefs.state.collectAsStateWithLifecycle(initialValue = AiProviderState())
    val scope = rememberCoroutineScope()
    val pageActive = LocalSettingPageActive.current
    var editor by remember { mutableStateOf<AiProviderId?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var help by remember { mutableStateOf(false) }
    LaunchedEffect(prefs) { prefs.migrateLegacyDeepSeekKey(app.container.userPrefs.apiKeyOverride.first()) }
    LaunchedEffect(pageActive) { if (!pageActive) { editor = null; help = false } }
    fun persist(action: suspend () -> Unit) { scope.launch {
        try { action(); error = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "未能保存选择，请重试" }
    } }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(state.selected == AiProviderId.DEEPSEEK,
            onClick = uiTap { persist { prefs.select(AiProviderId.DEEPSEEK) } }, label = { Text("DeepSeek") },
            modifier = Modifier.testTag("ai-provider-deepseek"))
        FilterChip(state.selected == AiProviderId.CUSTOM,
            onClick = uiTap {
                if (state.custom.address.isBlank() || state.custom.model.isBlank()) editor = AiProviderId.CUSTOM
                else persist { prefs.select(AiProviderId.CUSTOM) }
            },
            label = { Text("自定义") }, modifier = Modifier.testTag("ai-provider-custom"))
        TextButton(onClick = uiTap { help = true }) { Text("说明") }
    }
    val selected = state.selectedProfile
    Row(Modifier.fillMaxWidth().clickable { editor = state.selected }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(selected.name, style = MaterialTheme.typography.bodyMedium)
            Text(selected.model.ifBlank { "填写模型与接口" }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val hasKey = if (state.selected == AiProviderId.DEEPSEEK) state.hasDeepSeekKey else state.hasCustomKey
            val usingDefault = state.selected == AiProviderId.DEEPSEEK && !state.hasDeepSeekKey
            Text(if (usingDefault) "默认 DS API 已启用" else if (hasKey) "密钥已设置" else "未填密钥 · 免鉴权服务可留空",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = uiTap { editor = state.selected }, modifier = Modifier.testTag("ai-provider-edit")) { Text("编辑") }
    }
    if (state.selected == AiProviderId.DEEPSEEK) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(state.deepSeekModel == AiConfig.MODEL, onClick = uiTap { persist { prefs.setDeepSeekModel(AiConfig.MODEL) } }, label = { Text("Flash · 可识图") })
        FilterChip(state.deepSeekModel == AiConfig.DEEPSEEK_PRO_MODEL,
            onClick = uiTap { persist { prefs.setDeepSeekModel(AiConfig.DEEPSEEK_PRO_MODEL) } }, label = { Text("Pro · 文字") })
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    editor?.let { id ->
        AiProviderEditor(id, if (id == AiProviderId.CUSTOM) state.custom else AiProviderProfile.deepSeek(state.deepSeekModel),
            loadKey = { prefs.savedKey(id) }, onDismiss = { editor = null }, onSave = { profile, key ->
                if (id == AiProviderId.DEEPSEEK) prefs.saveDeepSeekKey(key) else {
                    prefs.saveCustom(profile, key)
                    prefs.select(AiProviderId.CUSTOM)
                }
            })
    }
    if (help) GuluDialog("AI 服务说明", onDismiss = { help = false }, compact = true, dense = true) {
        Text("聊天、记账、分类建议与图片识别使用当前供应商；每次请求固定地址、模型和独立密钥，切换不会把 DeepSeek 密钥带给其它服务。选择与模型切换即时保存；编辑窗口点击保存后生效。")
        Text("自定义仅支持 OpenAI Chat Completions 兼容接口。基础地址会补 /chat/completions，空路径会补 /v1；完整接口按填写地址使用。HTTP 不加密，云端建议 HTTPS，HTTP 可用于你信任的本地服务。")
        Text("不同模型支持的参数与识图能力不同。自定义默认只按提示要求 JSON，不发送 DeepSeek thinking、temperature 或 token 上限；确认服务支持后，可启用 JSON 模式、temperature 和图像输入。")
        Text("密钥仅用于当前设备请求鉴权，不会共享给其它供应商；免鉴权服务可留空。")
        Text("DeepSeek 留空使用默认 DS 服务，经安全转发请求官方接口，共享密钥不在安装包里。填写自己的密钥后直接连接官方；自定义供应商保持自己的地址和配置。")
    }
}

@Composable
private fun AiProviderEditor(id: AiProviderId, initial: AiProviderProfile, loadKey: suspend () -> String,
    onDismiss: () -> Unit, onSave: suspend (AiProviderProfile, String) -> Unit) {
    var profile by remember(id) { mutableStateOf(initial) }
    // Credentials never enter rememberSaveable/SavedStateHandle or a public configuration DTO.
    var key by remember(id) { mutableStateOf("") }
    var reveal by remember(id) { mutableStateOf(false) }
    var loading by remember(id) { mutableStateOf(true) }
    var saving by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) {
        try { key = loadKey() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "未能读取设置，请关闭后重试" }
        finally { loading = false }
    }
    GuluDialog(if (id == AiProviderId.DEEPSEEK) "DeepSeek 密钥" else "编辑自定义供应商",
        onDismiss = onDismiss, confirmLabel = "保存", dismissLabel = "取消", compact = true, compactWidth = 352.dp,
        dense = true, busy = saving, confirmEnabled = !loading, onConfirm = {
            scope.launch {
                saving = true; error = null
                try { onSave(profile, key); onDismiss() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "未能保存，请重试" }
                finally { saving = false }
            }
        }) {
        if (id == AiProviderId.CUSTOM) {
            OutlinedTextField(profile.name, { profile = profile.copy(name = it) }, label = { Text("名称") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("ai-custom-name"))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(profile.endpointKind == AiEndpointKind.BASE_URL, onClick = { profile = profile.copy(endpointKind = AiEndpointKind.BASE_URL) }, label = { Text("基础地址") })
                FilterChip(profile.endpointKind == AiEndpointKind.CHAT_ENDPOINT, onClick = { profile = profile.copy(endpointKind = AiEndpointKind.CHAT_ENDPOINT) }, label = { Text("完整接口") })
            }
            OutlinedTextField(profile.address, { profile = profile.copy(address = it) }, label = { Text("地址") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag("ai-custom-endpoint"))
            OutlinedTextField(profile.model, { profile = profile.copy(model = it) }, label = { Text("模型 ID") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("ai-custom-model"))
        }
        OutlinedTextField(key, { key = it }, label = { Text("API Key") }, singleLine = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "隐藏" else "显示") } },
            modifier = Modifier.fillMaxWidth().testTag("ai-provider-key"))
        if (id == AiProviderId.DEEPSEEK) Text("留空使用默认 DS 服务；个人密钥不带 Bearer。", style = MaterialTheme.typography.bodySmall)
        else {
            CapabilityRow("模型支持图像输入", profile.supportsImages) { profile = profile.copy(supportsImages = it) }
            CapabilityRow("支持 JSON object 响应格式", profile.jsonMode) { profile = profile.copy(jsonMode = it) }
            CapabilityRow("支持 temperature 参数", profile.sendsTemperature) { profile = profile.copy(sendsTemperature = it) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun CapabilityRow(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChanged(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = onChanged)
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}
