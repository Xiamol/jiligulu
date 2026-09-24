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

@Composable
fun FloatingCaptureSettings() {
    val context = LocalContext.current
    val running by FloatingCaptureService.running.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    fun start() {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, FloatingCaptureService::class.java)) }
            .onFailure { error = "悬浮窗暂时无法启动，请检查系统权限" }
    }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(context)) start() else error = "开启悬浮窗需要允许显示在其他应用上层"
    }
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) { Text("阿噜悬浮球"); Text("轻点截图、拖动挪位置、长按关闭", style = MaterialTheme.typography.bodySmall) }
        Switch(checked = running, onCheckedChange = { enabled ->
            error = null
            if (!enabled) context.stopService(Intent(context, FloatingCaptureService::class.java))
            else if (Settings.canDrawOverlays(context)) start()
            else runCatching { overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
                .onFailure { error = "请在系统设置中开启悬浮窗权限" }
        })
    }
    Text("截图前由系统询问授权；截图后先预览，再由你决定是否识别。", style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
