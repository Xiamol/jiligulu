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

/** One frame per consent, bounded lifetime, projection and buffers always released. */
class ScreenCaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var finished = false
    private var started = false
    private var notBefore = 0L
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        started = true
        try {
            val notification = captureNotification(this, "正在截取账单", "只截取这一次画面，完成后自动停止", ScreenCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= 29) startForeground(3102, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(3102, notification)
            val grant = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("grant", Intent::class.java) else @Suppress("DEPRECATION") (intent?.getParcelableExtra("grant") as? Intent)
            require(grant != null)
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent!!.getIntExtra("code", 0), grant)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (!finished && display != null && width > 0 && height > 0 && (reader?.width != width || reader?.height != height)) {
                        reader?.close(); reader = makeReader(width, height)
                        display?.resize(width, height, resources.displayMetrics.densityDpi); display?.surface = reader!!.surface
                    }
                }
            }, handler)
            handler.postDelayed({
                if (!finished) runCatching {
                    val metrics = resources.displayMetrics
                    val bounds = if (Build.VERSION.SDK_INT >= 30) getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds else null
                    reader = makeReader(bounds?.width() ?: metrics.widthPixels, bounds?.height() ?: metrics.heightPixels)
                    notBefore = 0L
                    display = projection!!.createVirtualDisplay("Gulu single screenshot", reader!!.width, reader!!.height, metrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
                }.onFailure { fail("截图未能启动，请重试") }
            }, 750)
            handler.postDelayed({ if (!finished) fail("没有收到截图，可能是当前页面禁止截屏") }, 12000)
        } catch (_: Exception) { fail("截图授权已失效，请重新点击阿噜") }
        return START_NOT_STICKY
    }
    private fun makeReader(width: Int, height: Int): ImageReader {
        require(width.toLong() * height <= 20_000_000)
        return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { target ->
            target.setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                if (finished || SystemClock.elapsedRealtime() < notBefore) { image.close(); return@setOnImageAvailableListener }
                finished = true
                try {
                    val plane = image.planes[0]
                    val paddedWidth = plane.rowStride / plane.pixelStride
                    val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                    if (cropped !== padded) padded.recycle()
                    image.close()
                    display?.release(); display = null; reader?.close(); reader = null
                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) { try { ImageBillImport.save(this@ScreenCaptureService, cropped) } finally { cropped.recycle() } }
                            ImageBillImport.accept(file)
                            startActivity(Intent(this@ScreenCaptureService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        } catch (_: Exception) { Toast.makeText(this@ScreenCaptureService, "截图未能打开，请重试", Toast.LENGTH_SHORT).show() }
                        finally { stopSelf() }
                    }
                } catch (_: Exception) { image.close(); fail("截图处理失败，请重试") }
            }, handler)
        }
    }
    private fun fail(message: String) { finished = true; Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); stopSelf() }
    override fun onDestroy() {
        finished = true; handler.removeCallbacksAndMessages(null); scope.cancel()
        display?.release(); reader?.close(); projection?.stop(); projection = null
        FloatingCaptureService.restore(); super.onDestroy()
    }
}
