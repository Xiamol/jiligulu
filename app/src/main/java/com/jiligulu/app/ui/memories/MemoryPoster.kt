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
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class PosterData(
    val title: String,
    val caption: String = "",
    val photoPath: String = "",
    val amountFen: Long? = null,
    val dateMillis: Long? = null,
    val week: WeeklyMemory? = null
)

object MemoryPoster {
    suspend fun render(context: Context, data: PosterData, showAmount: Boolean, stamp: String): File {
        var created: File? = null
        try { return withContext(Dispatchers.Default) {
        // Exported Chinese always uses bundled fonts rather than the phone's themed/pinyin font.
        val bodyFont = context.resources.getFont(R.font.noto_sans_sc)
        val bodyTypeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(bodyFont, 400, false) else Typeface.create(bodyFont, Typeface.NORMAL)
        val headingTypeface = context.resources.getFont(R.font.zcool_kuaile)
        fun drawText(canvas: Canvas, text: String, x: Float, baseline: Float, size: Float, color: Int) =
            MemoryPoster.drawText(canvas, text, x, baseline, size, color, bodyTypeface)
        fun drawWrapped(canvas: Canvas, text: String, x: Float, y: Float, width: Int, size: Float,
            color: Int, bold: Boolean, maxLines: Int, alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) =
            MemoryPoster.drawWrapped(canvas, text, x, y, width, size, color,
                if (bold) headingTypeface else bodyTypeface, maxLines, alignment)
        val bitmap = Bitmap.createBitmap(1080, 1440, Bitmap.Config.ARGB_8888)
        try {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cream = Color.rgb(255, 251, 242)
        val lavender = Color.rgb(120, 99, 185)
        val ink = Color.rgb(57, 49, 69)
        canvas.drawColor(cream)
        paint.color = Color.rgb(241, 233, 252)
        canvas.drawCircle(990f, 130f, 220f, paint)
        paint.color = Color.rgb(255, 234, 226)
        canvas.drawCircle(44f, 1410f, 210f, paint)
        paint.color = Color.rgb(229, 239, 222)
        canvas.drawCircle(1080f, 1290f, 160f, paint)
        paint.color = Color.rgb(222, 210, 243)
        for (i in 0..5) { canvas.drawCircle(65f + i * 28, 70f, 4f, paint) }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
        paint.pathEffect = DashPathEffect(floatArrayOf(10f, 12f), 0f)
        canvas.drawRoundRect(RectF(35f, 32f, 1045f, 1408f), 38f, 38f, paint)
        paint.style = Paint.Style.FILL; paint.pathEffect = null
        drawText(canvas, "阿噜的生活纪念册", 78f, 124f, 27f, lavender)
        drawWrapped(canvas, data.title.ifBlank { "把这一刻收起来" }.take(32), 78f, 172f, 915, 57f, ink, true, 2)

        val photo = if (data.photoPath.isBlank()) null else MemoryFiles.readBitmap(context, data.photoPath, 1200)
        if (photo != null) {
            paint.color = Color.WHITE
            paint.setShadowLayer(14f, 0f, 6f, Color.argb(26, 74, 48, 95))
            canvas.drawRoundRect(RectF(80f, 350f, 1000f, 980f), 16f, 16f, paint)
            paint.clearShadowLayer()
            val rect = RectF(105f, 375f, 975f, 905f)
            canvas.save()
            val clip = Path().apply { addRoundRect(rect, 10f, 10f, Path.Direction.CW) }
            canvas.clipPath(clip)
            val targetRatio = rect.width() / rect.height()
            val sourceRatio = photo.width.toFloat() / photo.height
            val source = if (sourceRatio > targetRatio) {
                val width = (photo.height * targetRatio).toInt()
                Rect((photo.width - width) / 2, 0, (photo.width + width) / 2, photo.height)
            } else {
                val height = (photo.width / targetRatio).toInt()
                Rect(0, (photo.height - height) / 2, photo.width, (photo.height + height) / 2)
            }
            canvas.drawBitmap(photo, source, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restore(); photo.recycle()
            drawText(canvas, "生活的小票根  /  ${dateText(data.dateMillis)}", 126f, 950f, 25f, lavender)
            drawTape(canvas, paint, 398f, 328f)
        } else if (data.week != null) {
            val week = data.week
            paint.color = Color.WHITE
            canvas.drawRoundRect(RectF(80f, 345f, 1000f, 980f), 32f, 32f, paint)
            drawTape(canvas, paint, 398f, 326f)
            drawText(canvas, week.rangeLabel, 124f, 425f, 32f, lavender)
            drawText(canvas, "这一周，留下了 ${week.billsCount} 笔生活足迹", 124f, 502f, 35f, ink)
            if (showAmount) {
                drawText(canvas, "支出  ¥${Formatters.fenToYuanText(week.expenseFen)}", 124f, 581f, 35f, Color.rgb(204, 114, 100))
                drawText(canvas, "收入  ¥${Formatters.fenToYuanText(week.incomeFen)}", 585f, 581f, 35f, Color.rgb(76, 149, 116))
            } else drawText(canvas, "认真生活，慢慢记录", 124f, 581f, 34f, lavender)
            drawText(canvas, "本周的小偏爱", 124f, 675f, 28f, lavender)
            week.categories.take(3).forEachIndexed { i, category ->
                val y = 725f + i * 70f
                drawText(canvas, "${i + 1}  ${category.first}", 134f, y, 32f, ink)
                val maximum = week.categories.firstOrNull()?.second?.coerceAtLeast(1) ?: 1
                paint.color = intArrayOf(0xFFC6B6ED.toInt(), 0xFFF1C3B7.toInt(), 0xFFBDDCC9.toInt())[i]
                canvas.drawRoundRect(RectF(535f, y - 25f, 535f + 330f * category.second / maximum, y - 3f), 11f, 11f, paint)
            }
            if (week.categories.isEmpty()) drawText(canvas, "空白的一周，也值得温柔收好。", 124f, 760f, 32f, ink)
        } else {
            paint.color = Color.WHITE
            canvas.drawRoundRect(RectF(80f, 355f, 1000f, 985f), 36f, 36f, paint)
            drawTape(canvas, paint, 398f, 330f)
            drawText(canvas, "✦", 500f, 566f, 90f, lavender)
            drawWrapped(canvas, "小小的一件事\n也有大大的意义", 180f, 650f, 720, 49f, ink, false, 3, Layout.Alignment.ALIGN_CENTER)
            drawText(canvas, dateText(data.dateMillis), 430f, 913f, 27f, lavender)
        }
        if (showAmount && data.week == null && data.amountFen != null) drawText(canvas, "¥${Formatters.fenToYuanText(data.amountFen)}", 80f, 1052f, 36f, lavender)
        drawWrapped(canvas, data.caption.ifBlank { "今天的生活，阿噜替你收好啦。" }.take(120), 84f, 1090f, 760, 33f, ink, false, 4)
        paint.color = Color.rgb(241, 229, 205)
        canvas.save(); canvas.rotate(-9f, 905f, 1070f)
        canvas.drawRoundRect(RectF(822f, 998f, 990f, 1142f), 14f, 14f, paint)
        drawWrapped(canvas, stamp, 834f, 1032f, 144, 30f, lavender, true, 2, Layout.Alignment.ALIGN_CENTER)
        canvas.restore()
        val mascot = BitmapFactory.decodeResource(context.resources, R.drawable.gulu_idle, BitmapFactory.Options().apply { inSampleSize = 3; inScaled = false })
        mascot?.let { canvas.drawBitmap(it, null, RectF(856f, 1190f, 1010f, 1334f), paint); it.recycle() }
        drawText(canvas, "小小的账单，大大的生活  ♡", 80f, 1340f, 25f, lavender)
        val output = MemoryFiles.posterFile(context).also { created = it }
        output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        output
        } finally { bitmap.recycle() }
        } } catch (failure: Throwable) { created?.delete(); throw failure }
    }

    private fun drawTape(canvas: Canvas, paint: Paint, x: Float, y: Float) {
        canvas.save(); canvas.rotate(-5f, x + 140f, y + 25f)
        paint.color = Color.argb(205, 218, 204, 245)
        canvas.drawRoundRect(RectF(x, y, x + 284f, y + 53f), 4f, 4f, paint)
        canvas.restore()
    }
    private fun drawText(canvas: Canvas, text: String, x: Float, baseline: Float, size: Float, color: Int, font: Typeface) {
        canvas.drawText(text, x, baseline, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; typeface = font })
    }
    private fun drawWrapped(canvas: Canvas, text: String, x: Float, y: Float, width: Int, size: Float, color: Int, font: Typeface, maxLines: Int, alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; typeface = font }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(alignment)
            .setLineSpacing(8f, 1f).setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        canvas.save(); canvas.translate(x, y); layout.draw(canvas); canvas.restore()
    }
    private fun dateText(millis: Long?) = Instant.ofEpochMilli(millis ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))

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
