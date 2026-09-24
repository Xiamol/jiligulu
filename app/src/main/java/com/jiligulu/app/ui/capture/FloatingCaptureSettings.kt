package com.jiligulu.app.ui.capture

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    Text("开关会记住。截屏授权在本次共享会话内复用，系统会显示共享标识；只在点击时截取画面。清理后台或系统结束共享后，需要重新授权。", style = MaterialTheme.typography.bodySmall)
    if (enabled) {
        Text(if (hidden) "本次已隐藏，重新启动 App 后恢复；功能开关仍开启" else if (ready) "截屏已就绪 ♡" else if (running) "悬浮球已开启，下次截图时申请授权" else "已记住开启状态，等待恢复悬浮球", style = MaterialTheme.typography.bodySmall)
        if (!ready && !hidden) TextButton(onClick = { runCatching { prepare() }.onFailure { error = "暂时无法打开授权页面，请重试" } }) { Text("准备截屏") }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
