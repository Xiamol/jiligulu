package com.jiligulu.app.ui.capture

import android.app.KeyguardManager
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
import com.jiligulu.app.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Explicit screenshots only; the virtual display has no surface or reader while idle. */
class ScreenCaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val workerThread = HandlerThread("GuluScreenshot")
    private lateinit var worker: Handler
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null
    @Volatile private var generation = 0
    @Volatile private var closed = false
    @Volatile private var capturing = false
    @Volatile private var processingFrame = false
    private var started = false
    private var width = 0
    private var height = 0
    private var readerWidth = 0
    private var readerHeight = 0
    private var timeout: Runnable? = null
    private var screenshotStart: Runnable? = null
    private var receiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                cancelPendingScreenshot()
            }
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
        if (intent?.action == "stop") { releaseCapture(); stopSelf(); return START_NOT_STICKY }
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
                override fun onStop() { if (!closed) { releaseCapture(); stopSelf() } }
                override fun onCapturedContentResize(newWidth: Int, newHeight: Int) {
                    if (closed || newWidth <= 0 || newHeight <= 0 || width == newWidth && height == newHeight) return
                    val screenshotWasAttached = reader != null
                    width = newWidth; height = newHeight
                    detachReader()
                    runCatching {
                        display?.resize(width, height, resources.displayMetrics.densityDpi)
                        // A resize while the consent sheet is dismissing must not bypass the
                        // scheduled screenshot delay and capture the authorization UI itself.
                        if (capturing && screenshotWasAttached) attachScreenshotReader()
                    }.onFailure { endWithError("截屏尺寸已变化，请重新授权") }
                }
            }, handler)
            // Android 14 prohibits another createVirtualDisplay on this MediaProjection token.
            display = projection!!.createVirtualDisplay("Gulu user-approved capture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, handler)
            instance = this; mutableReady.value = true
            if (intent.getBooleanExtra("prepareOnly", false)) FloatingCaptureService.restore()
            else capture(delayMs = 750)
        } catch (_: Exception) { endWithError("截屏授权已失效，请重新点击阿噜") }
        return START_NOT_STICKY
    }

    private fun screenUsable(): Boolean = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun capture(delayMs: Long = 300): Boolean {
        if (closed || display == null || projection == null) return false
        if (capturing) return true
        detachReader(); capturing = true
        screenshotStart = Runnable {
            screenshotStart = null
            if (!closed && capturing) runCatching { attachScreenshotReader() }
                .onFailure { endWithError("截图未能启动，请重新授权") }
        }.also { handler.postDelayed(it, delayMs) }
        timeout = Runnable {
            if (capturing) {
                detachReader(); capturing = false; FloatingCaptureService.restore()
                Toast.makeText(this, "没有收到截图，当前页面可能禁止截屏", Toast.LENGTH_SHORT).show()
            }
        }.also { handler.postDelayed(it, 12_000) }
        return true
    }
    private fun attachScreenshotReader() {
        require(width.toLong() * height <= 20_000_000)
        ensureReader(width, height)
        display!!.surface = reader!!.surface
    }

    private fun ensureReader(w: Int, h: Int) {
        if (reader != null && readerWidth == w && readerHeight == h) return
        detachReader()
        val next = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = next; readerWidth = w; readerHeight = h
        val epoch = generation
        next.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            if (closed || source !== reader || epoch != generation || processingFrame) { image.close(); return@setOnImageAvailableListener }
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
            finally { capturing = false; FloatingCaptureService.restore() }
        }
    }
    private fun detachReader() {
        generation++; processingFrame = false
        runCatching { display?.surface = null }
        val previous = reader; reader = null
        previous?.setOnImageAvailableListener(null, null)
        worker.post { previous?.close() }
    }
    private fun releaseCapture() { detachReader() }
    private fun cancelPendingScreenshot() {
        if (!capturing) return
        capturing = false
        screenshotStart?.let(handler::removeCallbacks); screenshotStart = null
        timeout?.let(handler::removeCallbacks); timeout = null
        detachReader(); FloatingCaptureService.restore()
    }
    private fun endWithError(message: String) { releaseCapture(); Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); stopSelf() }
    override fun onTaskRemoved(rootIntent: Intent?) { releaseCapture(); stopSelf(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        closed = true; instance = null; mutableReady.value = false
        handler.removeCallbacksAndMessages(null); scope.cancel(); releaseCapture()
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        display?.release(); display = null; projection?.stop(); projection = null
        workerThread.quitSafely()
        FloatingCaptureService.restore(); super.onDestroy()
    }
    companion object {
        private var instance: ScreenCaptureService? = null
        private val mutableReady = MutableStateFlow(false)
        val ready = mutableReady.asStateFlow()
        fun captureIfReady(): Boolean = instance?.capture() ?: false
    }
}
