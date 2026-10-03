package com.jiligulu.app.ui.memories

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import com.jiligulu.app.data.local.AppDatabase
import com.jiligulu.app.data.local.entity.BillEntity
import com.jiligulu.app.data.local.entity.BillSource
import com.jiligulu.app.data.local.entity.BillType
import com.jiligulu.app.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MemoriesTest {
    @Test fun billAndPhotoSaveTogetherWithoutChangingLedgerIdentity() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.billDao()
            val original = BillEntity(amountFen = 1200, type = BillType.EXPENSE, categoryId = 19, detail = "面", timestamp = 120,
                source = BillSource.AI_CHAT, rawText = "午饭吃面12", note = "原备注")
            val id = dao.insert(original)
            assertEquals(1, dao.updateWithMemory(id, 1500, "生日面", 150, "和朋友一起", "/private/photo.jpg"))
            val saved = dao.getById(id)!!
            assertEquals(original.copy(id = id, amountFen = 1500, detail = "生日面", timestamp = 150, note = "和朋友一起", photoUri = "/private/photo.jpg"), saved)
            assertEquals(listOf(saved), dao.observePhotoMemories().first())
        } finally { db.close() }
    }

    @Test fun attachingPhotoCannotReviveTrashAndTrashIsHiddenFromAlbum() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.billDao()
            val id = dao.insert(BillEntity(amountFen = 1200, type = BillType.EXPENSE, categoryId = 1, detail = "面", timestamp = 120, photoUri = "old.jpg"))
            dao.moveToTrash(id, 200)
            assertEquals(0, dao.updateWithMemory(id, 900, "改名", 500, "新备注", "new.jpg"))
            assertEquals("old.jpg", dao.getById(id)!!.photoUri)
            assertTrue(dao.observePhotoMemories().first().isEmpty())
        } finally { db.close() }
    }

    @Test fun lastFullWeekUsesCalendarMondayAndExclusiveNextMonday() {
        val zone = ZoneId.of("Asia/Tokyo")
        val range = memoryWeekRange(-1, LocalDate.of(2026, 10, 3), zone)
        assertEquals(LocalDate.of(2026, 9, 21).atStartOfDay(zone).toInstant().toEpochMilli(), range.first)
        assertEquals(LocalDate.of(2026, 9, 28).atStartOfDay(zone).toInstant().toEpochMilli(), range.second)
    }

    @Test fun weekOnMondayStillDefaultsToPreviousCompleteWeek() {
        val zone = ZoneId.of("America/New_York")
        val range = memoryWeekRange(-1, LocalDate.of(2026, 3, 9), zone)
        assertEquals(LocalDate.of(2026, 3, 2).atStartOfDay(zone).toInstant().toEpochMilli(), range.first)
        assertEquals(LocalDate.of(2026, 3, 9).atStartOfDay(zone).toInstant().toEpochMilli(), range.second)
        assertEquals(167L * 60 * 60 * 1000, range.second - range.first)
    }

    @Test fun weekTotalsAndPreferencesUseRealBillsAndKeepIncomeSeparate() {
        val categories = listOf(CategoryEntity(id = 1, name = "吃饭", colorHue = 1f, colorIndex = 1), CategoryEntity(id = 2, name = "交通", colorHue = 2f, colorIndex = 2))
        val rows = listOf(BillEntity(amountFen = 1200, type = BillType.EXPENSE, categoryId = 1, timestamp = 1),
            BillEntity(amountFen = 300, type = BillType.EXPENSE, categoryId = 2, timestamp = 2),
            BillEntity(amountFen = 50000, type = BillType.INCOME, categoryId = 2, timestamp = 3))
        val summary = summarizeMemoryWeek(rows, categories, "日期")
        assertEquals(3, summary.billsCount)
        assertEquals(1500L, summary.expenseFen)
        assertEquals(50000L, summary.incomeFen)
        assertEquals(listOf("吃饭" to 1200L, "交通" to 300L), summary.categories)
    }

    @Test fun pickedPhotoIsCopiedIntoPrivateStorageAndRemainsAfterSourceDisappears() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.cacheDir, "photo-import-test.png")
        val image = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        source.outputStream().use { assertTrue("Native PNG encoder must write a real photo fixture", image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
        val checkBounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.inputStream().use { android.graphics.BitmapFactory.decodeStream(it, null, checkBounds) }
        assertEquals(160, checkBounds.outWidth)
        assertEquals(90, checkBounds.outHeight)
        assertNotNull("Source bitmap must decode before testing URI import", MemoryFiles.readBitmap(context, source.absolutePath))
        val saved = MemoryFiles.importPhoto(context, Uri.fromFile(source))
        source.delete()
        try {
            assertTrue(File(saved).isFile)
            assertTrue(File(saved).canonicalPath.startsWith(File(context.filesDir, "life-memories/photos").canonicalPath))
            val decoded = MemoryFiles.readBitmap(context, saved)!!
            assertEquals(160, decoded.width); assertEquals(90, decoded.height); decoded.recycle()
        } finally { File(saved).delete() }
    }

    @Test fun nativeLifePosterRendersBundledChineseAndSyntheticPhoto() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.cacheDir, "synthetic-seaside-photo.png")
        val fixture = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(fixture)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(android.graphics.Color.rgb(234, 242, 253))
        paint.color = android.graphics.Color.rgb(255, 219, 150)
        canvas.drawCircle(373f, 59f, 29f, paint)
        paint.color = android.graphics.Color.rgb(154, 208, 219)
        canvas.drawRect(0f, 112f, 480f, 252f, paint)
        paint.color = android.graphics.Color.rgb(248, 229, 199)
        canvas.drawRect(0f, 252f, 480f, 320f, paint)
        paint.color = android.graphics.Color.WHITE
        paint.strokeWidth = 4f
        listOf(155f, 190f, 225f).forEach { y -> canvas.drawLine(38f, y, 430f, y, paint) }
        source.outputStream().use { assertTrue(fixture.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        fixture.recycle()
        var poster: File? = null
        try {
            poster = MemoryPoster.render(context, PosterData(
                title = "把海风收进这一页",
                caption = "和朋友在海边坐了一下午。\n一点阳光，一点海风，一段想记住的小日子。",
                photoPath = source.absolutePath,
                amountFen = 15800,
                dateMillis = LocalDate.of(2026, 10, 3).atStartOfDay(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli()
            ), showAmount = false, stamp = "秋日收藏")
            assertTrue("Renderer must write a real nonempty PNG", poster.isFile && poster.length() > 10_000)
            val bitmap = android.graphics.BitmapFactory.decodeFile(poster.absolutePath)
            assertNotNull("Native renderer output must decode", bitmap)
            assertEquals(1080, bitmap!!.width)
            assertEquals(1440, bitmap.height)
            val colors = mutableSetOf<Int>()
            for (x in 0 until bitmap.width step 37) {
                for (y in 0 until bitmap.height step 37) colors += bitmap.getPixel(x, y)
            }
            assertTrue("Bundled fonts and photo must produce actual artwork", colors.size > 30)
            val output = File("build/reports/ui/life-poster-sample.png")
            output.parentFile!!.mkdirs()
            poster.copyTo(output, overwrite = true)
            bitmap.recycle()
        } finally { poster?.delete(); source.delete() }
    }
}
