package com.jiligulu.app.ui.capture

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import com.jiligulu.app.R
import kotlin.math.min

/**
 * Transparent overlay material: the real window shows through a light violet glass shell.
 * Cached lighting supplies depth; GlassOverlayHost uses platform blur without raw pixel capture.
 * The service owns touch/drag placement; pressing only transforms drawing inside this view.
 */
class GlassFloatingBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {
    private val glassBounds = RectF()
    private val innerBounds = RectF()
    private val glassPath = Path()
    private val topGlint = Path()
    private val lowerGlint = Path()
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val lowerGlintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var softShadow: Bitmap? = null
    private var radius = 0f
    private var side = 0f
    private var pressure = 0f
    private var glassPressed = false
    private var pressAnimator: ValueAnimator? = null
    private val lightMatrix=Matrix()
    private var lightX=.3f
    private var lightY=.3f

    init {
        background = null
        // The default launcher asset contains a cream square; this sprite has true alpha.
        setImageResource(R.drawable.gulu_idle)
        scaleType = ScaleType.FIT_CENTER
        imageAlpha = 110
        // Shadow is cached below, so an elevation assigned by an existing service cannot
        // introduce a second rectangular platform shadow around the transparent corners.
        outlineProvider = null
        clipToOutline = false
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        side = min(w, h).toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val half = side * .42f
        radius = side * .28f
        glassBounds.set(cx - half, cy - half, cx + half, cy + half)
        innerBounds.set(glassBounds)
        innerBounds.inset(side * .018f, side * .018f)
        glassPath.reset()
        glassPath.addRoundRect(glassBounds, radius, radius, Path.Direction.CW)

        // All shader/array/path allocations happen on resize, never on idle or per draw.
        glassPaint.shader = LinearGradient(glassBounds.left, glassBounds.top,
            glassBounds.right, glassBounds.bottom,
            intArrayOf(Color.argb(25, 255, 255, 255), Color.argb(9, 246, 243, 255), Color.argb(22, 187, 168, 232)),
            floatArrayOf(0f, .53f, 1f), Shader.TileMode.CLAMP)
        glowPaint.shader = RadialGradient(cx - side * .19f, cy - side * .22f, side * .63f,
            intArrayOf(Color.argb(46, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        rimPaint.strokeWidth = (side * .044f).coerceAtLeast(1.8f)
        rimPaint.shader = SweepGradient(cx, cy,
            intArrayOf(Color.argb(232, 255, 255, 255), Color.argb(74, 59, 46, 93),
                Color.argb(180, 248, 241, 255), Color.argb(250, 255, 255, 255),
                Color.argb(112, 179, 189, 234), Color.argb(232, 255, 255, 255)),
            floatArrayOf(0f, .25f, .5f, .68f, .84f, 1f))
        lightMatrix.setRotate((lightX-.5f)*110f+(lightY-.5f)*45f,cx,cy)
        rimPaint.shader?.setLocalMatrix(lightMatrix)
        innerRimPaint.strokeWidth = (side * .012f).coerceAtLeast(.8f)
        innerRimPaint.color = Color.argb(70, 77, 65, 110)

        topGlint.reset()
        topGlint.moveTo(glassBounds.left + side * .11f, glassBounds.top + side * .064f)
        topGlint.cubicTo(glassBounds.left + side * .24f, glassBounds.top + side * .014f,
            cx + side * .11f, glassBounds.top + side * .012f,
            glassBounds.right - side * .14f, glassBounds.top + side * .048f)
        glintPaint.strokeWidth = (side * .015f).coerceAtLeast(1f)
        glintPaint.shader = LinearGradient(glassBounds.left, 0f, glassBounds.right, 0f,
            intArrayOf(Color.argb(215, 255, 255, 255), Color.argb(26, 255, 255, 255)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        lowerGlint.reset()
        lowerGlint.moveTo(glassBounds.right - side * .047f, glassBounds.bottom - side * .22f)
        lowerGlint.quadTo(glassBounds.right - side * .04f, glassBounds.bottom - side * .045f,
            glassBounds.right - side * .21f, glassBounds.bottom - side * .04f)
        lowerGlintPaint.strokeWidth = (side * .012f).coerceAtLeast(.8f)
        lowerGlintPaint.color = Color.argb(114, 248, 240, 255)

        // A tiny software bitmap makes a soft rounded shadow work on every supported
        // renderer. Only the current view size is cached; no screen content is involved.
        val shadow = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val shadowCanvas = Canvas(shadow)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(12, 121, 94, 176)
            setShadowLayer(side * .045f, 0f, side * .021f, Color.argb(47, 54, 33, 89))
        }
        shadowCanvas.drawRoundRect(glassBounds, radius, radius, shadowPaint)
        softShadow = shadow
    }

    override fun onDraw(canvas: Canvas) {
        if (side <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val body = canvas.save()
        canvas.scale(1f - pressure * .07f,1f + pressure * .018f,cx,cy)
        canvas.translate(0f, pressure * side * .011f)
        softShadow?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
        canvas.drawRoundRect(glassBounds, radius, radius, glassPaint)
        canvas.drawPath(glassPath, glowPaint)
        canvas.drawRoundRect(innerBounds, radius - side * .018f, radius - side * .018f, innerRimPaint)

        val artwork = canvas.save()
        canvas.clipPath(glassPath)
        // Keep the eyes, sprout and purple silhouette recognizable on light/dark surfaces.
        canvas.scale(.75f, .75f, cx, cy)
        super.onDraw(canvas)
        canvas.restoreToCount(artwork)

        canvas.drawRoundRect(glassBounds, radius, radius, rimPaint)
        val reflections = canvas.save()
        canvas.clipPath(glassPath)
        canvas.translate(pressure * side * .012f, -pressure * side * .014f)
        glintPaint.alpha = (224 + pressure * 30).toInt().coerceIn(0, 255)
        canvas.drawPath(topGlint, glintPaint)
        canvas.drawPath(lowerGlint, lowerGlintPaint)
        canvas.restoreToCount(reflections)
        canvas.restoreToCount(body)
    }

    /** Position-driven specular bands; no pixel sampling or continuously running animation. */
    fun setGlassLight(x:Float,y:Float) {
        val nextX=x.coerceIn(0f,1f);val nextY=y.coerceIn(0f,1f)
        if(kotlin.math.abs(nextX-lightX)+kotlin.math.abs(nextY-lightY)<.012f) return
        lightX=nextX;lightY=nextY
        lightMatrix.setRotate((lightX-.5f)*110f+(lightY-.5f)*45f,width/2f,height/2f)
        rimPaint.shader?.setLocalMatrix(lightMatrix)
        invalidate()
    }

    /** Call on DOWN, and release on UP/CANCEL/configuration changes in the service. */
    fun setGlassPressed(pressed: Boolean) {
        if (glassPressed == pressed) return
        glassPressed = pressed
        super.setPressed(pressed)
        pressAnimator?.cancel()
        val target = if (pressed) 1f else 0f
        if (!isShown || !ValueAnimator.areAnimatorsEnabled()) {
            pressure = target
            invalidate()
            return
        }
        pressAnimator = ValueAnimator.ofFloat(pressure, target).apply {
            duration = if (pressed) 100L else 240L
            interpolator = if (pressed) DecelerateInterpolator() else OvershootInterpolator(1.45f)
            addUpdateListener {
                pressure = (it.animatedValue as Float).coerceIn(-.2f, 1f)
                invalidate()
            }
            start()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) resetPress()
    }

    override fun onDetachedFromWindow() {
        resetPress()
        super.onDetachedFromWindow()
    }

    private fun resetPress() {
        pressAnimator?.cancel()
        pressAnimator = null
        pressure = 0f
        glassPressed = false
        super.setPressed(false)
    }
}
