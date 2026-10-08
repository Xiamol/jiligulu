package com.jiligulu.app.ui.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import com.jiligulu.app.JiliguluApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** One explicitly chosen image; never searches gallery or captures in the background. */
object ImageBillImport {
    private val mutable = MutableStateFlow<File?>(null)
    val pending = mutable.asStateFlow()
    fun directory(context: Context) = File(context.cacheDir, "bill-images").apply { mkdirs() }
    fun accept(file: File) { mutable.value?.takeIf { it != file }?.delete(); mutable.value = file }
    fun clear() { mutable.value?.delete(); mutable.value = null }
    suspend fun import(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val raw = File(directory(context), "${UUID.randomUUID()}.source")
        try {
            context.contentResolver.openInputStream(uri)!!.use { input -> raw.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 24 * 1024 * 1024) { "图片过大，请选小于 24 MB 的图片" }; output.write(buffer, 0, n) }
            } }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(raw.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "这张图片无法读取，请换一张" }
            val options = BitmapFactory.Options().apply { inSampleSize = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 4096 || bounds.outWidth.toLong() / inSampleSize * (bounds.outHeight / inSampleSize) > 8_000_000) inSampleSize *= 2 }
            val bitmap = checkNotNull(BitmapFactory.decodeFile(raw.path, options))
            val orientation = runCatching { ExifInterface(raw.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            val matrix = Matrix().apply { when (orientation) { 2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f); 5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f); 7 -> { setRotate(-90f); postScale(-1f, 1f) }; 8 -> setRotate(-90f) } }
            val fixed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            try { save(context, fixed) } finally { if (fixed !== bitmap) fixed.recycle(); bitmap.recycle() }
        } finally { raw.delete() }
    }
    fun save(context: Context, bitmap: Bitmap): File = File(directory(context), "${UUID.randomUUID()}.jpg").also { file ->
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
    }
    suspend fun recognize(context: Context, file: File): String = withContext(Dispatchers.IO) {
        val requestMillis = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        val service = (context.applicationContext as JiliguluApp).container.aiRepository.createClient()
        require(service.profile.supportsImages) {
            "当前模型未启用图片输入，请在「设置 → AI 服务」选择支持识图的模型或编辑图像能力"
        }
        val data = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        val content = service.recognizeImage(com.jiligulu.app.core.ai.ImageReceiptCodec.PROMPT,
            com.jiligulu.app.core.ai.ImageReceiptCodec.requestContext(requestMillis, zone), data).getOrThrow()
        require(content.isNotBlank() && content.length <= 8000) { "这次没读到有效内容，可以换张清晰图片重试" }
        com.jiligulu.app.core.ai.ImageReceiptCodec.render(content, requestMillis, zone)
    }
}
