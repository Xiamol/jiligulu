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
import com.jiligulu.app.data.prefs.GlobalGlassPrefs
import com.jiligulu.app.data.prefs.GlobalGlassFrameRate
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
    @Volatile private var processingFrame = false
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
    private var processedFrames = 0
    private var processingNanos = 0L
    @Volatile private var samplingActive = false
    @Volatile private var sampleRate = GlobalGlassFrameRate.DEFAULT
    private val frameCadence = GlassFrameCadence() // worker-thread only
    private val frameHistory = GlassPixelHistory() // worker-thread dedupe; never compare large arrays on Main
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
            scope.launch {
                GlobalGlassPrefs(this@ScreenCaptureService).frameRate.collect { rate ->
                    sampleRate = rate
                    GlobalGlassBackdrop.setTargetFps(rate.fps)
                    if (globalRequested) refreshEnvironment()
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
            GlobalGlassBackdrop.session(true, GlobalGlassPhase.STARTING, "本次共享已授权，等待可见浮球")
            refreshEnvironment()
        } else {
            samplingActive = false
            handler.removeCallbacks(glassTick)
            if (!capturing) detachReader()
            GlobalGlassBackdrop.session(false, GlobalGlassPhase.OFF, "全局光学已停止")
            GlobalGlassBackdrop.release()
            updateNotification("阿噜截屏已就绪", "全局折射已关闭 · 仅点击时截图 · 关闭可结束共享")
        }
        return true
    }

    private fun refreshEnvironment() {
        handler.removeCallbacks(glassTick)
        if (!closed && globalRequested) handler.post(glassTick)
    }
    private fun pauseGlass(reason: String, release: Boolean = false) {
        samplingActive = false
        handler.removeCallbacks(glassTick)
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
        if (GlobalGlassBackdrop.rendererUnavailable) { pauseGlass("设备当前无法使用光学，保留基础透明玻璃", release = true); return }
        if (capturing) { pauseGlass("正在处理你主动拍下的截图"); return }
        if (!screenUsable()) { pauseGlass("屏幕锁定或关闭，采样暂停", release = true); return }
        if (!projectionVisible) { pauseGlass("共享画面不可见，采样暂停", release = true); return }
        if (!fullDisplayShared()) { pauseGlass("请选择共享整个屏幕后再启用", release = true); return }
        // Activity/dialog resume/pause already calls refreshEnvironment. Polling every
        // 250 ms here needlessly woke the main thread even while the app was motionless.
        if (AppGlassBackdrop.available()) { pauseGlass("应用内使用本应用背景，整屏采样暂停"); return }
        val target = GlobalGlassBackdrop.currentTarget()
        if (target == null) {
            pauseGlass("浮球已隐藏或不可见，采样暂停")
            return
        }
        runCatching {
            val (w, h) = GlobalGlassSampling.captureSize(width, height)
            ensureReader(ReaderMode.GLASS, w, h)
            samplingActive = true
            // One persistent surface. ImageReader supplies only its latest image; the
            // worker's cadence decides whether that frame needs processing.
            val surface = reader!!.surface
            if (display!!.surface !== surface) display!!.surface = surface
            updateNotification("阿噜全局光学运行中", "目标${sampleRate.fps}帧/秒 · 中心近似重建 · 仅内存、不保存上传")
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
        val sourceWidth = width
        val sourceHeight = height
        next.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            if (closed || source !== reader || epoch != generation || processingFrame) { image.close(); return@setOnImageAvailableListener }
            if (mode == ReaderMode.GLASS) {
                if (!samplingActive || !globalRequested || !frameCadence.accepts(System.nanoTime(), sampleRate.fps)) {
                    image.close(); return@setOnImageAvailableListener
                }
                val target = GlobalGlassBackdrop.samplingTarget(image.timestamp)
                val region = target?.let { GlobalGlassSampling.region(sourceWidth, sourceHeight, w, h, it.bubble, it.unsafeBounds) }
                if (region == null) { image.close(); return@setOnImageAvailableListener }
                processingFrame = true
                val before = System.nanoTime()
                try {
                    val needed = region.roi.width * region.roi.height
                    if (ringPixels.size != needed) ringPixels = IntArray(needed)
                    val plane = image.planes[0]
                    val usable = GlobalGlassSampling.copyRing(plane.buffer, plane.rowStride, plane.pixelStride, region, ringPixels)
                    val changed = frameHistory.changed(region, ringPixels)
                    val materialTone=if(usable)GlassAmbientTone.estimate(ringPixels)else GlassAmbientTone.NEUTRAL
                    image.close()
                    val pixels = ringPixels
                    handler.post {
                        if (!closed && epoch == generation && globalRequested && samplingActive && !capturing) {
                            processedFrames++; processingNanos += System.nanoTime() - before
                            if (usable) {
                                if (changed || !GlobalGlassBackdrop.keepFresh(region)) GlobalGlassBackdrop.publish(region, pixels, true,materialTone)
                            } else GlobalGlassBackdrop.session(true, GlobalGlassPhase.PAUSED, "画面受保护或没有可信采样，使用基础透明玻璃")
                            if (processedFrames == 1 || processedFrames % 120 == 0) android.util.Log.i("GlobalGlass",
                                "frames=$processedFrames roi=${region.roi.width}x${region.roi.height} capture=${w}x$h avgProcessMs=${processingNanos / processedFrames / 1_000_000}")
                        }
                        if (epoch == generation) processingFrame = false
                    }
                } catch (_: Exception) {
                    runCatching { image.close() }
                    handler.post { if (epoch == generation) pauseGlass("采样画面不可用", release = true) }
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
        generation++; processingFrame = false
        runCatching { display?.surface = null }
        val previous = reader; reader = null; readerMode = ReaderMode.NONE
        previous?.setOnImageAvailableListener(null, null)
        worker.post { previous?.close(); ringPixels.fill(0); ringPixels = IntArray(0); frameCadence.reset(); frameHistory.clear() }
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
        samplingActive = false
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
