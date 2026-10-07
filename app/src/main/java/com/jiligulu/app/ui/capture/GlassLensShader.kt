package com.jiligulu.app.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi

/** Own-window PixelCopy supplies real, unobstructed pixels to the complete glass lens. */
@RequiresApi(33)
internal class GlassLensShader {
    val shader = RuntimeShader(
        """
            uniform shader backdrop;
            uniform float2 offset;
            half4 sampleBackdrop(float2 p) {
                return backdrop.eval(p+offset);
            }
        """.trimIndent() + "\n" + GlassLensOptics.SOURCE
    )
    private val inputs = GlassBitmapShaderCache(Shader.TileMode.CLAMP)

    fun bind(bitmap: Bitmap, x: Float, y: Float, w: Int, h: Int, lx: Float, ly: Float) {
        shader.setInputShader("backdrop", inputs.forBitmap(bitmap))
        shader.setFloatUniform("offset", x, y)
        GlassLensOptics.bind(shader, w, h, lx, ly)
    }
}

/** Both sources share magnification, meniscus, dispersion and reflected light across the body. */
internal object GlassLensOptics {
    val SOURCE = """

        uniform float2 size;
        uniform float2 light;
        uniform float cornerRadius;
        float lensDistance(float2 p) {
            float2 q=abs(p)-(size*.5-cornerRadius);
            return length(max(q,float2(0)))+min(max(q.x,q.y),0.0)-cornerRadius;
        }
        half4 main(float2 xy) {
            float2 p=xy-size*.5;
            float d=lensDistance(p);
            if(d>.8) return half4(0);
            float rim=min(size.x,size.y)*.11;
            float edge=1.0-smoothstep(0.0,rim,-d);
            float2 gradient=float2(
                lensDistance(p+float2(.6,0))-lensDistance(p-float2(.6,0)),
                lensDistance(p+float2(0,.6))-lensDistance(p-float2(0,.6)));
            float2 n=gradient/max(length(gradient),.001);
            float2 q=xy-n*rim*.68*pow(edge,.75)-p*.09*(1.0-edge);
            float dispersion=min(size.x,size.y)*.014*edge;
            half4 middle=sampleBackdrop(q);
            if(middle.a<.001) return half4(0);
            half4 red=sampleBackdrop(q+n*dispersion);
            half4 blue=sampleBackdrop(q-n*dispersion);
            half3 color=half3(
                red.a>.001?red.r/red.a:middle.r/middle.a,
                middle.g/middle.a,
                blue.a>.001?blue.b/blue.a:middle.b/middle.a);
            half4 mirror=sampleBackdrop(xy+n*rim*(1.0-edge*.7));
            if(mirror.a>.001) {
                color=mix(color,mirror.rgb/mirror.a,half(.26*edge*edge));
            }
            float spec=pow(max(dot(n,normalize(light)),0.0),5.0)*edge;
            color=mix(color,half3(.98,.985,1.0),half(spec*.5));
            color=mix(color,half3(.90,.92,.98),half(.035));
            half alpha=middle.a*half(1.0-smoothstep(-.8,.8,d));
            return half4(color*alpha,alpha);
        }
    """.trimIndent()

    @RequiresApi(33)
    fun bind(shader: RuntimeShader, width: Int, height: Int, lightX: Float, lightY: Float) {
        shader.setFloatUniform("size", width.toFloat(), height.toFloat())
        shader.setFloatUniform("cornerRadius", GlassBubbleGeometry.cornerRadius(width, height))
        shader.setFloatUniform("light", -.7f + lightX * .25f, -1f + lightY * .25f)
    }
}

/** The two pixel buffers keep their shaders; position/light updates allocate no shader objects. */
@RequiresApi(33)
internal class GlassBitmapShaderCache(private val tileMode: Shader.TileMode) {
    private val bitmaps = arrayOfNulls<Bitmap>(2)
    private val shaders = arrayOfNulls<BitmapShader>(2)
    private var replacement = 0

    fun forBitmap(bitmap: Bitmap): BitmapShader {
        for (index in bitmaps.indices) {
            if (bitmaps[index] === bitmap) return checkNotNull(shaders[index])
        }
        val index = replacement
        replacement = 1 - replacement
        val shader = BitmapShader(bitmap, tileMode, tileMode).apply {
            // RuntimeShader inputs default to nearest filtering regardless of the Paint flags.
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
        bitmaps[index] = bitmap
        shaders[index] = shader
        return shader
    }
}
