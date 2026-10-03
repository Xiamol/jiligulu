package com.jiligulu.app.ui.memories

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Copy selected media into app storage, so a reboot or removed gallery permission cannot lose it. */
object MemoryFiles {
    private fun root(context: Context) = File(context.filesDir, "life-memories").apply { mkdirs() }
    fun posterFile(context: Context): File = File(root(context), "posters").apply { mkdirs() }
        .let { File(it, "${UUID.randomUUID()}.png") }

    suspend fun importPhoto(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val bitmap = readBitmap(context, uri, 1600) ?: error("这张照片暂时打不开，换一张试试吧。")
        val target = File(File(root(context), "photos").apply { mkdirs() }, "${UUID.randomUUID()}.jpg")
        try {
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
            target.absolutePath
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        } finally { bitmap.recycle() }
    }

    fun readBitmap(context: Context, path: String, maxSide: Int = 900): Bitmap? =
        readBitmap(context, if (path.startsWith("content:") || path.startsWith("file:")) Uri.parse(path)
            else Uri.fromFile(File(path)), maxSide)

    private fun readBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        // Keep the picker's typed URI intact. String slicing loses URI escaping and file-path semantics.
        fun stream() = if (uri.scheme == "file") uri.path?.let(::File)?.takeIf { it.isFile }?.inputStream()
            else context.contentResolver.openInputStream(uri)
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            stream()?.use { BitmapFactory.decodeStream(it, null, bounds); true } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val original = stream()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            val orientation = runCatching { stream()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }.getOrNull()
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { postScale(-1f, 1f); postRotate(90f) }
                    ExifInterface.ORIENTATION_TRANSVERSE -> { postScale(-1f, 1f); postRotate(270f) }
                }
            }
            var result = if (matrix.isIdentity) original else Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true).also { if (it !== original) original.recycle() }
            val scale = maxSide.toFloat() / maxOf(result.width, result.height)
            if (scale < 1f) result = Bitmap.createScaledBitmap(result, (result.width * scale).toInt().coerceAtLeast(1), (result.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== result) result.recycle() }
            result
        }.getOrNull()
    }
}

/** Decodes thumbnails off the UI thread; decoded bitmaps die with their bounded visible item. */
@Composable
fun MemoryPhoto(path: String, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { MemoryFiles.readBitmap(context, path, 700) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), "生活照片", Modifier.matchParentSize(), contentScale = scale) }
            ?: Text("✦", color = MaterialTheme.colorScheme.primary)
    }
}
