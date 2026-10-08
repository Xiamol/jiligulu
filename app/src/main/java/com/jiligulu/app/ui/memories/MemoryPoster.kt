package com.jiligulu.app.ui.memories

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.jiligulu.app.R
import com.jiligulu.app.core.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class PosterData(
    val title: String,
    val caption: String = "",
    val photoPath: String = "",
    val amountFen: Long? = null,
    val dateMillis: Long? = null,
    val week: WeeklyMemory? = null
)

object MemoryPoster {
    private data class ArtworkAssets(val body: Typeface, val emphasis: Typeface, val heading: Typeface, val mascot: Bitmap?)
    @Volatile private var cachedAssets: ArtworkAssets? = null
    private val assetLock = Any()
    private val assetDecodeLock = Any()
    private val fontFileLock = Any()
    private var assetGeneration = 0L
    private val renderLock = Mutex()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")
    fun clearMemoryCache() = synchronized(assetLock) { assetGeneration++; cachedAssets = null }

    private fun assets(context: Context): ArtworkAssets = cachedAssets ?: synchronized(assetDecodeLock) {
        cachedAssets ?: run {
            val generation = synchronized(assetLock) { assetGeneration }
            val resources = context.applicationContext.resources
            // The packaged variable font's default wght is 100. Typeface.create(weight)
            // can keep that hairline axis on Canvas; select the variation explicitly.
            fun font(weight: Int) = posterTypeface(context, weight)
            ArtworkAssets(font(400), font(650), resources.getFont(R.font.zcool_kuaile),
                BitmapFactory.decodeResource(resources, R.drawable.gulu_idle,
                    BitmapFactory.Options().apply { inSampleSize = 3; inScaled = false }))
                .also { created -> synchronized(assetLock) { if (generation == assetGeneration) cachedAssets = created } }
        }
    }

    internal fun posterTypeface(context: Context, weight: Int,
        modern: (android.content.res.Resources, Int) -> Typeface = ::resourceTypeface): Typeface {
        val app = context.applicationContext
        val resources = app.resources
        fun legacyFont(): Typeface {
            val fontFile = synchronized(fontFileLock) {
                val length = resources.openRawResource(R.font.noto_sans_sc).use { it.available().toLong() }
                ensurePosterFontFile(app.cacheDir, length) { resources.openRawResource(R.font.noto_sans_sc) }
            }
            return Typeface.Builder(fontFile).setFontVariationSettings("'wght' $weight").setWeight(weight).build()
        }
        return if (Build.VERSION.SDK_INT >= 29) runCatching { modern(resources, weight) }.getOrElse { legacyFont() }
            else legacyFont()
    }

    @androidx.annotation.RequiresApi(29)
    private fun resourceTypeface(resources: android.content.res.Resources, weight: Int): Typeface {
        // Same explicit variable-font axis, without copying 17.8 MB out of the APK on modern Android.
        val font = android.graphics.fonts.Font.Builder(resources, R.font.noto_sans_sc)
            .setFontVariationSettings("'wght' $weight").setWeight(weight).build()
        return Typeface.CustomFallbackBuilder(android.graphics.fonts.FontFamily.Builder(font).build())
            .setStyle(android.graphics.fonts.FontStyle(weight, android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT))
            .setSystemFallback("sans-serif").build()
    }

    suspend fun render(context: Context, data: PosterData, showAmount: Boolean, stamp: String): File {
        var created: File? = null
        try { return renderLock.withLock { withContext(Dispatchers.Default) {
            val artwork = assets(context)
            val title = textLayout(data.title.ifBlank { "把这一刻收起来" }.take(32), artwork.heading, 60f, ink, 936, 2)
            val caption = textLayout(data.caption.ifBlank { "今天的生活，阿噜替你收好啦。" }.take(120), artwork.body, 38f, ink, 912, 6)
            val headerEnd = 148f + title.height + 30f
            val photo = if (data.photoPath.isBlank()) null else MemoryFiles.readBitmap(context, data.photoPath, 1500)
            var bitmap: Bitmap? = null
            try {
                val categoriesCount = data.week?.categories?.size?.coerceAtMost(5) ?: 0
                val weekBody = 310f + (if (showAmount) 142f else 0f) + max(categoriesCount * 98f, 170f)
                val extraAmount = if (showAmount && data.week == null && data.amountFen != null) 62f else 0f
                val height = when {
                    photo != null && photo.height > photo.width -> 1760
                    photo != null -> 1440
                    data.week != null -> max(1120, (headerEnd + weekBody + caption.height + 246f).roundToInt())
                    else -> max(1280, (headerEnd + 615f + caption.height + extraAmount + 240f).roundToInt())
                }
                val rendered = Bitmap.createBitmap(1080, height, Bitmap.Config.ARGB_8888)
                bitmap = rendered
                val painter = PosterPainter(Canvas(rendered), artwork, height)
                painter.background()
                painter.header(title, if (data.week != null) data.week.rangeLabel else dateText(data.dateMillis))
                val bodyEnd = when {
                    photo != null -> painter.photo(photo, headerEnd, height - caption.height - extraAmount - 220f, stamp)
                    data.week != null -> painter.week(data.week, headerEnd, showAmount, stamp)
                    else -> painter.smallMemory(headerEnd, stamp)
                }
                val captionTop = if (photo != null) bodyEnd + 40f else bodyEnd + 48f
                painter.caption(caption, captionTop, if (showAmount && data.week == null) data.amountFen else null)
                painter.footer()
                val output = MemoryFiles.posterFile(context).also { created = it }
                output.outputStream().use { check(rendered.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                output
            } finally { bitmap?.recycle(); photo?.recycle() }
        } } } catch (failure: Throwable) { created?.delete(); throw failure }
    }

    private val ink = Color.rgb(58, 47, 73)
    private val purple = Color.rgb(106, 80, 165)
    private val muted = Color.rgb(116, 104, 131)
    private val categoryColors = intArrayOf(0xFFAA98DA.toInt(), 0xFFEAAFA1.toInt(), 0xFF9FC7B3.toInt(), 0xFFE7C18C.toInt(), 0xFFB8A8CE.toInt())

    private fun textLayout(text: String, font: Typeface, size: Float, color: Int, width: Int, maxLines: Int): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; typeface = font }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setIncludePad(false)
            .setLineSpacing(8f, 1f).setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
    }

    /** One mutable paint per export, immutable shared font/sprite assets across exports. */
    private class PosterPainter(val canvas: Canvas, val art: ArtworkAssets, val height: Int) {
        private val brush = Paint(Paint.ANTI_ALIAS_FLAG)
        private val type = Paint(Paint.ANTI_ALIAS_FLAG)
        private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        private fun text(value: String, x: Float, y: Float, size: Float = 32f, color: Int = ink,
            font: Typeface = art.body, maxWidth: Float = 936f) {
            type.color = color; type.typeface = font; type.textSize = size
            if (type.measureText(value) > maxWidth) type.textSize = max(24f, size * maxWidth / type.measureText(value))
            canvas.drawText(value, x, y, type)
        }
        private fun right(value: String, x: Float, y: Float, size: Float, color: Int = muted, font: Typeface = art.body) {
            type.color = color; type.typeface = font; type.textSize = size
            canvas.drawText(value, x - type.measureText(value), y, type)
        }
        private fun layout(value: StaticLayout, x: Float, y: Float) {
            val state = canvas.save(); canvas.translate(x, y); value.draw(canvas); canvas.restoreToCount(state)
        }
        private fun rounded(rect: RectF, color: Int, radius: Float = 24f) {
            brush.style = Paint.Style.FILL; brush.color = color
            canvas.drawRoundRect(rect, radius, radius, brush)
        }

        fun background() {
            canvas.drawColor(Color.rgb(253, 248, 240))
            brush.shader = LinearGradient(0f, 0f, 1080f, height.toFloat(),
                intArrayOf(Color.argb(50, 237, 226, 246), Color.TRANSPARENT, Color.argb(55, 255, 220, 205)),
                floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, 1080f, height.toFloat(), brush)
            brush.shader = null
            // Small quiet paper speckles give texture; the story/photo keeps the foreground.
            brush.color = Color.argb(19, 139, 107, 149)
            for (y in 20 until height step 38) for (x in 18 until 1080 step 47) {
                canvas.drawCircle((x + (y % 17)).toFloat(), y.toFloat(), .75f, brush)
            }
            vine(26f, 74f, 1f)
            vine(1030f, height - 235f, -.68f)
        }

        private fun vine(x: Float, y: Float, scale: Float) {
            val state = canvas.save(); canvas.translate(x, y); canvas.scale(scale, scale)
            brush.color = Color.argb(70, 134, 161, 130); brush.style = Paint.Style.STROKE; brush.strokeWidth = 2.2f
            val stem = Path().apply { moveTo(0f, 0f); cubicTo(22f, 45f, -11f, 92f, 7f, 145f) }
            canvas.drawPath(stem, brush); brush.style = Paint.Style.FILL
            for (i in 0..4) {
                brush.color = Color.argb(65 + i * 6, 158, 131, 189)
                canvas.drawOval(RectF(-9f + i % 2 * 10, 31f + i * 20, 6f + i % 2 * 10, 58f + i * 20), brush)
            }
            brush.color = Color.argb(73, 129, 161, 131)
            canvas.drawOval(RectF(5f, 5f, 23f, 16f), brush)
            canvas.restoreToCount(state)
        }

        fun header(title: StaticLayout, date: String) {
            text("阿噜的生活纪念", 72f, 95f, 28f, purple, art.emphasis)
            right(date, 1008f, 95f, 27f, muted)
            brush.color = Color.rgb(223, 211, 228); brush.strokeWidth = 2f
            canvas.drawLine(72f, 121f, 1008f, 121f, brush)
            layout(title, 72f, 148f)
        }

        private fun stamp(label: String, x: Float, y: Float, width: Float = 188f) {
            val state = canvas.save(); canvas.rotate(-4f, x + width / 2, y + 29f)
            rounded(RectF(x, y, x + width, y + 58f), Color.rgb(236, 227, 246), 18f)
            text(label, x + 18f, y + 39f, 30f, purple, art.heading, width - 36f)
            canvas.restoreToCount(state)
        }

        fun photo(bitmap: Bitmap, top: Float, bottom: Float, stamp: String): Float {
            val frame = RectF(72f, top + 16f, 1008f, bottom)
            brush.color = Color.WHITE; brush.style = Paint.Style.FILL
            brush.setShadowLayer(15f, 0f, 7f, Color.argb(23, 77, 47, 85))
            canvas.drawRoundRect(frame, 24f, 24f, brush); brush.clearShadowLayer()
            val space = RectF(frame.left + 22f, frame.top + 22f, frame.right - 22f, frame.bottom - 80f)
            rounded(space, Color.rgb(241, 235, 242), 14f)
            val factor = min(space.width() / bitmap.width, space.height() / bitmap.height)
            val w = bitmap.width * factor; val h = bitmap.height * factor
            val fitted = RectF(space.centerX() - w / 2, space.centerY() - h / 2, space.centerX() + w / 2, space.centerY() + h / 2)
            val state = canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(space, 14f, 14f, Path.Direction.CW) })
            // Full source image is preserved. A portrait is never forced into a landscape crop.
            canvas.drawBitmap(bitmap, null, fitted, image)
            canvas.restoreToCount(state)
            stamp(stamp, frame.left + 26f, frame.bottom - 65f)
            right("把这一刻，好好留下 ♡", frame.right - 27f, frame.bottom - 29f, 25f)
            return bottom
        }

        fun week(week: WeeklyMemory, top: Float, showAmount: Boolean, stamp: String): Float {
            val start = top + 25f
            text("这一周，留下了", 72f, start + 28f, 31f, muted)
            text(week.billsCount.toString(), 72f, start + 139f, 105f, purple, art.emphasis, 280f)
            text("笔生活足迹", 263f, start + 127f, 35f, ink)
            stamp(stamp, 798f, start + 75f, 210f)
            text("${week.categories.size} 个消费分类，把日子记得具体。", 72f, start + 180f, 30f, muted)
            var chart = start + 220f
            if (showAmount) {
                rounded(RectF(72f, chart, 1008f, chart + 114f), Color.rgb(245, 238, 246), 24f)
                text("本周支出", 94f, chart + 34f, 25f, muted)
                text("¥${Formatters.fenToYuanText(week.expenseFen)}", 94f, chart + 88f, 43f, Color.rgb(200, 110, 99), art.emphasis, 412f)
                text("本周收入", 556f, chart + 34f, 25f, muted)
                text("¥${Formatters.fenToYuanText(week.incomeFen)}", 556f, chart + 88f, 43f, Color.rgb(75, 143, 113), art.emphasis, 420f)
                chart += 142f
            }
            text("本周的小偏爱", 72f, chart + 33f, 35f, purple, art.heading)
            right("消费分布", 1008f, chart + 31f, 25f)
            chart += 65f
            val categories = week.categories.take(5)
            val maximum = categories.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1L
            categories.forEachIndexed { index, category ->
                val y = chart + index * 98f
                brush.color = categoryColors[index]; canvas.drawCircle(93f, y + 20f, 20f, brush)
                text((index + 1).toString(), 84f, y + 31f, 26f, Color.WHITE, art.emphasis)
                layout(textLayout(category.first, art.body, 33f, ink, 460, 1), 129f, y - 3f)
                val share = if (week.expenseFen > 0) (category.second.toDouble() / week.expenseFen * 100).roundToInt() else 0
                right("$share%", 1008f, y + 30f, 31f, purple, art.emphasis)
                rounded(RectF(130f, y + 50f, 1008f, y + 72f), Color.rgb(237, 229, 239), 11f)
                val width = (878f * category.second.toDouble() / maximum).toFloat().coerceIn(16f, 878f)
                rounded(RectF(130f, y + 50f, 130f + width, y + 72f), categoryColors[index], 11f)
            }
            if (categories.isEmpty()) {
                text("空白的一周，也值得温柔收好。", 72f, chart + 62f, 36f, ink)
                text("慢慢过日子，下一页再见。", 72f, chart + 119f, 30f, muted)
            }
            return chart + max(categories.size * 98f, 170f)
        }

        fun smallMemory(top: Float, stamp: String): Float {
            val center = top + 225f
            brush.shader = RadialGradient(540f, center, 300f, Color.rgb(237, 225, 247), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(540f, center, 300f, brush); brush.shader = null
            art.mascot?.let { canvas.drawBitmap(it, null, RectF(332f, center - 194f, 748f, center + 222f), image) }
            stamp(stamp, 427f, center + 206f, 226f)
            text("小小的一件事，也有自己的光。", 200f, center + 339f, 37f, purple, art.heading, 720f)
            return center + 390f
        }

        fun caption(value: StaticLayout, top: Float, amountFen: Long?) {
            var y = top
            if (amountFen != null) {
                text("¥${Formatters.fenToYuanText(amountFen)}", 84f, y + 40f, 41f, purple, art.emphasis)
                y += 62f
            }
            brush.color = Color.rgb(199, 177, 218)
            canvas.drawRoundRect(RectF(72f, y + 5f, 78f, y + min(value.height.toFloat(), 150f)), 3f, 3f, brush)
            layout(value, 99f, y)
        }

        fun footer() {
            val line = height - 137f
            brush.color = Color.rgb(225, 213, 226); brush.strokeWidth = 2f
            canvas.drawLine(72f, line, 808f, line, brush)
            text("叽里咕噜 · 把生活慢慢收好", 72f, height - 81f, 27f, purple, art.heading, 730f)
            text("小小的账单，大大的生活 ♡", 72f, height - 39f, 24f, muted)
            art.mascot?.let { canvas.drawBitmap(it, null, RectF(831f, height - 175f, 1012f, height + 6f), image) }
        }
    }
    private fun dateText(millis: Long?) = Instant.ofEpochMilli(millis ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault()).format(dateFormatter)

    suspend fun saveGallery(context: Context, file: File): Uri = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= 29)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "阿噜生活纪念-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/叽里咕噜")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("相册暂时没有准备好。")
        try {
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("无法保存到相册。")
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0); resolver.update(uri, values, null, null)
            uri
        } catch (failure: Throwable) { resolver.delete(uri, null, null); throw failure }
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.memories", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "阿噜的生活纪念", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "把这一页分享出去"))
    }
}
