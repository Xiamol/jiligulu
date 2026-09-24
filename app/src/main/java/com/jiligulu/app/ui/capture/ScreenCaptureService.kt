package com.jiligulu.app.ui.capture

import android.app.Service
import android.content.Intent
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
import com.jiligulu.app.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One consent -> one virtual display. Idle has no surface; each click attaches a fresh reader. */
class ScreenCaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var closed = false
    private var started = false
    private var capturing = false
    private var width = 0
    private var height = 0
    private var timeout: Runnable? = null
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        started = true
        try {
            val notification = captureNotification(this, "阿噜截屏已就绪", "仅点击时截图 · 关闭即可结束本次共享授权", ScreenCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= 29) startForeground(3102, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(3102, notification)
            val grant = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("grant", Intent::class.java) else @Suppress("DEPRECATION") (intent?.getParcelableExtra("grant") as? Intent)
            require(grant != null)
            val metrics = resources.displayMetrics
            val bounds = if (Build.VERSION.SDK_INT >= 30) getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds else null
            width = bounds?.width() ?: metrics.widthPixels
            height = bounds?.height() ?: metrics.heightPixels
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent!!.getIntExtra("code", 0), grant)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
                override fun onCapturedContentResize(newWidth: Int, newHeight: Int) {
                    if (closed || newWidth <= 0 || newHeight <= 0 || (width == newWidth && height == newHeight)) return
                    width = newWidth; height = newHeight
                    runCatching {
                        display?.resize(width, height, resources.displayMetrics.densityDpi)
                        if (reader != null && capturing) attachReader()
                    }.onFailure { endWithError("截屏尺寸已变化，请重新授权") }
                }
            }, handler)
            // Android 14 forbids another createVirtualDisplay on this token. Keep this display alive.
            display = projection!!.createVirtualDisplay("Gulu on-demand screenshots", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, handler)
            instance = this; mutableReady.value = true
            if (intent.getBooleanExtra("prepareOnly", false)) FloatingCaptureService.restore()
            else capture(delayMs = 750)
        } catch (_: Exception) { endWithError("截屏授权已失效，请重新点击阿噜") }
        return START_NOT_STICKY
    }

    private fun capture(delayMs: Long = 300): Boolean {
        if (closed || display == null || projection == null) return false
        if (capturing) return true
        capturing = true
        handler.postDelayed({
            if (!closed && capturing) runCatching { attachReader() }
                .onFailure { endWithError("截图未能启动，请重新授权") }
        }, delayMs)
        timeout = Runnable { if (capturing) {
            detachReader(); capturing = false; FloatingCaptureService.restore()
            Toast.makeText(this, "没有收到截图，当前页面可能禁止截屏", Toast.LENGTH_SHORT).show()
        } }.also { handler.postDelayed(it, 12000) }
        return true
    }

    private fun attachReader() {
        require(width.toLong() * height <= 20_000_000)
        detachReader()
        val next = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = next
        next.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            if (closed || !capturing || source !== reader) { image.close(); return@setOnImageAvailableListener }
            try {
                val plane = image.planes[0]
                val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                val cropped = try {
                    padded.copyPixelsFromBuffer(plane.buffer)
                    Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                } catch (failure: Exception) { padded.recycle(); throw failure }
                if (cropped !== padded) padded.recycle()
                image.close()
                timeout?.let(handler::removeCallbacks); timeout = null
                // Disconnect immediately: idle session receives no screen frames and saves no images.
                detachReader()
                scope.launch {
                    try {
                        val file = withContext(Dispatchers.IO) { try { ImageBillImport.save(this@ScreenCaptureService, cropped) } finally { cropped.recycle() } }
                        ImageBillImport.accept(file)
                        startActivity(Intent(this@ScreenCaptureService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) { Toast.makeText(this@ScreenCaptureService, "截图未能打开，请重试", Toast.LENGTH_SHORT).show()
                    } finally { capturing = false; FloatingCaptureService.restore() }
                }
            } catch (_: Exception) { image.close(); endWithError("截图处理失败，请重新点击阿噜") }
        }, handler)
        display!!.resize(width, height, resources.displayMetrics.densityDpi)
        display!!.surface = next.surface
    }

    private fun detachReader() {
        runCatching { display?.surface = null }
        reader?.close(); reader = null
    }
    private fun endWithError(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); stopSelf() }
    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        closed = true; instance = null; mutableReady.value = false
        handler.removeCallbacksAndMessages(null); scope.cancel(); detachReader()
        display?.release(); display = null; projection?.stop(); projection = null
        FloatingCaptureService.restore(); super.onDestroy()
    }
    companion object {
        private var instance: ScreenCaptureService? = null
        private val mutableReady = MutableStateFlow(false)
        val ready = mutableReady.asStateFlow()
        fun captureIfReady(): Boolean = instance?.capture() ?: false
    }
}
