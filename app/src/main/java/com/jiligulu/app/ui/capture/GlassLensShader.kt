package com.jiligulu.app.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi

/** Rounded meniscus, magnification, edge dispersion and mirrored background reflection. */
@RequiresApi(33)
internal class GlassLensShader {
    val shader=RuntimeShader("""
        uniform shader backdrop;
        uniform float2 size;
        uniform float2 offset;
        uniform float2 light;
        float box(float2 p) {
            float radius=min(size.x,size.y)*.24;
            float2 q=abs(p)-(size*.46-radius);
            return length(max(q,float2(0)))+min(max(q.x,q.y),0.0)-radius;
        }
        half4 main(float2 xy) {
            float2 p=xy-size*.5;
            float d=box(p);
            if(d>0.0) return half4(0);
            float rim=min(size.x,size.y)*.085;
            float e=1.0-smoothstep(0.0,rim,-d);
            float2 gradient=float2(box(p+float2(.6,0))-box(p-float2(.6,0)),box(p+float2(0,.6))-box(p-float2(0,.6)));
            float2 n=gradient/max(length(gradient),.001);
            float2 q=xy+offset-n*rim*.68*pow(e,.75)-p*.065*(1.0-e);
            float dispersion=min(size.x,size.y)*.014*e;
            half3 color=half3(backdrop.eval(q+n*dispersion).r,backdrop.eval(q).g,backdrop.eval(q-n*dispersion).b);
            half3 mirror=backdrop.eval(xy+offset+n*rim*(1.0-e*.7)).rgb;
            color=mix(color,mirror,half(.32*e*e));
            float spec=pow(max(dot(n,normalize(light)),0.0),5.0)*e;
            color=mix(color,half3(.98,.985,1.0),half(spec*.5));
            color=mix(color,half3(.90,.92,.98),half(.035));
            return half4(color,1.0);
        }
    """.trimIndent())
    fun bind(bitmap:Bitmap,x:Float,y:Float,w:Int,h:Int,lx:Float,ly:Float) {
        shader.setInputShader("backdrop",BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP))
        shader.setFloatUniform("size",w.toFloat(),h.toFloat())
        shader.setFloatUniform("offset",x,y)
        shader.setFloatUniform("light",-.7f+lx*.25f,-1f+ly*.25f)
    }
}
