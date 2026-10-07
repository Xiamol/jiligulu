package com.jiligulu.app.ui.capture

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

/** One explicit consent -> exactly one virtual display; screenshot and opt-in ring sampling share it. */
class ScreenCaptureService : Service() {
    private enum class ReaderMode { NONE, SCREENSHOT, GLASS }
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val workerThread = HandlerThread("GuluGlassFrames")
    private lateinit var worker: Handler
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null
    @Volatile private var generation = 0
    @Volatile private var closed = false
    @Volatile private var capturing = false
    @Volatile private var awaitingGlassFrame = false
    @Volatile private var processingFrame = false
    @Volatile private var ringRequest: GlassSampleRegion? = null
    @Volatile private var requestedAtNanos = 0L
    private var readerMode = ReaderMode.NONE
    private var started = false
    private var globalRequested = false // NEVER initialized from the saved preference
    private var projectionVisible = true
    private var width = 0
    private var height = 0
    private var readerWidth = 0
    private var readerHeight = 0
    private var timeout: Runnable? = null
    private var screenshotStart: Runnable? = null
    private var ringPixels = IntArray(0) // worker-thread buffer; one frame in flight
    private var lastNotification = ""
    private var receiverRegistered = false
    private var requestAt = 0L
    private var processedFrames = 0
    private var processingNanos = 0L
    private val glassTick = Runnable { sampleGlass() }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                cancelPendingScreenshot()
                pauseGlass("屏幕已关闭，采样暂停", release = true)
            }
            else refreshEnvironment()
        }
    }
    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        workerThread.start(); worker = Handler(workerThread.looper)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { shutdownSampling(); stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        started = true
        try {
            val notification = captureNotification(this, "阿噜截屏已就绪", "仅点击时截图 · 关闭结束本次共享授权", ScreenCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= 29) startForeground(3102, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(3102, notification)
            val grant = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("grant", Intent::class.java)
                else @Suppress("DEPRECATION") (intent?.getParcelableExtra("grant") as? Intent)
            require(grant != null)
            val metrics = resources.displayMetrics
            val bounds = if (Build.VERSION.SDK_INT >= 30) getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds else null
            width = bounds?.width() ?: metrics.widthPixels; height = bounds?.height() ?: metrics.heightPixels
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent!!.getIntExtra("code", 0), grant)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { if (!closed) { shutdownSampling(); stopSelf() } }
                override fun onCapturedContentResize(newWidth: Int, newHeight: Int) {
                    if (closed || newWidth <= 0 || newHeight <= 0 || width == newWidth && height == newHeight) return
                    val screenshotWasAttached = readerMode == ReaderMode.SCREENSHOT
                    width = newWidth; height = newHeight
                    GlobalGlassBackdrop.clearFrame()
                    detachReader()
                    runCatching {
                        display?.resize(width, height, resources.displayMetrics.densityDpi)
                        // A resize while the consent sheet is dismissing must not bypass the
                        // scheduled screenshot delay and capture the authorization UI itself.
                        if (capturing && screenshotWasAttached) attachScreenshotReader()
                        else if (!capturing) refreshEnvironment()
                    }.onFailure { endWithError("截屏尺寸已变化，请重新授权") }
                }
                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    projectionVisible = isVisible
                    if (!isVisible) pauseGlass("共享画面不可见，采样暂停", release = true) else refreshEnvironment()
                }
            }, handler)
            // Android 14 prohibits another createVirtualDisplay on this MediaProjection token.
            display = projection!!.createVirtualDisplay("Gulu user-approved capture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, handler)
            instance = this; mutableReady.value = true
            scope.launch {
                (application as JiliguluApp).container.userPrefs.globalGlassRefractionEnabled.collect { desired ->
                    if (!desired && globalRequested) setGlobalRequested(false)
                }
            }
            if (intent.getBooleanExtra(EXTRA_GLOBAL_GLASS, false)) {
                setGlobalRequested(true); FloatingCaptureService.restore()
            } else if (intent.getBooleanExtra("prepareOnly", false)) FloatingCaptureService.restore()
            else capture(delayMs = 750)
        } catch (_: Exception) { endWithError("截屏授权已失效，请重新点击阿噜") }
        return START_NOT_STICKY
    }

    private fun screenUsable(): Boolean = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun fullDisplayShared(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return true
        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return width == bounds.width() && height == bounds.height()
    }

    private fun setGlobalRequested(enabled: Boolean): Boolean {
        if (closed || projection == null || display == null || enabled && Build.VERSION.SDK_INT < 33) return false
        globalRequested = enabled
        if (enabled) {
            GlobalGlassBackdrop.session(true, GlobalGlassPhase.STARTING, "本次共享已授权，等待浮球稳定")
            refreshEnvironment()
        } else {
            handler.removeCallbacks(glassTick)
            if (!capturing) detachReader()
            GlobalGlassBackdrop.session(false, GlobalGlassPhase.OFF, "全局边缘折射已停止")
            GlobalGlassBackdrop.release()
            updateNotification("阿噜截屏已就绪", "全局折射已关闭 · 仅点击时截图 · 关闭可结束共享")
        }
        return true
    }

    private fun refreshEnvironment() {
        handler.removeCallbacks(glassTick)
        if (!closed && globalRequested) handler.post(glassTick)
    }
    private fun nextGlass(delay: Long = GlobalGlassSampling.FRAME_INTERVAL_MS) {
        handler.removeCallbacks(glassTick)
        if (!closed && globalRequested) handler.postDelayed(glassTick, delay)
    }
    private fun pauseGlass(reason: String, release: Boolean = false) {
        handler.removeCallbacks(glassTick)
        awaitingGlassFrame = false; ringRequest = null
        if (!capturing) {
            runCatching { display?.surface = null }
            // Invalidate queued callbacks as well as detaching the surface: a late pre-lock/
            // pre-drag frame must never repopulate a cleared backdrop after a pause.
            if (reader != null) detachReader()
        }
        if (globalRequested) {
            GlobalGlassBackdrop.session(true, GlobalGlassPhase.PAUSED, reason)
            updateNotification("阿噜全局折射已暂停", "$reason · 关闭可结束共享")
        } else GlobalGlassBackdrop.clearFrame()
        if (release) GlobalGlassBackdrop.release()
    }

    private fun sampleGlass() {
        if (closed || !globalRequested || Build.VERSION.SDK_INT < 33) return
        if (GlobalGlassBackdrop.rendererUnavailable) { pauseGlass("设备当前无法使用折射，保留基础系统模糊", release = true); return }
        if (capturing) { pauseGlass("正在处理你主动拍下的截图"); return }
        if (!screenUsable()) { pauseGlass("屏幕锁定或关闭，采样暂停", release = true); return }
        if (!projectionVisible) { pauseGlass("共享画面不可见，采样暂停", release = true); return }
        if (!fullDisplayShared()) { pauseGlass("请选择共享整个屏幕后再启用", release = true); return }
        // Activity/dialog resume/pause already calls refreshEnvironment. Polling every
        // 250 ms here needlessly woke the main thread even while the app was motionless.
        if (AppGlassBackdrop.available()) { pauseGlass("应用内使用本应用背景，整屏采样暂停"); return }
        val target = GlobalGlassBackdrop.settledTarget()
        if (target == null) { pauseGlass("浮球移动或隐藏，稳定后继续"); nextGlass(150); return }
        if (awaitingGlassFrame) {
            if (SystemClock.uptimeMillis() - requestAt > 1_500) {
                pauseGlass("尚未收到可采样画面"); nextGlass(250)
            } else nextGlass()
            return
        }
        runCatching {
            val (w, h) = GlobalGlassSampling.captureSize(width, height)
            ensureReader(ReaderMode.GLASS, w, h)
            ringRequest = GlobalGlassSampling.region(width, height, w, h, target)
            if (ringRequest == null) { pauseGlass("浮球已离开可采样区域"); nextGlass(250); return }
            requestedAtNanos = System.nanoTime(); requestAt = SystemClock.uptimeMillis(); awaitingGlassFrame = true
            display!!.surface = reader!!.surface
            updateNotification("阿噜全局边缘折射运行中", "最高12帧/秒 · 仅内存、不保存上传 · 关闭结束共享")
            nextGlass()
        }.onFailure { pauseGlass("采样暂不可用，请停止共享后重新授权", release = true) }
    }

    private fun capture(delayMs: Long = 300): Boolean {
        if (closed || display == null || projection == null) return false
        if (capturing) return true
        pauseGlass("正在准备你主动拍下的截图")
        detachReader(); capturing = true
        screenshotStart = Runnable {
            screenshotStart = null
            if (!closed && capturing) runCatching { attachScreenshotReader() }
                .onFailure { endWithError("截图未能启动，请重新授权") }
        }.also { handler.postDelayed(it, delayMs) }
        timeout = Runnable {
            if (capturing) {
                detachReader(); capturing = false; FloatingCaptureService.restore(); refreshEnvironment()
                Toast.makeText(this, "没有收到截图，当前页面可能禁止截屏", Toast.LENGTH_SHORT).show()
            }
        }.also { handler.postDelayed(it, 12_000) }
        return true
    }
    private fun attachScreenshotReader() {
        require(width.toLong() * height <= 20_000_000)
        ensureReader(ReaderMode.SCREENSHOT, width, height)
        display!!.surface = reader!!.surface
    }

    private fun ensureReader(mode: ReaderMode, w: Int, h: Int) {
        if (reader != null && readerMode == mode && readerWidth == w && readerHeight == h) return
        detachReader()
        val next = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = next; readerMode = mode; readerWidth = w; readerHeight = h
        val epoch = generation
        next.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            if (closed || source !== reader || epoch != generation || processingFrame) { image.close(); return@setOnImageAvailableListener }
            if (mode == ReaderMode.GLASS) {
                val region = ringRequest
                if (!awaitingGlassFrame || region == null || image.timestamp > 0 && image.timestamp < requestedAtNanos) {
                    image.close(); return@setOnImageAvailableListener
                }
                processingFrame = true
                val before = System.nanoTime()
                try {
                    val needed = region.roi.width * region.roi.height
                    if (ringPixels.size != needed) ringPixels = IntArray(needed)
                    val plane = image.planes[0]
                    val usable = GlobalGlassSampling.copyRing(plane.buffer, plane.rowStride, plane.pixelStride, region, ringPixels)
                    image.close()
                    val pixels = ringPixels
                    handler.post {
                        if (!closed && epoch == generation && globalRequested && !capturing && screenUsable()) {
                            display?.surface = null // no capture surface between 12fps requests
                            processedFrames++; processingNanos += System.nanoTime() - before
                            if (usable) GlobalGlassBackdrop.publish(region, pixels, true)
                            else GlobalGlassBackdrop.session(true, GlobalGlassPhase.PAUSED, "画面受保护或没有可见内容，使用系统模糊")
                            if (processedFrames == 1 || processedFrames % 120 == 0) android.util.Log.i("GlobalGlass",
                                "frames=$processedFrames roi=${region.roi.width}x${region.roi.height} capture=${w}x$h avgProcessMs=${processingNanos / processedFrames / 1_000_000}")
                            processingFrame = false; awaitingGlassFrame = false
                            nextGlass()
                        }
                    }
                } catch (_: Exception) {
                    runCatching { image.close() }
                    handler.post { if (epoch == generation) { pauseGlass("采样画面不可用", release = true); nextGlass(250) } }
                }
            } else {
                if (!capturing) { image.close(); return@setOnImageAvailableListener }
                processingFrame = true
                try {
                    val plane = image.planes[0]
                    val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                    val cropped = try {
                        padded.copyPixelsFromBuffer(plane.buffer)
                        Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                    } catch (failure: Exception) { padded.recycle(); throw failure }
                    if (cropped !== padded) padded.recycle()
                    image.close()
                    handler.post {
                        if (closed || epoch != generation || !capturing || !screenUsable()) cropped.recycle()
                        else finishScreenshot(cropped)
                    }
                } catch (_: Exception) {
                    runCatching { image.close() }
                    handler.post { if (epoch == generation) endWithError("截图处理失败，请重新点击阿噜") }
                }
            }
        }, worker)
        display!!.resize(w, h, resources.displayMetrics.densityDpi)
    }

    private fun finishScreenshot(bitmap: Bitmap) {
        timeout?.let(handler::removeCallbacks); timeout = null
        detachReader()
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { try { ImageBillImport.save(this@ScreenCaptureService, bitmap) } finally { bitmap.recycle() } }
                ImageBillImport.accept(file)
                startActivity(Intent(this@ScreenCaptureService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(this@ScreenCaptureService, "截图未能打开，请重试", Toast.LENGTH_SHORT).show() }
            finally { capturing = false; FloatingCaptureService.restore(); refreshEnvironment() }
        }
    }
    private fun detachReader() {
        generation++; awaitingGlassFrame = false; processingFrame = false; ringRequest = null
        runCatching { display?.surface = null }
        val previous = reader; reader = null; readerMode = ReaderMode.NONE
        previous?.setOnImageAvailableListener(null, null)
        worker.post { previous?.close(); ringPixels.fill(0); ringPixels = IntArray(0) }
    }
    private fun updateNotification(title: String, detail: String) {
        val text = "$title|$detail"
        if (text == lastNotification) return
        lastNotification = text
        getSystemService(NotificationManager::class.java).notify(3102,
            captureNotification(this, title, detail, ScreenCaptureService::class.java))
    }
    private fun shutdownSampling() {
        globalRequested = false
        handler.removeCallbacks(glassTick)
        GlobalGlassBackdrop.session(false, GlobalGlassPhase.OFF, "共享已停止，需要重新授权")
        GlobalGlassBackdrop.release()
        detachReader()
    }
    private fun cancelPendingScreenshot() {
        if (!capturing) return
        capturing = false
        screenshotStart?.let(handler::removeCallbacks); screenshotStart = null
        timeout?.let(handler::removeCallbacks); timeout = null
        detachReader(); FloatingCaptureService.restore()
    }
    private fun endWithError(message: String) { shutdownSampling(); Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); stopSelf() }
    override fun onTaskRemoved(rootIntent: Intent?) { shutdownSampling(); stopSelf(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        closed = true; instance = null; mutableReady.value = false
        handler.removeCallbacksAndMessages(null); scope.cancel(); shutdownSampling()
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        display?.release(); display = null; projection?.stop(); projection = null
        workerThread.quitSafely()
        FloatingCaptureService.restore(); super.onDestroy()
    }
    companion object {
        const val EXTRA_GLOBAL_GLASS = "globalGlass"
        private var instance: ScreenCaptureService? = null
        private val mutableReady = MutableStateFlow(false)
        val ready = mutableReady.asStateFlow()
        fun captureIfReady(): Boolean = instance?.capture() ?: false
        fun enableGlobalIfReady(): Boolean = instance?.setGlobalRequested(true) ?: false
        fun stopGlobalSampling() { instance?.setGlobalRequested(false) }
        internal fun refreshGlassEnvironment() { instance?.refreshEnvironment() }
    }
}
