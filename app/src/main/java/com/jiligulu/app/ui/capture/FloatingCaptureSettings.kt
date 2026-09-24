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
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
fun FloatingCaptureSettings() {
    val context = LocalContext.current
    val prefs = (context.applicationContext as JiliguluApp).container.userPrefs
    val enabled by prefs.floatingCaptureEnabled.collectAsStateWithLifecycle(false)
    val running by FloatingCaptureService.running.collectAsStateWithLifecycle()
    val ready by ScreenCaptureService.ready.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    fun prepare() {
        context.startActivity(Intent(context, CapturePermissionActivity::class.java).putExtra("prepareOnly", true))
    }
    fun enable() { scope.launch {
        try {
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
        Column(Modifier.weight(1f)) { Text("阿噜悬浮球"); Text("轻点截图、拖动挪位置、长按关闭", style = MaterialTheme.typography.bodySmall) }
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
    Text("开关会记住。截屏授权在本次共享会话内复用，系统会显示共享标识；只在点击时截取画面。清理后台或系统结束共享后，需要重新授权。", style = MaterialTheme.typography.bodySmall)
    if (enabled) {
        Text(if (ready) "截屏已就绪 ♡" else if (running) "悬浮球已开启，下次截图时申请授权" else "已记住开启状态，等待恢复悬浮球", style = MaterialTheme.typography.bodySmall)
        if (!ready) TextButton(onClick = { runCatching { prepare() }.onFailure { error = "暂时无法打开授权页面，请重试" } }) { Text("准备截屏") }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
