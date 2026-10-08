package com.jiligulu.app.ui.capture

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi

/**
 * A complete optical body using the captured, unobstructed ring. The occluded center is an
 * a quiet material tone, never stretched edge letters or the live pixels under the overlay.
 * MediaProjection cannot exclude our own window from whole-display capture with a public API.
 */
@RequiresApi(33)
internal class GlobalEdgeLens {
    private val shader = RuntimeShader(
        """
            uniform shader ring;
            uniform float2 imageSize;
            uniform float2 scale;
            uniform float2 origin;
            uniform float4 excluded;
            uniform float3 ambient;
            half4 safeSample(float2 p) {
                if(p.x<${GlassLensSamplingGeometry.IMAGE_BORDER} || p.y<${GlassLensSamplingGeometry.IMAGE_BORDER} ||
                   p.x>imageSize.x-${GlassLensSamplingGeometry.FAR_IMAGE_BORDER} || p.y>imageSize.y-${GlassLensSamplingGeometry.FAR_IMAGE_BORDER}) return half4(0);
                if(p.x>=excluded.x-${GlassLensSamplingGeometry.EXCLUSION_GUARD} && p.y>=excluded.y-${GlassLensSamplingGeometry.EXCLUSION_GUARD} &&
                   p.x<=excluded.z+${GlassLensSamplingGeometry.EXCLUSION_GUARD} && p.y<=excluded.w+${GlassLensSamplingGeometry.EXCLUSION_GUARD}) return half4(0);
                return ring.eval(p);
            }
            half4 sampleBackdrop(float2 local) {
                // Origin always belongs to the current screen target, including while dragging.
                float2 q=origin+local*scale;
                // The missing centre has no observable detail. Four line samples stretched
                // text into large stripes. Use a translucent low-frequency material there.
                half4 quiet=half4(half3(ambient)*half(.16),half(.16));
                half4 direct=safeSample(q);
                if(direct.a>.001) return mix(quiet,direct,half(.32));
                return quiet;
            }
        """.trimIndent() + "\n" + GlassLensOptics.SOURCE
    )
    private val inputs = GlassBitmapShaderCache(Shader.TileMode.DECAL)

    fun bind(bitmap: Bitmap, region: GlassSampleRegion, width: Int, height: Int, lightX: Float, lightY: Float,
        ambientColor:Int=GlassAmbientTone.NEUTRAL): Shader {
        shader.setInputShader("ring", inputs.forBitmap(bitmap))
        shader.setFloatUniform("imageSize", bitmap.width.toFloat(), bitmap.height.toFloat())
        shader.setFloatUniform("scale", region.scaleX, region.scaleY)
        shader.setFloatUniform("origin", GlassLensSamplingGeometry.sourceX(region, 0f), GlassLensSamplingGeometry.sourceY(region, 0f))
        shader.setFloatUniform("excluded", GlassLensSamplingGeometry.excludedLeft(region), GlassLensSamplingGeometry.excludedTop(region),
            GlassLensSamplingGeometry.excludedRight(region), GlassLensSamplingGeometry.excludedBottom(region))
        shader.setFloatUniform("ambient",((ambientColor ushr 16)and255)/255f,((ambientColor ushr 8)and255)/255f,(ambientColor and255)/255f)
        GlassLensOptics.bind(shader, width, height, lightX, lightY)
        return shader
    }
}
