package com.jiligulu.app.ui.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** The legacy ID stays with the bubble so a user-blocked category is never recreated. */
internal object CaptureNotificationChannels {
    const val FLOATING = "screen-capture-silent-low"
    const val SCREENSHOT = "screen-capture-on-demand"
    fun ensure(context: Context, floating: Boolean): String {
        val id = if (floating) FLOATING else SCREENSHOT
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(id, if (floating) "阿噜悬浮记账" else "主动截屏", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
                description = if (floating) "仅管理悬浮球的常驻通知，可单独关闭" else "手动截图的屏幕共享服务通知"
                if (Build.VERSION.SDK_INT >= 33) setBlockable(true)
            })
        return id
    }
    fun floatingShown(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(FLOATING)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun floatingSettings(context: Context): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, FLOATING)
}
