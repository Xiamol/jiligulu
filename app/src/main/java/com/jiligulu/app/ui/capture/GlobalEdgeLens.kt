package com.jiligulu.app.ui.capture

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi

/**
 * A complete optical body using the captured, unobstructed ring. The occluded center is an
 * approximation from four safe boundary samples, never the live pixels underneath the overlay.
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
                half4 direct=safeSample(q);
                if(direct.a>.001) return direct;
                float2 tangent=clamp(q,float2(${GlassLensSamplingGeometry.IMAGE_BORDER}),max(imageSize-float2(${GlassLensSamplingGeometry.FAR_IMAGE_BORDER}),float2(${GlassLensSamplingGeometry.IMAGE_BORDER})));
                float4 sides=excluded+float4(-${GlassLensSamplingGeometry.RECONSTRUCTION_MARGIN},-${GlassLensSamplingGeometry.RECONSTRUCTION_MARGIN},${GlassLensSamplingGeometry.RECONSTRUCTION_MARGIN},${GlassLensSamplingGeometry.RECONSTRUCTION_MARGIN});
                half4 left=safeSample(float2(sides.x,tangent.y));
                half4 top=safeSample(float2(tangent.x,sides.y));
                half4 right=safeSample(float2(sides.z,tangent.y));
                half4 bottom=safeSample(float2(tangent.x,sides.w));
                float4 distance=max(abs(float4(q.x-sides.x,q.y-sides.y,q.x-sides.z,q.y-sides.w)),float4(1));
                float4 weights=float4(left.a,top.a,right.a,bottom.a)/(distance*distance);
                float weight=weights.x+weights.y+weights.z+weights.w;
                if(weight<.0000001) return half4(0);
                half3 color=half3((float3(left.rgb)*weights.x+float3(top.rgb)*weights.y+
                    float3(right.rgb)*weights.z+float3(bottom.rgb)*weights.w)/weight);
                return half4(color,1);
            }
        """.trimIndent() + "\n" + GlassLensOptics.SOURCE
    )
    private val inputs = GlassBitmapShaderCache(Shader.TileMode.DECAL)

    fun bind(bitmap: Bitmap, region: GlassSampleRegion, width: Int, height: Int, lightX: Float, lightY: Float): Shader {
        shader.setInputShader("ring", inputs.forBitmap(bitmap))
        shader.setFloatUniform("imageSize", bitmap.width.toFloat(), bitmap.height.toFloat())
        shader.setFloatUniform("scale", region.scaleX, region.scaleY)
        shader.setFloatUniform("origin", GlassLensSamplingGeometry.sourceX(region, 0f), GlassLensSamplingGeometry.sourceY(region, 0f))
        shader.setFloatUniform("excluded", GlassLensSamplingGeometry.excludedLeft(region), GlassLensSamplingGeometry.excludedTop(region),
            GlassLensSamplingGeometry.excludedRight(region), GlassLensSamplingGeometry.excludedBottom(region))
        GlassLensOptics.bind(shader, width, height, lightX, lightY)
        return shader
    }
}
