package com.jiligulu.app.ui.capture

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import com.jiligulu.app.MainActivity
import com.jiligulu.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

class FloatingCaptureService : Service() {
    private var bubble: ImageView? = null
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        val notification = captureNotification(this, "阿噜悬浮记账", "轻点截图 · 拖动挪位置 · 长按关闭", FloatingCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= 34) startForeground(3101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(3101, notification)
        val side = (60 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(side, side, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 300 }
        val view = ImageView(this).apply { setImageResource(R.mipmap.ic_launcher); contentDescription = "阿噜截图记账，长按关闭"; elevation = 8f }
        var startX = 0f; var startY = 0f; var x = 0; var y = 0; var moved = false; var downAt = 0L
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; x = params.x; y = params.y; moved = false; downAt = SystemClock.elapsedRealtime(); true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX; val dy = event.rawY - startY
                    if (abs(dx) + abs(dy) > ViewConfiguration.get(this).scaledTouchSlop) moved = true
                    if (moved) { params.x = (x + dx.toInt()).coerceIn(0, (resources.displayMetrics.widthPixels - side).coerceAtLeast(0)); params.y = (y + dy.toInt()).coerceIn(0, (resources.displayMetrics.heightPixels - side).coerceAtLeast(0)); windows.updateViewLayout(view, params) }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (SystemClock.elapsedRealtime() - downAt > 650) stopSelf()
                        else if (!capturing) {
                            capturing = true; view.visibility = View.INVISIBLE
                            runCatching { startActivity(Intent(this, CapturePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                .onFailure { restore(); Toast.makeText(this, "无法打开截图授权，请回到设置重试", Toast.LENGTH_SHORT).show() }
                        }
                    }; true
                }
                else -> true
            }
        }
        try { windows.addView(view, params); bubble = view; instance = this; running.value = true }
        catch (_: Exception) { Toast.makeText(this, "悬浮窗未能开启，请检查悬浮窗权限", Toast.LENGTH_SHORT).show(); stopSelf() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { if (intent?.action == "stop") stopSelf(); return START_NOT_STICKY }
    override fun onDestroy() { bubble?.let { runCatching { windows.removeView(it) } }; bubble = null; instance = null; running.value = false; super.onDestroy() }
    companion object {
        val running = MutableStateFlow(false)
        private var instance: FloatingCaptureService? = null
        private var capturing = false
        fun restore() { capturing = false; instance?.bubble?.visibility = View.VISIBLE }
    }
}

internal fun captureNotification(context: Context, title: String, text: String, service: Class<*>): Notification {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("screen-capture", "悬浮记账", NotificationManager.IMPORTANCE_LOW))
    val open = PendingIntent.getActivity(context, 3100, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val stop = PendingIntent.getService(context, 3101, Intent(context, service).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    return Notification.Builder(context, "screen-capture").setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title).setContentText(text).setContentIntent(open).setOngoing(true)
        .addAction(Notification.Action.Builder(null, "关闭", stop).build()).build()
}
