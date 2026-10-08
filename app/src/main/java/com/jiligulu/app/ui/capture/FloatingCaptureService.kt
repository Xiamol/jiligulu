package com.jiligulu.app.ui.capture

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
import kotlinx.coroutines.flow.collect
import kotlin.math.abs
import kotlin.math.roundToInt
import com.jiligulu.app.data.prefs.FloatingCapturePosition
import com.jiligulu.app.data.prefs.UserPrefs

class FloatingCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bubble: ImageView? = null
    private var bubbleLayout: WindowManager.LayoutParams? = null
    private var dismissTarget: TextView? = null
    private var rememberedPosition: FloatingCapturePosition? = null
    private var sizePercent = UserPrefs.DEFAULT_FLOATING_SIZE_PERCENT
    private val touchHandler = Handler(Looper.getMainLooper())
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private val glassHost by lazy { GlassOverlayHost(windows) }
    private val prefs by lazy { (application as com.jiligulu.app.JiliguluApp).container.userPrefs }
    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        val notification = captureNotification(this, "阿噜悬浮记账", "轻点截图 · 长按拖到底部可暂时隐藏", FloatingCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= 34) startForeground(3101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(3101, notification)
        scope.launch {
            try {
                // Read both size and placement before adding a window: never flash at a
                // fixed coordinate before moving to the user's saved place.
                val saved = prefs.readFloatingCapturePlacement()
                sizePercent = saved.sizePercent
                rememberedPosition = saved.position
                attachBubble()
                prefs.floatingCaptureSizePercent.collect { percent ->
                    if (sizePercent != percent) {
                        sizePercent = percent
                        placeRememberedBubble()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                Toast.makeText(this@FloatingCaptureService, "悬浮窗未能开启，请检查悬浮窗权限", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
        }
    }

    private fun attachBubble() {
        val side = iconPixels()
        val bounds = usableBounds()
        val position = rememberedPosition ?: FloatingCaptureGeometry.normalize(bounds.left, maxOf(bounds.top, 300), side, bounds)
        rememberedPosition = position
        val point = FloatingCaptureGeometry.restore(position, side, bounds)
        val params = WindowManager.LayoutParams(side, side, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            x = point.x; y = point.y
        }
        val view = GlassFloatingBubbleView(this).apply {
            contentDescription = "阿噜截图记账，长按后拖到底部关闭区"
            elevation = 0f
            visibility = if (capturing) View.INVISIBLE else View.VISIBLE
        }
        var startX = 0f; var startY = 0f; var x = 0; var y = 0
        var dragStartPosition = position
        var moved = false; var held = false; var overTarget = false
        val targetLocation = IntArray(2)
        val targetBounds = Rect()
        fun overDismissTarget(rawX: Float, rawY: Float): Boolean {
            val target = dismissTarget ?: return false
            if (!held) return false
            target.getLocationOnScreen(targetLocation)
            targetBounds.set(targetLocation[0], targetLocation[1], targetLocation[0] + target.width, targetLocation[1] + target.height)
            return targetBounds.contains(rawX.toInt(), rawY.toInt())
        }
        val hold = Runnable { held = true; showDismissTarget(); view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.setGlassPressed(true)
                    startX = event.rawX; startY = event.rawY; x = params.x; y = params.y
                    dragStartPosition = FloatingCaptureGeometry.normalize(x, y, params.width, usableBounds())
                    moved = false; held = false; overTarget = false
                    touchHandler.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong()); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX; val dy = event.rawY - startY
                    if (abs(dx) + abs(dy) > ViewConfiguration.get(this).scaledTouchSlop) moved = true
                    if (moved) {
                        view.setGlassDragging(true)
                        val point = FloatingCaptureGeometry.clamp(x + dx.toInt(), y + dy.toInt(), params.width, usableBounds())
                        params.x = point.x; params.y = point.y
                        runCatching { glassHost.update(params) }.onSuccess {view.refreshBackdropAfterMove()}
                    }
                    val target = dismissTarget
                    val inside = overDismissTarget(event.rawX, event.rawY)
                    if (inside != overTarget) {
                        overTarget = inside
                        target?.text = if (inside) "♡ 松开就回家\n下次启动再见" else "✕\n拖到这里，暂时隐藏"
                        target?.scaleX = if (inside) 1.08f else 1f; target?.scaleY = if (inside) 1.08f else 1f
                        if (inside) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    view.setGlassPressed(false)
                    view.setGlassDragging(false)
                    val droppedToHide = moved && overDismissTarget(event.rawX, event.rawY)
                    touchHandler.removeCallbacks(hold); hideDismissTarget()
                    if (droppedToHide) {
                        val end = FloatingCaptureGeometry.normalize(params.x, params.y, params.width, usableBounds())
                        hideForSession(FloatingCaptureGeometry.positionAfterDrop(dragStartPosition, end, droppedToHide = true))
                    } else if (moved) {
                        val finalPoint = FloatingCaptureGeometry.clamp(x + (event.rawX - startX).toInt(),
                            y + (event.rawY - startY).toInt(), params.width, usableBounds())
                        params.x = finalPoint.x; params.y = finalPoint.y
                        runCatching { glassHost.update(params) }.onSuccess {view.refreshBackdropAfterMove()}
                        // One write per completed drag; never persist intermediate movement,
                        // the trash target or temporary screenshot invisibility.
                        persistPosition(FloatingCaptureGeometry.normalize(params.x, params.y, params.width, usableBounds()))
                    } else if (!held && !capturing) {
                        capturing = true; glassHost.visible(false)
                        if (!ScreenCaptureService.captureIfReady()) runCatching {
                            startActivity(Intent(this, CapturePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { restore(); Toast.makeText(this, "无法打开截图授权，请回到设置重试", Toast.LENGTH_SHORT).show() }
                    }; true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.setGlassPressed(false)
                    view.setGlassDragging(false)
                    touchHandler.removeCallbacks(hold); hideDismissTarget()
                    rememberedPosition = dragStartPosition
                    placeRememberedBubble()
                    true
                }
                else -> true
            }
        }
        glassHost.attach(view, params)
        if(capturing) glassHost.visible(false)
        bubbleLayout = params; bubble = view; instance = this; running.value = true
    }

    private fun iconPixels() = (60f * sizePercent / 100 * resources.displayMetrics.density).roundToInt()

    private fun usableBounds(): FloatingCaptureBounds {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return FloatingCaptureBounds(insets.left, insets.top,
                (metrics.bounds.width() - insets.right).coerceAtLeast(insets.left),
                (metrics.bounds.height() - insets.bottom).coerceAtLeast(insets.top))
        }
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also { windows.defaultDisplay.getRealMetrics(it) }
        fun dimension(name: String): Int {
            val id = resources.getIdentifier(name, "dimen", "android")
            return if (id == 0) 0 else resources.getDimensionPixelSize(id)
        }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val right = if (landscape) dimension("navigation_bar_width") else 0
        val bottom = if (landscape) 0 else dimension("navigation_bar_height")
        return FloatingCaptureBounds(0, dimension("status_bar_height"),
            (metrics.widthPixels - right).coerceAtLeast(0), (metrics.heightPixels - bottom).coerceAtLeast(0))
    }

    private fun placeRememberedBubble() {
        val view = bubble ?: return
        val params = bubbleLayout ?: return
        val position = rememberedPosition ?: return
        val side = iconPixels()
        val point = FloatingCaptureGeometry.restore(position, side, usableBounds())
        params.width = side; params.height = side; params.x = point.x; params.y = point.y
        runCatching { glassHost.update(params) }.onSuccess {(view as? GlassFloatingBubbleView)?.refreshBackdropAfterMove()}
    }

    private fun persistPosition(position: FloatingCapturePosition, stopAfterSave: Boolean = false) {
        rememberedPosition = position
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // A service teardown right after the finger lifts cannot cancel its final edit.
            withContext(NonCancellable) {
                try { prefs.setFloatingCapturePosition(position) }
                catch (failure: Exception) { android.util.Log.w("FloatingCapture", "Unable to save icon position", failure) }
            }
            if (stopAfterSave) stopSelf()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        touchHandler.removeCallbacksAndMessages(null)
        hideDismissTarget()
        (bubble as? GlassFloatingBubbleView)?.apply { setGlassPressed(false); setGlassDragging(false) }
        placeRememberedBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { if (intent?.action == "stop") hideForSession(); return START_NOT_STICKY }
    private fun hideForSession(position: FloatingCapturePosition? = rememberedPosition) {
        hiddenForSession.value = true
        glassHost.visible(false)
        if (position != null) persistPosition(position, stopAfterSave = true) else stopSelf()
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
        runCatching { glassHost.detach() }
        bubble = null; bubbleLayout = null; instance = null; running.value = false
        super.onDestroy()
    }
    companion object {
        val running = MutableStateFlow(false)
        val hiddenForSession = MutableStateFlow(false)
        private var instance: FloatingCaptureService? = null
        private var capturing = false
        fun restore() { capturing = false; instance?.glassHost?.visible(true) }
    }
}

internal fun captureNotification(context: Context, title: String, text: String, service: Class<*>): Notification {
    // Foreground services require LOW or higher: MIN can cause an additional system
    // app-running notice. This dedicated LOW channel is silent and never heads-up.
    val channel = CaptureNotificationChannels.ensure(context, service == FloatingCaptureService::class.java)
    val open = PendingIntent.getActivity(context, 3100, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val stop = PendingIntent.getService(context, 3101, Intent(context, service).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val builder = Notification.Builder(context, channel).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title).setContentText(text)
        .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
        .setCategory(Notification.CATEGORY_SERVICE).setPriority(Notification.PRIORITY_LOW)
        .setSound(null).setVibrate(longArrayOf()).setDefaults(0)
        .addAction(Notification.Action.Builder(null, "关闭", stop).build())
    if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
    return builder.build()
}
