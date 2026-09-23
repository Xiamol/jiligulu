package com.jiligulu.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jiligulu.app.data.reminder.WaterReminderScheduler

@Composable
fun ReminderPermissionHint() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var exact by remember { mutableStateOf(WaterReminderScheduler.exactAllowed(context)) }
    var notifications by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exact = WaterReminderScheduler.exactAllowed(context)
                notifications = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                    (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (!exact) {
        Text("还没开启「闹钟和提醒」权限。目前仍会安排提醒，但可能延迟。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            try { context.startActivity(WaterReminderScheduler.exactPermissionIntent(context)) }
            catch (_: Exception) { error = "请在系统设置中找到叽里咕噜，开启「闹钟和提醒」。" }
        }) { Text("开启准时提醒") }
    }
    if (!notifications) {
        Text("通知权限还没开启，后台和设置页的喝水提醒可能看不到。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            try {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            } catch (_: Exception) { error = "请到系统设置中开启叽里咕噜的通知权限。" }
        }) { Text("打开通知设置") }
    }
    Text("息屏省电和后台限制可能延迟提醒。可在手机系统设置中允许自启动与后台运行；强行停止应用后，需要重新打开才能恢复。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (Build.MANUFACTURER.equals("vivo", ignoreCase = true) || Build.BRAND.equals("iQOO", ignoreCase = true)) {
        Text("vivo / iQOO：系统设置中搜索「后台高耗电」或「后台耗电管理」，允许叽里咕噜后台运行；同时开启自启动。仅开启通知和准时提醒权限仍可能被系统冻结。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    TextButton(onClick = {
        try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
        catch (_: Exception) { error = "请在系统设置中找到叽里咕噜的应用信息。" }
    }) { Text("打开系统应用设置") }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}
