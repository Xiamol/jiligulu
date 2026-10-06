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
import androidx.compose.ui.Alignment
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

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
    val globalDesired by prefs.globalGlassRefractionEnabled.collectAsStateWithLifecycle(false)
    val globalStatus by GlobalGlassBackdrop.state.collectAsStateWithLifecycle()
    var explainGlobal by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    fun prepare() {
        context.startActivity(Intent(context, CapturePermissionActivity::class.java).putExtra("prepareOnly", true))
    }
    fun authorizeGlobal() { scope.launch {
        try {
            prefs.setGlobalGlassRefractionEnabled(true)
            if (!ScreenCaptureService.enableGlobalIfReady()) context.startActivity(
                Intent(context, CapturePermissionActivity::class.java).putExtra("prepareOnly", true)
                    .putExtra(ScreenCaptureService.EXTRA_GLOBAL_GLASS, true))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "未能启动共享授权，点击重新授权后再试。" }
    } }
    fun enable() { scope.launch {
        try {
            FloatingCaptureService.hiddenForSession.value = false
            prefs.setFloatingCaptureEnabled(true)
            ContextCompat.startForegroundService(context, Intent(context, FloatingCaptureService::class.java))
            if (!ScreenCaptureService.ready.value) prepare()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { error = "已记住开关，暂未能启动，请检查系统权限或稍后重试" }
    } }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(context)) enable() else error = "开启悬浮窗需要允许显示在其他应用上层"
    }
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) { Text("阿噜悬浮球"); Text("轻点截图；长按拖到底部可暂时隐藏", style = MaterialTheme.typography.bodySmall) }
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("小一点", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("大一点", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text("默认 80%，调整即时生效并自动保存。", style = MaterialTheme.typography.bodySmall)
    Text("开关会记住。默认只在点击时截图，本次共享授权可复用；清理后台或系统结束共享后，需要重新授权。系统会显示共享标识。", style = MaterialTheme.typography.bodySmall)
    if (enabled) {
        Text(if (hidden) "本次已隐藏，重新启动 App 后恢复；功能开关仍开启" else if (ready) "截屏已就绪 ♡" else if (running) "悬浮球已开启，下次截图时申请授权" else "已记住开启状态，等待恢复悬浮球", style = MaterialTheme.typography.bodySmall)
        if (!ready && !hidden) TextButton(onClick = { runCatching { prepare() }.onFailure { error = "暂时无法打开授权页面，请重试" } }) { Text("准备截屏") }
    }
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("全局边缘折射 · 可选")
            Text("默认关闭；需要本次系统屏幕共享授权", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = globalDesired, enabled = Build.VERSION.SDK_INT >= 33 && (globalDesired || enabled && running && !hidden),
            onCheckedChange = { value ->
                com.jiligulu.app.core.audio.UiSound.toggle(context)
                if (value) explainGlobal = true else {
                    ScreenCaptureService.stopGlobalSampling()
                    scope.launch {
                        try { prefs.setGlobalGlassRefractionEnabled(false) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "采样已停止，但开关未保存，请重试。" }
                    }
                }
            })
    }
    Text("授权后最高每秒 12 次读取浮球外侧的小片画面，只用于本机实时边缘折射；不保存、上传或识别。中心仍用系统模糊，应用内继续使用本应用背景。",
        style = MaterialTheme.typography.bodySmall)
    Text(when {
        Build.VERSION.SDK_INT < 33 -> "这项折射需要 Android 13 或更新版本；基础悬浮球和系统模糊不受影响。"
        !enabled || !running || hidden -> "先开启并显示阿噜悬浮球。"
        !globalDesired -> "当前为基础玻璃：不持续采样屏幕。"
        !globalStatus.authorizedThisSession -> "已记住开启意愿，本次尚未授权采样；重启后不会自动录取屏幕。"
        else -> globalStatus.detail
    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (globalDesired && enabled && running && !hidden && Build.VERSION.SDK_INT >= 33) {
        if (!globalStatus.authorizedThisSession) TextButton(onClick = { explainGlobal = true }) {
            Text(if (ready) "在本次共享中启用" else "重新授权并启用")
        }
        if (ready) TextButton(onClick = {
            ScreenCaptureService.stopGlobalSampling()
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }) { Text("停止本次共享") }
    }
    if (explainGlobal) AlertDialog(onDismissRequest = { explainGlobal = false },
        title = { Text("开启实时边缘折射？") },
        text = { Text("需要共享整个屏幕，系统共享标志和可关闭的静默通知会持续显示。\n\n只读取浮球外侧小片区域，最高 12 帧/秒，画面仅在内存中使用，不保存、上传或识别。浮球遮挡的中心保持系统模糊。\n\n拖动、锁屏、屏幕关闭或系统结束共享时暂停或停止；下次启动需要重新授权。") },
        confirmButton = { TextButton(onClick = { explainGlobal = false; authorizeGlobal() }) { Text(if (ready) "启用本次共享" else "前往系统授权") } },
        dismissButton = { TextButton(onClick = { explainGlobal = false }) { Text("先不开启") } })
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
