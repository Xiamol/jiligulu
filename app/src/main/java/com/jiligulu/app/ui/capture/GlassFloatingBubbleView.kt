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
 * Cached lighting supplies depth; own-window fresh pixels and a stable global material stay separate.
 * The service owns touch/drag placement; pressing only transforms drawing inside this view.
 */
class GlassFloatingBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {
    private val glassBounds = RectF()
    private val rimBounds = RectF()
    private val glassPath = Path()
    private val topGlint = Path()
    private val lowerGlint = Path()
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
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
    private val lightX=.3f
    private val lightY=.3f
    private val backdropPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var lens:GlassLensShader?=null
    private var backdrop:Bitmap?=null
    private var dragging=false
    internal val canSampleOwnBackdrop get() = isAttachedToWindow && isShown
    private val backdropRefreshTask=Runnable {refreshBackdrop()}

    init {
        background = null
        // The default launcher asset contains a cream square; this sprite has true alpha.
        setImageResource(R.drawable.gulu_idle)
        scaleType = ScaleType.FIT_CENTER
        imageAlpha = 170
        // Shadow is cached below, so an elevation assigned by an existing service cannot
        // introduce a second rectangular platform shadow around the transparent corners.
        outlineProvider = null
        clipToOutline = false
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clearBackdrop()
        if (w <= 0 || h <= 0) return
        side = min(w, h).toFloat()
        val cx = w / 2f
        val cy = h / 2f
        // The shell and AGSL lens share one outline, without a second transparent inset.
        radius = GlassBubbleGeometry.cornerRadius(w, h)
        glassBounds.set(0f, 0f, w.toFloat(), h.toFloat())
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
        rimPaint.strokeWidth = (side * .007f).coerceAtLeast(.8f)
        rimBounds.set(glassBounds)
        rimBounds.inset(rimPaint.strokeWidth / 2f, rimPaint.strokeWidth / 2f)
        rimPaint.shader = SweepGradient(cx, cy,
            intArrayOf(Color.argb(170, 255, 255, 255), Color.argb(54, 59, 46, 93),
                Color.argb(94, 248, 241, 255), Color.argb(185, 255, 255, 255),
                Color.argb(76, 179, 189, 234), Color.argb(170, 255, 255, 255)),
            floatArrayOf(0f, .25f, .5f, .68f, .84f, 1f))
        lightMatrix.setRotate((lightX-.5f)*110f+(lightY-.5f)*45f,cx,cy)
        rimPaint.shader?.setLocalMatrix(lightMatrix)

        topGlint.reset()
        topGlint.moveTo(glassBounds.left + side * .11f, glassBounds.top + side * .064f)
        topGlint.cubicTo(glassBounds.left + side * .24f, glassBounds.top + side * .014f,
            cx + side * .11f, glassBounds.top + side * .012f,
            glassBounds.right - side * .14f, glassBounds.top + side * .048f)
        glintPaint.strokeWidth = (side * .006f).coerceAtLeast(.7f)
        glintPaint.shader = LinearGradient(glassBounds.left, 0f, glassBounds.right, 0f,
            intArrayOf(Color.argb(215, 255, 255, 255), Color.argb(26, 255, 255, 255)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        lowerGlint.reset()
        lowerGlint.moveTo(glassBounds.right - side * .047f, glassBounds.bottom - side * .22f)
        lowerGlint.quadTo(glassBounds.right - side * .04f, glassBounds.bottom - side * .045f,
            glassBounds.right - side * .21f, glassBounds.bottom - side * .04f)
        lowerGlintPaint.strokeWidth = (side * .006f).coerceAtLeast(.7f)
        lowerGlintPaint.color = Color.argb(114, 248, 240, 255)

        // A tiny software bitmap makes a soft rounded shadow work on every supported
        // renderer. Only the current view size is cached; no screen content is involved.
        val shadow = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val shadowCanvas = Canvas(shadow)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(12, 121, 94, 176)
            setShadowLayer(side * .027f, 0f, side * .015f, Color.argb(24, 54, 33, 89))
        }
        shadowCanvas.drawRoundRect(glassBounds, radius, radius, shadowPaint)
        softShadow = shadow
        post(backdropRefreshTask)
    }

    override fun onDraw(canvas: Canvas) {
        if (side <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val body = canvas.save()
        // Keep one stable shell while the sprite flexes inside, avoiding a press halo.
        softShadow?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
        if (!AppGlassBackdrop.available() || (backdrop!=null&&!AppGlassBackdrop.matchesCurrentContent(backdrop))) clearOwnBackdrop()
        AppGlassBackdrop.cachedFor(this)?.let { bindOwnBackdrop(it.bitmap,it.offsetX,it.offsetY, redraw = false) }
        if(backdropPaint.shader!=null) {
            backdropPaint.alpha=255
            canvas.drawPath(glassPath,backdropPaint)
        }
        canvas.drawRoundRect(glassBounds, radius, radius, glassPaint)
        canvas.drawPath(glassPath, glowPaint)

        val artwork = canvas.save()
        canvas.clipPath(glassPath)
        // Keep the eyes, sprout and purple silhouette recognizable on light/dark surfaces.
        canvas.scale(.88f - pressure * .018f, .88f + pressure * .014f, cx, cy)
        canvas.translate(0f, pressure * side * .009f)
        super.onDraw(canvas)
        canvas.restoreToCount(artwork)

        val rimRadius = (radius - rimPaint.strokeWidth / 2f).coerceAtLeast(0f)
        canvas.drawRoundRect(rimBounds, rimRadius, rimRadius, rimPaint)
        val reflections = canvas.save()
        canvas.clipPath(glassPath)
        glintPaint.alpha = 224
        canvas.drawPath(topGlint, glintPaint)
        canvas.drawPath(lowerGlint, lowerGlintPaint)
        canvas.restoreToCount(reflections)
        canvas.restoreToCount(body)
    }

    /** Moving reprojects the valid crop before sampling; the shell and opacity remain stable. */
    fun setGlassDragging(value: Boolean) {
        if(dragging==value) return
        dragging=value
        refreshBackdropAfterMove()
    }
    fun refreshBackdropAfterMove() {
        if(android.os.Build.VERSION.SDK_INT<33 || !isAttachedToWindow) return
        removeCallbacks(backdropRefreshTask)
        postOnAnimation(backdropRefreshTask)
    }

    fun refreshBackdrop() {
        if(android.os.Build.VERSION.SDK_INT<33 || !canSampleOwnBackdrop) return
        if (!AppGlassBackdrop.available()) { clearBackdrop(); return }
        AppGlassBackdrop.cachedFor(this)?.let { bindOwnBackdrop(it.bitmap,it.offsetX,it.offsetY) }
        AppGlassBackdrop.copyBehind(this) {bitmap,x,y ->
            if(!canSampleOwnBackdrop) return@copyBehind
            if(bitmap==null || !AppGlassBackdrop.available()) clearOwnBackdrop()
            else bindOwnBackdrop(bitmap,x,y)
        }
    }
    private fun bindOwnBackdrop(bitmap:Bitmap,x:Float,y:Float, redraw:Boolean=true) {
        backdrop=bitmap
        runCatching {
                val next=lens ?: GlassLensShader().also {lens=it;android.util.Log.d("GlassLens","Own-window refraction initialized")}
                next.bind(bitmap,x,y,width,height,lightX,lightY)
                backdropPaint.shader=next.shader
            }.onFailure {backdropPaint.shader=null;backdrop=null;android.util.Log.w("GlassLens","Shader unavailable",it)}
        if(redraw) invalidate()
    }

    fun clearBackdrop() {
        val changed=backdrop!=null||backdropPaint.shader!=null
        backdrop=null;backdropPaint.shader=null
        if(changed)invalidate()
    }
    internal fun clearOwnBackdrop() {if(backdrop!=null)clearBackdrop()}
    internal fun releaseOwnBackdrop() {clearBackdrop();lens=null}
    internal fun hasOwnBackdrop(bitmap:Bitmap?)=bitmap!=null&&backdrop===bitmap&&backdropPaint.shader!=null
    override fun onAttachedToWindow() {super.onAttachedToWindow();AppGlassBackdrop.watch(this);postDelayed(backdropRefreshTask,100)}

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
        if (visibility != VISIBLE) {
            removeCallbacks(backdropRefreshTask);resetPress()
            AppGlassBackdrop.suspendForDrag(this);clearBackdrop()
        }
        else if(isAttachedToWindow) refreshBackdropAfterMove()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if(visibility!=VISIBLE)clearBackdrop() else if(isAttachedToWindow)refreshBackdropAfterMove()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(backdropRefreshTask)
        AppGlassBackdrop.unwatch(this)
        resetPress()
        clearBackdrop();dragging=false;lens=null
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
