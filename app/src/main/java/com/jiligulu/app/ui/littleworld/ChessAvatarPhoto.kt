package com.jiligulu.app.ui.littleworld

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Only bounded JPEG pixels are stored/shared. Remote strings can never become a file or URI read. */
internal object ChessAvatarPhoto {
    const val SIDE = 112
    const val MAX_JPEG_BYTES = 6 * 1024
    const val MAX_BASE64_LENGTH = 8192
    private const val MAX_SOURCE_BYTES = 24 * 1024 * 1024
    internal fun importDirectory(context: Context) = File(context.cacheDir, "chess-avatar-imports")
    private val checked = object : LinkedHashMap<String, Boolean>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 8
    }

    fun normalizedJpeg(value: String): String {
        if (value.isEmpty() || value.length > MAX_BASE64_LENGTH) return ""
        val valid = synchronized(checked) { checked[value] } ?: validJpeg(value).also {
            synchronized(checked) { checked[value] = it }
        }
        return if (valid) value else ""
    }
    private fun validJpeg(value: String): Boolean = runCatching {
        require(value.matches(Regex("[A-Za-z0-9+/]+={0,2}")))
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size in 4..MAX_JPEG_BYTES && (bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 255) == 216)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outMimeType != "image/jpeg" || bounds.outWidth !in 1..SIDE || bounds.outHeight !in 1..SIDE ||
            bounds.outWidth != bounds.outHeight) return@runCatching false
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching false
        try { decoded.width == bounds.outWidth && decoded.height == bounds.outHeight }
        finally { decoded.recycle() }
    }.getOrDefault(false)

    fun decodePreview(value: String): Bitmap? {
        val checked = normalizedJpeg(value)
        if (checked.isEmpty()) return null
        return runCatching {
            val bytes = Base64.getDecoder().decode(checked)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    suspend fun importPhoto(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请选择相册中的图片" }
        val directory = importDirectory(context)
        check(directory.isDirectory || directory.mkdirs()) { "头像缓存暂不可用" }
        val temporary = File.createTempFile("avatar-", ".source", directory)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_SOURCE_BYTES) { "图片过大，请换一张小于 24 MB 的图片" }
                    output.write(buffer, 0, count)
                }
            } } ?: error("图片暂时无法读取，请重新选择")
            croppedJpeg(temporary)
        } finally { temporary.delete() }
    }

    private suspend fun croppedJpeg(temporary: File): String {
        currentCoroutineContext().ensureActive()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(temporary.path, bounds)
        require(bounds.outWidth in 1..32_000 && bounds.outHeight in 1..32_000 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L) { "这张图片无法处理，请换一张" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while ((maxOf(bounds.outWidth, bounds.outHeight) + inSampleSize - 1) / inSampleSize > 640) inSampleSize *= 2
        }
        val source = checkNotNull(BitmapFactory.decodeFile(temporary.path, options))
        var oriented: Bitmap? = null
        var cropped: Bitmap? = null
        var thumbnail: Bitmap? = null
        try {
            val orientation = runCatching { ExifInterface(temporary.path)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
            val matrix = Matrix().apply { when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f, 1f) }
                8 -> setRotate(-90f)
            } }
            val upright = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also { oriented = it }
            val edge = minOf(upright.width, upright.height)
            val crop = Bitmap.createBitmap(upright, (upright.width - edge) / 2, (upright.height - edge) / 2, edge, edge).also { cropped = it }
            val small = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888).also { thumbnail = it }
            Canvas(small).apply {
                drawColor(Color.rgb(247, 244, 247))
                drawBitmap(crop, null, android.graphics.Rect(0, 0, SIDE, SIDE), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
            currentCoroutineContext().ensureActive()
            for (quality in listOf(85, 70, 55, 40, 25, 15, 10)) {
                val compressed = ByteArrayOutputStream().use { output ->
                    check(small.compress(Bitmap.CompressFormat.JPEG, quality, output)); output.toByteArray()
                }
                if (compressed.size <= MAX_JPEG_BYTES) {
                    val encoded = Base64.getEncoder().encodeToString(compressed)
                    check(normalizedJpeg(encoded).isNotEmpty())
                    return encoded
                }
            }
            error("头像未能压缩，请换一张图片")
        } finally {
            listOfNotNull(source, oriented, cropped, thumbnail).distinct().forEach { it.recycle() }
        }
    }
}
