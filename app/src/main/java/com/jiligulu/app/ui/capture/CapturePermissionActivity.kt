package com.jiligulu.app.ui.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** System consent only. No hidden or persistent capture authorization. */
class CapturePermissionActivity : ComponentActivity() {
    private var handedOff = false
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, ScreenCaptureService::class.java)
                    .putExtra("code", result.resultCode).putExtra("grant", result.data)
                    .putExtra("prepareOnly", intent.getBooleanExtra("prepareOnly", false)))
                handedOff = true
            } catch (_: Exception) { android.widget.Toast.makeText(this, "截图未能启动，请重试", android.widget.Toast.LENGTH_SHORT).show() }
        }
        if (!handedOff) FloatingCaptureService.restore()
        finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ScreenCaptureService.ready.value) {
            if (!intent.getBooleanExtra("prepareOnly", false)) ScreenCaptureService.captureIfReady()
            finish(); return
        }
        if (savedInstanceState == null) {
            try {
                val manager = getSystemService(MediaProjectionManager::class.java)
                val request = if (android.os.Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(
                    android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
                permission.launch(request)
            }
            catch (_: Exception) { FloatingCaptureService.restore(); finish() }
        }
    }
}
