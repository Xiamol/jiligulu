package com.jiligulu.app.ui.memories

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import com.jiligulu.app.ui.littleworld.ArtworkMemoryCache
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MemoryDecodeAndPosterTest {
    @get:Rule val temporary = TemporaryFolder()
    @After fun clearArtworkReferences() = MemoryPoster.clearMemoryCache()
    private fun context(): Context {
        val cache = temporary.newFolder("cache")
        val files = temporary.newFolder("files")
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = cache
            override fun getFilesDir(): File = files
        }
    }

    @Test fun sampledPhotoStillHonoursExifOrientationColourAndTargetSizeWithoutChangingTheOriginal() {
        val context = context()
        val source = File(temporary.root, "original.jpg")
        val bitmap = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint()
            paint.color = Color.RED; canvas.drawRect(0f, 0f, 800f, 800f, paint)
            paint.color = Color.GREEN; canvas.drawRect(800f, 0f, 1600f, 800f, paint)
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        } finally { bitmap.recycle() }
        ExifInterface(source.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes()
        }
        val decoded = requireNotNull(MemoryFiles.readBitmap(context, source.path, 400))
        try {
            assertEquals(200, decoded.width)
            assertEquals(400, decoded.height)
            assertFalse(decoded.isRecycled)
            val top = decoded.getPixel(100, 40)
            val bottom = decoded.getPixel(100, 360)
            assertTrue(Color.red(top) > Color.green(top) + 100)
            assertTrue(Color.green(bottom) > Color.red(bottom) + 100)
            val original = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, original)
            assertEquals(1600, original.outWidth)
            assertEquals(800, original.outHeight)
            assertEquals(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface(source.path)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        } finally { decoded.recycle() }
    }

    @Test fun droppingGlobalBitmapReferencesDoesNotRecycleOrChangeAVisibleBitmap() {
        val cache = ArtworkMemoryCache<String, Bitmap>(4096) { it.allocationByteCount.toLong() }
        val visible = cache.getOrLoad("visible") { Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) } }
        cache.clear()
        try {
            assertNull(cache.get("visible"))
            assertEquals(0L, cache.bytes)
            assertFalse(visible.isRecycled)
            assertEquals(Color.MAGENTA, visible.getPixel(16, 16))
        } finally { visible.recycle() }
    }

    @Test fun concurrentLegacyPosterRequestsWaitAndBothProduceCompleteShareableImages() = runBlocking {
        MemoryPoster.clearMemoryCache()
        val context = context()
        val outputs = coroutineScope { listOf("第一张", "第二张").map { title -> async {
            MemoryPoster.render(context, PosterData(title, "有些普通的生活，也值得收好。"), false, "好好生活")
        } }.awaitAll() }
        assertEquals(2, outputs.map { it.path }.toSet().size)
        outputs.forEach { file ->
            assertTrue(file.isFile && file.length() > 100)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            assertEquals(1080, options.outWidth)
            assertTrue(options.outHeight >= 1280)
        }
        assertTrue(File(context.cacheDir, "memory-poster-noto.ttf").isFile)
    }

    @Test @Config(sdk = [35]) fun modernResourceFontsRenderWithoutCreatingTheLargeCacheCopy() = runBlocking {
        MemoryPoster.clearMemoryCache()
        val context = context()
        assertEquals(400, MemoryPoster.posterTypeface(context, 400).weight)
        assertEquals(650, MemoryPoster.posterTypeface(context, 650).weight)
        val output = MemoryPoster.render(context, PosterData("现代字体", "好好吃饭，慢慢生活。"), false, "小小快乐")
        assertTrue(output.isFile && output.length() > 100)
        assertFalse(File(context.cacheDir, "memory-poster-noto.ttf").exists())
    }

    @Test @Config(sdk = [35]) fun aResourceFontFailureFallsBackToTheSameReadableLegacyWeight() {
        val context = context()
        val font = MemoryPoster.posterTypeface(context, 650) { _, _ -> error("Synthetic resource font failure") }
        assertEquals(650, font.weight)
        assertTrue(File(context.cacheDir, "memory-poster-noto.ttf").isFile)
        val bitmap = Bitmap.createBitmap(600, 100, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            Canvas(bitmap).drawText("阿噜替你收好这一页", 5f, 65f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = font; textSize = 45f; color = Color.BLACK
            })
            assertTrue((0 until bitmap.height).any { y -> (0 until bitmap.width).any { x -> bitmap.getPixel(x, y) != Color.WHITE } })
        } finally { bitmap.recycle() }
    }
}
