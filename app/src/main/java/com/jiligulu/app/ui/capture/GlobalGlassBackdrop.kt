package com.jiligulu.app.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

internal enum class GlobalGlassPhase { OFF, STARTING, ACTIVE, PAUSED }
internal data class GlobalGlassState(val authorizedThisSession: Boolean = false,
    val phase: GlobalGlassPhase = GlobalGlassPhase.OFF, val detail: String = "全局边缘折射未启动")

/** Main-thread, memory-only ROI handoff. This object never requests permission or starts capture. */
internal object GlobalGlassBackdrop {
    private val mutableState = MutableStateFlow(GlobalGlassState())
    val state = mutableState.asStateFlow()
    private var watched = WeakReference<GlassFloatingBubbleView>(null)
    private val location = IntArray(2)
    private var target: GlassRect? = null
    private var changedAt = 0L
    private var interacting = false
    private var frameAt = 0L
    private var frameRegion: GlassSampleRegion? = null
    private var frame: Bitmap? = null
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var nextBuffer = 0
    private var lens: GlobalEdgeLens? = null
    var rendererUnavailable = false
        private set

    fun watch(view: GlassFloatingBubbleView) { watched = WeakReference(view); refreshTarget() }
    fun unwatch(view: GlassFloatingBubbleView) {
        if (watched.get() === view) { clearFrame(); watched.clear(); target = null; interacting = false }
    }
    fun interaction(pressed: Boolean) {
        interacting = pressed
        changedAt = SystemClock.uptimeMillis()
        clearFrame()
        ScreenCaptureService.refreshGlassEnvironment()
    }
    fun refreshTarget() {
        val view = watched.get()
        val next = if (view != null && view.isAttachedToWindow && view.isShown && view.width > 0 && view.height > 0) {
            view.getLocationOnScreen(location)
            GlassRect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        } else null
        if (next != target) { target = next; changedAt = SystemClock.uptimeMillis(); clearFrame(); ScreenCaptureService.refreshGlassEnvironment() }
    }
    fun settledTarget(): GlassRect? {
        refreshTarget()
        return target?.takeIf { !interacting && SystemClock.uptimeMillis() - changedAt >= GlobalGlassSampling.SETTLE_MS }
    }
    fun session(enabled: Boolean, phase: GlobalGlassPhase, detail: String) {
        if (!enabled) rendererUnavailable = false
        mutableState.value = GlobalGlassState(enabled, phase, detail)
        if (phase != GlobalGlassPhase.ACTIVE) clearFrame()
    }
    fun available(): Boolean = mutableState.value.authorizedThisSession && frame != null &&
        !AppGlassBackdrop.available() && SystemClock.uptimeMillis() - frameAt <= GlobalGlassSampling.FRAME_MAX_AGE_MS &&
        watched.get()?.isShown == true && !interacting

    fun publish(region: GlassSampleRegion, pixels: IntArray, usable: Boolean) {
        if (!usable || !mutableState.value.authorizedThisSession || AppGlassBackdrop.available() || settledTarget() != region.bubble) {
            clearFrame()
            return
        }
        val index = nextBuffer
        nextBuffer = 1 - nextBuffer
        val width = region.roi.width
        val height = region.roi.height
        val bitmap = buffers[index]?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { buffers[index]?.eraseColor(0); buffers[index] = it }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        frame = bitmap; frameRegion = region; frameAt = SystemClock.uptimeMillis()
        mutableState.value = GlobalGlassState(true, GlobalGlassPhase.ACTIVE, "全局边缘正在采样 · 最高 12 帧/秒 · 仅本机内存")
        watched.get()?.refreshBackdrop()
    }

    @RequiresApi(33)
    fun shaderFor(view: GlassFloatingBubbleView, lightX: Float, lightY: Float): Shader? {
        if (!available() || watched.get() !== view || settledTarget() != frameRegion?.bubble) return null
        val region = frameRegion ?: return null
        val bitmap = frame ?: return null
        return try {
            (lens ?: GlobalEdgeLens().also { lens = it }).bind(bitmap, region, view.width, view.height, lightX, lightY)
        } catch (failure: Exception) {
            rendererUnavailable = true
            mutableState.value = GlobalGlassState(true, GlobalGlassPhase.PAUSED, "设备当前无法使用折射，保留基础系统模糊")
            clearFrame()
            android.util.Log.w("GlobalGlass", "Edge shader unavailable; sampling will stop", failure)
            null
        }
    }

    fun clearFrame() {
        frame = null; frameRegion = null; frameAt = 0L
        watched.get()?.clearGlobalBackdrop()
        buffers.forEach { it?.eraseColor(0) }
        lens = null
    }
    fun release() { clearFrame(); buffers.fill(null); nextBuffer = 0 }
}

/**
 * Only the rim refracts, using live pixels OUTSIDE the full overlay window plus a guard band.
 * The occluded center remains transparent over Android's real system blur, never reconstructed.
 */
@RequiresApi(33)
private class GlobalEdgeLens {
    private val shader = RuntimeShader("""
        uniform shader ring;
        uniform float2 size;
        uniform float2 imageSize;
        uniform float2 scale;
        uniform float2 origin;
        uniform float4 excluded;
        uniform float cornerRadius;
        uniform float2 light;
        float sdf(float2 p) {
            float2 q=abs(p)-(size*.5-cornerRadius);
            return length(max(q,float2(0)))+min(max(q.x,q.y),0.0)-cornerRadius;
        }
        half4 safeSample(float2 p) {
            if(p.x<1.0 || p.y<1.0 || p.x>imageSize.x-2.0 || p.y>imageSize.y-2.0) return half4(0);
            if(p.x>=excluded.x-1.0 && p.y>=excluded.y-1.0 && p.x<=excluded.z+1.0 && p.y<=excluded.w+1.0) return half4(0);
            return ring.eval(p);
        }
        half4 main(float2 xy) {
            float2 p=xy-size*.5;
            float d=sdf(p);
            float width=min(size.x,size.y)*.17;
            if(d>0.0 || d<-width) return half4(0);
            float edge=1.0-smoothstep(0.0,width,-d);
            float2 direction=p*scale/max(length(p*scale),.001);
            float2 center=origin+size*.5*scale;
            float dx=(direction.x>=0.0?excluded.z-center.x:center.x-excluded.x)/max(abs(direction.x),.001);
            float dy=(direction.y>=0.0?excluded.w-center.y:center.y-excluded.y)/max(abs(direction.y),.001);
            float2 q=center+direction*(min(dx,dy)+3.0+(1.0-edge)*width*scale.x*.65);
            float2 tangent=float2(-direction.y,direction.x);
            half4 middle=safeSample(q);
            half4 red=safeSample(q+tangent*.7*edge);
            half4 blue=safeSample(q-tangent*.7*edge);
            half alpha=half(edge*.82)*min(middle.a,min(red.a,blue.a));
            half3 color=half3(red.r,middle.g,blue.b);
            float glint=pow(max(dot(direction,normalize(light)),0.0),4.0)*edge;
            color=mix(color,half3(.98,.985,1),half(glint*.18));
            return half4(color*alpha,alpha);
        }
    """.trimIndent())
    fun bind(bitmap: Bitmap, region: GlassSampleRegion, width: Int, height: Int, lightX: Float, lightY: Float): Shader {
        shader.setInputShader("ring", BitmapShader(bitmap, Shader.TileMode.DECAL, Shader.TileMode.DECAL))
        shader.setFloatUniform("size", width.toFloat(), height.toFloat())
        shader.setFloatUniform("imageSize", bitmap.width.toFloat(), bitmap.height.toFloat())
        shader.setFloatUniform("scale", region.scaleX, region.scaleY)
        shader.setFloatUniform("origin", region.originX, region.originY)
        shader.setFloatUniform("excluded", (region.excluded.left-region.roi.left).toFloat(), (region.excluded.top-region.roi.top).toFloat(),
            (region.excluded.right-region.roi.left).toFloat(), (region.excluded.bottom-region.roi.top).toFloat())
        shader.setFloatUniform("cornerRadius", GlassBubbleGeometry.cornerRadius(width, height))
        shader.setFloatUniform("light", -.7f+lightX*.25f, -1f+lightY*.25f)
        return shader
    }
}
