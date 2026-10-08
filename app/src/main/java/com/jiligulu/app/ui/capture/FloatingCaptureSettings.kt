package com.jiligulu.app.ui.capture

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.data.prefs.AppGlassFrameRate
import com.jiligulu.app.data.prefs.AppGlassPrefs
import com.jiligulu.app.ui.settings.SettingHelpButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart

@Composable
fun FloatingCaptureSettings() {
    val context = LocalContext.current
    val prefs = (context.applicationContext as JiliguluApp).container.userPrefs
    val savedSize by prefs.floatingCaptureSizePercent.collectAsStateWithLifecycle(UserPrefs.DEFAULT_FLOATING_SIZE_PERCENT)
    var sizePercent by remember { mutableFloatStateOf(UserPrefs.DEFAULT_FLOATING_SIZE_PERCENT.toFloat()) }
    var adjustingSize by remember { mutableStateOf(false) }
    LaunchedEffect(savedSize) { if (!adjustingSize) sizePercent = savedSize.toFloat() }
    val enabled by prefs.floatingCaptureEnabled.collectAsStateWithLifecycle(false)
    val hidden by FloatingCaptureService.hiddenForSession.collectAsStateWithLifecycle()
    val running by FloatingCaptureService.running.collectAsStateWithLifecycle()
    val ready by ScreenCaptureService.ready.collectAsStateWithLifecycle()
    val glassPrefs = remember(context.applicationContext) { AppGlassPrefs(context) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    fun prepare() {
        context.startActivity(Intent(context, CapturePermissionActivity::class.java).putExtra("prepareOnly", true))
    }
    fun enable() { scope.launch {
        try {
            FloatingCaptureService.hiddenForSession.value = false
            prefs.setFloatingCaptureEnabled(true)
            ContextCompat.startForegroundService(context, Intent(context, FloatingCaptureService::class.java))
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { error = "已记住开关，暂未能启动，请检查系统权限或稍后重试" }
    } }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(context)) enable() else error = "开启悬浮窗需要允许显示在其他应用上层"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("阿噜悬浮球", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        SettingHelpButton("阿噜悬浮球", "轻点悬浮球截图记账，长按后拖到底部可在本次使用中隐藏；重新启动 App 后恢复。开关、图标大小都会自动保存，默认大小为 80%。\n\n需要允许显示在其他应用上层。基础模式只在点击时截图，本次系统屏幕共享授权可复用；清理后台或系统结束共享后，需要重新授权。共享期间，系统会显示共享标识。")
        Switch(checked = enabled, onCheckedChange = { value ->
            error = null
            if (!value) scope.launch {
                try {
                    prefs.setFloatingCaptureEnabled(false)
                    context.stopService(Intent(context, ScreenCaptureService::class.java))
                    context.stopService(Intent(context, FloatingCaptureService::class.java))
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { error = "开关未能保存，请重试" }
            }
            else if (Settings.canDrawOverlays(context)) enable()
            else runCatching { overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
                .onFailure { error = "请在系统设置中开启悬浮窗权限" }
        })
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("图标大小 · ${sizePercent.roundToInt()}%", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = {
            adjustingSize = false
            sizePercent = UserPrefs.DEFAULT_FLOATING_SIZE_PERCENT.toFloat()
            scope.launch { withContext(NonCancellable) {
                try { prefs.setFloatingCaptureSizePercent(UserPrefs.DEFAULT_FLOATING_SIZE_PERCENT) }
                catch (_: Exception) { error = "大小未能保存，请重试" }
            } }
        }) { Text("恢复默认") }
    }
    Slider(value = sizePercent, valueRange = UserPrefs.MIN_FLOATING_SIZE_PERCENT.toFloat()..UserPrefs.MAX_FLOATING_SIZE_PERCENT.toFloat(),
        steps = 7, onValueChange = { value ->
            val next = (value / 10).roundToInt() * 10
            if (next != sizePercent.roundToInt()) {
                adjustingSize = true; sizePercent = next.toFloat()
                scope.launch { withContext(NonCancellable) {
                    try { prefs.setFloatingCaptureSizePercent(next) }
                    catch (_: Exception) { error = "大小未能保存，请重试" }
                } }
            }
        }, onValueChangeFinished = { adjustingSize = false })
    if (enabled) {
        Text(if (hidden) "本次已隐藏，重新启动 App 后恢复；功能开关仍开启" else if (ready) "截屏已就绪 ♡" else if (running) "悬浮球已开启，下次截图时申请授权" else "已记住开启状态，等待恢复悬浮球", style = MaterialTheme.typography.bodySmall)
        if (!ready && !hidden) TextButton(onClick = { runCatching { prepare() }.onFailure { error = "暂时无法打开授权页面，请重试" } }) { Text("准备截屏") }
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    GlassSamplingSettings(glassPrefs, enabled = Build.VERSION.SDK_INT >= 33)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun GlassSamplingSettings(prefs: AppGlassPrefs, enabled: Boolean) {
    val context = LocalContext.current
    val saved by prefs.frameRate.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("采样速度", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("次/秒", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingHelpButton("采样速度", "设置液态玻璃每秒采样的目标次数，默认 30。它与外观页的屏幕刷新率（Hz）是两个设置；实际采样速度受设备画面与处理速度限制，较高档位会增加耗电，静止时不持续重绘。\n\n仅对 App 内的背景折射起效；App 外保持透明材质，不持续采集屏幕。取样无需屏幕共享授权。拖动时固定透明材质，松手后渐显折射。选项立即保存。")
    }
    Row(Modifier.fillMaxWidth().testTag("app-glass-frame-rate"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AppGlassFrameRate.entries.forEach { rate ->
            val checked = saved == rate
            Surface(onClick = {
                if (checked) return@Surface
                com.jiligulu.app.core.audio.UiSound.select(context)
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    saving = true; error = false
                    try { withContext(NonCancellable) { prefs.setFrameRate(rate) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = true }
                    finally { saving = false }
                }
            }, enabled = enabled && saved != null && !saving,
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("app-glass-rate-${rate.fps}").semantics {
                    role = Role.RadioButton; selected = checked
                }, shape = MaterialTheme.shapes.medium,
                color = if (checked) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)) {
                Text(rate.label, Modifier.padding(vertical = 12.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (error) Text("采样速度没能保存，再点一次试试。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error)
}
