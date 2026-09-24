package com.jiligulu.app.ui.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.core.ai.AiConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()
    suspend fun recognize(context: Context, file: File): String = withContext(Dispatchers.IO) {
        val key = (context.applicationContext as JiliguluApp).container.aiRepository.effectiveApiKey()
        val data = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        val body = buildJsonObject {
            put("model", "deepseek-flash")
            put("max_tokens", 2400)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("temperature", 0.0)
            put("thinking", buildJsonObject { put("type", "disabled") })
            put("messages", buildJsonArray {
                addJsonObject { put("role", "system"); put("content", com.jiligulu.app.core.ai.ImageReceiptCodec.PROMPT) }
                addJsonObject { put("role", "user"); put("content", buildJsonArray {
                    addJsonObject { put("type", "text"); put("text", "请识别这张图片中的账单，返回可供我核对修改的文字。") }
                    addJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$data") }) }
                }) }
            })
        }.toString()
        val call = client.newCall(Request.Builder().url(AiConfig.BASE_URL).header("Authorization", "Bearer $key").post(body.toRequestBody("application/json".toMediaType())).build())
        val response = suspendCancellableCoroutine<Response> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
            })
        }
        response.use {
            check(it.isSuccessful) { when(it.code) { 401 -> "AI 密钥无效，请检查设置"; 402 -> "AI 余额不足"; 429 -> "请求有点多，请稍后重试"; else -> "图片识别暂不可用（${it.code}），请稍后重试" } }
            val root = Json.parseToJsonElement(it.body!!.string()).jsonObject
            val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull?.takeIf { text -> text.isNotBlank() && text.length <= 8000 }
                ?: error("这次没读到内容，可以换张清晰的图片重试")
            com.jiligulu.app.core.ai.ImageReceiptCodec.render(content)
        }
    }
}
