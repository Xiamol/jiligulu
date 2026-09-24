package com.jiligulu.app.ui.capture

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.TextView
import android.graphics.Rect
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.widget.Toast
import com.jiligulu.app.MainActivity
import com.jiligulu.app.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt
import com.jiligulu.app.data.prefs.UserPrefs

class FloatingCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bubble: ImageView? = null
    private var dismissTarget: TextView? = null
    private val touchHandler = Handler(Looper.getMainLooper())
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        val notification = captureNotification(this, "阿噜悬浮记账", "轻点截图 · 长按拖到底部可暂时隐藏", FloatingCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= 34) startForeground(3101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(3101, notification)
        val side = (60f * UserPrefs.DEFAULT_FLOATING_SIZE_PERCENT / 100 * resources.displayMetrics.density).roundToInt()
        val params = WindowManager.LayoutParams(side, side, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 300 }
        val view = ImageView(this).apply { visibility = View.INVISIBLE; setImageResource(R.mipmap.ic_launcher); contentDescription = "阿噜截图记账，长按后拖到底部关闭区"; elevation = 8f }
        var startX = 0f; var startY = 0f; var x = 0; var y = 0
        var moved = false; var held = false; var overTarget = false
        val hold = Runnable { held = true; showDismissTarget(); view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY; x = params.x; y = params.y
                    moved = false; held = false; overTarget = false
                    touchHandler.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong()); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX; val dy = event.rawY - startY
                    if (abs(dx) + abs(dy) > ViewConfiguration.get(this).scaledTouchSlop) moved = true
                    if (moved) {
                        params.x = (x + dx.toInt()).coerceIn(0, (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
                        params.y = (y + dy.toInt()).coerceIn(0, (resources.displayMetrics.heightPixels - params.height).coerceAtLeast(0))
                        windows.updateViewLayout(view, params)
                    }
                    val target = dismissTarget
                    val inside = if (held && target != null) {
                        val location = IntArray(2); target.getLocationOnScreen(location)
                        Rect(location[0], location[1], location[0] + target.width, location[1] + target.height).contains(event.rawX.toInt(), event.rawY.toInt())
                    } else false
                    if (inside != overTarget) {
                        overTarget = inside
                        target?.text = if (inside) "♡ 松开就回家\n下次启动再见" else "✕\n拖到这里，暂时隐藏"
                        target?.scaleX = if (inside) 1.08f else 1f; target?.scaleY = if (inside) 1.08f else 1f
                        if (inside) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    touchHandler.removeCallbacks(hold); hideDismissTarget()
                    if (held && overTarget) hideForSession()
                    else if (!moved && !held && !capturing) {
                        capturing = true; view.visibility = View.INVISIBLE
                        if (!ScreenCaptureService.captureIfReady()) runCatching {
                            startActivity(Intent(this, CapturePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { restore(); Toast.makeText(this, "无法打开截图授权，请回到设置重试", Toast.LENGTH_SHORT).show() }
                    }; true
                }
                MotionEvent.ACTION_CANCEL -> { touchHandler.removeCallbacks(hold); hideDismissTarget(); true }
                else -> true
            }
        }
        try {
            windows.addView(view, params); bubble = view; instance = this; running.value = true
            scope.launch {
                (application as com.jiligulu.app.JiliguluApp).container.userPrefs.floatingCaptureSizePercent.collect { percent ->
                    val pixels = (60f * percent / 100 * resources.displayMetrics.density).roundToInt()
                    params.width = pixels; params.height = pixels
                    params.x = params.x.coerceIn(0, (resources.displayMetrics.widthPixels - pixels).coerceAtLeast(0))
                    params.y = params.y.coerceIn(0, (resources.displayMetrics.heightPixels - pixels).coerceAtLeast(0))
                    runCatching { windows.updateViewLayout(view, params) }
                    if (!capturing) view.visibility = View.VISIBLE
                }
            }
        }
        catch (_: Exception) { Toast.makeText(this, "悬浮窗未能开启，请检查悬浮窗权限", Toast.LENGTH_SHORT).show(); stopSelf() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { if (intent?.action == "stop") hideForSession(); return START_NOT_STICKY }
    private fun hideForSession() {
        hiddenForSession.value = true
        Toast.makeText(this, "阿噜暂时回家了，下次启动再见 ♡", Toast.LENGTH_SHORT).show()
        stopSelf()
    }
    private fun showDismissTarget() {
        if (dismissTarget != null) return
        val density = resources.displayMetrics.density
        val target = TextView(this).apply {
            text = "✕\n拖到这里，暂时隐藏"; textSize = 17f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(91, 68, 143))
            background = GradientDrawable().apply { setColor(Color.rgb(244, 234, 250)); cornerRadius = 38 * density; setStroke((2 * density).toInt(), Color.rgb(190, 159, 224)) }
        }
        val layout = WindowManager.LayoutParams((200 * density).toInt(), (104 * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = (34 * density).toInt()
            }
        runCatching { windows.addView(target, layout); dismissTarget = target }
    }
    private fun hideDismissTarget() { dismissTarget?.let { runCatching { windows.removeView(it) } }; dismissTarget = null }
    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        scope.cancel(); touchHandler.removeCallbacksAndMessages(null); hideDismissTarget()
        stopService(Intent(this, ScreenCaptureService::class.java))
        capturing = false
        bubble?.let { runCatching { windows.removeView(it) } }; bubble = null; instance = null; running.value = false; super.onDestroy() }
    companion object {
        val running = MutableStateFlow(false)
        val hiddenForSession = MutableStateFlow(false)
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
