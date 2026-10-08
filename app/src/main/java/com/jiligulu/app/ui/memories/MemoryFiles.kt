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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.jiligulu.app.JiliguluApp
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Copy selected media into app storage, so a reboot or removed gallery permission cannot lose it. */
object MemoryFiles {
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ownedStores = ConcurrentHashMap<String, OwnedMediaStore>()
    private fun root(context: Context) = File(context.filesDir, "life-memories").apply { mkdirs() }
    private fun ownedStore(context: Context): OwnedMediaStore {
        val folder = root(context).canonicalFile
        return ownedStores.getOrPut(folder.path) { OwnedMediaStore(folder) }
    }
    fun posterFile(context: Context): File = File(root(context), "posters").apply { mkdirs() }
        .let { File(it, "${UUID.randomUUID()}.png") }

    /** Only remove copies owned by this importer, never an original gallery or another file. */
    fun deleteImportedPhoto(context: Context, path: String) {
        if (path.isNotBlank()) releaseDraftCopies(context, listOf(path))
    }

    suspend fun importPhoto(context: Context, uri: Uri): String {
        var created: File? = null
        try { return withContext(Dispatchers.IO) {
            val bitmap = readBitmap(context, uri, 1600) ?: error("这张照片暂时打不开，换一张试试吧。")
            val target = File(File(root(context), "photos").apply { mkdirs() }, "${UUID.randomUUID()}.jpg").also { created = it }
            try {
                target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
                target.absolutePath
            } finally { bitmap.recycle() }
        } } catch (failure: Throwable) {
            created?.delete()
            throw failure
        }
    }

    internal fun claimPrivateMedia(context: Context, path: String): AutoCloseable? = ownedStore(context).claim(path)

    /** Only release paths actually created by the calling draft. Existing files are never inferred as owned. */
    internal fun releaseDraftCopies(context: Context, paths: Collection<String>) {
        if (paths.isEmpty()) return
        val application = context.applicationContext
        cleanupScope.launch {
            val app = application as? JiliguluApp ?: return@launch // Unknown storage means keep, not guess.
            ownedStore(application).release(paths) {
                coroutineScope {
                    val live = async { app.container.billRepository.observePhotoMemories().first() }
                    val trash = async { app.container.billRepository.trash() }
                    val world = async { app.container.littleWorld.snapshot() }
                    val chat = async { app.container.chatHistoryRepository.mediaReferenceRows() }
                    val collection = world.await()
                    ((live.await() + trash.await()).mapNotNull { it.photoUri } +
                        collection.wishes.map { it.photoPath } + collection.cards.map { it.imagePath } +
                        chatMediaReferences(chat.await()))
                        .filter { it.isNotBlank() && !it.startsWith("content:") }.mapNotNull { path ->
                            runCatching { if (path.startsWith("file:")) Uri.parse(path).path?.let { File(it).canonicalPath }
                                else File(path).canonicalPath }.getOrNull()
                        }.toSet()
                }
            }
        }
    }

    fun readBitmap(context: Context, path: String, maxSide: Int = 900): Bitmap? =
        readBitmap(context, if (path.startsWith("content:") || path.startsWith("file:")) Uri.parse(path)
            else Uri.fromFile(File(path)), maxSide)

    private fun readBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        // Keep the picker's typed URI intact. String slicing loses URI escaping and file-path semantics.
        fun stream() = if (uri.scheme == "file") uri.path?.let(::File)?.takeIf { it.isFile }?.inputStream()
            else context.contentResolver.openInputStream(uri)
        var owned: Bitmap? = null
        var returned = false
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            stream()?.use { BitmapFactory.decodeStream(it, null, bounds); true } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                // Scale while decoding, keeping the delivered bitmap at the target size.
                // Sampling alone can leave a temporary image almost four times its pixel count.
                if (maxOf(bounds.outWidth, bounds.outHeight) > maxSide) {
                    inScaled = true
                    inDensity = maxOf(bounds.outWidth, bounds.outHeight)
                    inTargetDensity = maxSide * sample
                }
            }
            val original = stream()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            owned = original
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
            val scale = maxSide.toFloat() / maxOf(original.width, original.height)
            if (scale < 1f) matrix.postScale(scale, scale)
            val result = if (matrix.isIdentity) original else Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            if (result !== original) { original.recycle(); owned = result }
            returned = true
            result
        } catch (_: Exception) { null }
        catch (_: OutOfMemoryError) { null }
        finally { if (!returned) owned?.recycle() }
    }
}

/** Decodes thumbnails off the UI thread; decoded bitmaps die with their bounded visible item. */
@Composable
fun MemoryPhoto(path: String, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, maxSide: Int = 700) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, path, maxSide) {
        value = withContext(Dispatchers.IO) { MemoryFiles.readBitmap(context, path, maxSide.coerceAtLeast(32)) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), "生活照片", Modifier.matchParentSize(), contentScale = scale) }
            ?: Text("✦", color = MaterialTheme.colorScheme.primary)
    }
}
