package com.jiligulu.app.ui.littleworld

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.jiligulu.app.R
import kotlin.math.*

/** Watercolor glass and ribbon are cached art; folded stars still reflect the real saved progress. */
@Composable
fun StarWishJar(progress:Float,modifier:Modifier=Modifier,complete:Boolean=progress>=1f) {
    val fill = animateFloatAsState(progress.coerceIn(0f,1f),tween(420),label="wishStars")
    val resources=LocalContext.current.resources
    val art=remember { LittleWorldArtwork.image(resources,R.drawable.wish_star_bottle) }
    Box(modifier.aspectRatio(.75f).semantics {
        contentDescription=if(complete)"已经装满星星的愿望瓶" else "愿望瓶，已装满 ${(progress.coerceIn(0f,1f)*100).toInt()}%"
    }) {
        Image(art,null,Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().drawWithCache {
            val w=size.width;val h=size.height
            val palette=listOf(Color(0xFFBDA5EC),Color(0xFFE8ADC7),Color(0xFFFFD894),Color(0xFFA5D7C5),Color(0xFFB3D8EB))
            val vertices=(0..9).map { n ->
                val angle=(n*36f-90f)*PI/180;val factor=if(n%2==0)1f else .51f
                Offset(w*.5f+(cos(angle)*w*.37f*factor).toFloat(),h*.60f+(sin(angle)*h*.30f*factor).toFloat())
            }
            val interior=Path().apply { vertices.forEachIndexed{i,p->if(i==0)moveTo(p.x,p.y)else lineTo(p.x,p.y)};close() }
            fun inside(p:Offset):Boolean {
                var result=false;var j=vertices.lastIndex
                for(i in vertices.indices){val a=vertices[i];val b=vertices[j]
                    if((a.y>p.y)!=(b.y>p.y)&&p.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x)result=!result
                    j=i
                };return result
            }
            val positions=(0..10).flatMap { row->(0..7).map { col->
                Offset(w*(.115f+col*.11f+(if(row%2==0).008f else -.008f)),h*(.845f-row*.051f))
            } }.filter{inside(it)}
            // The bottle size owns this geometry. Progress changes only how many cached stars
            // are drawn; every animation frame reuses their outlines and five folded facets.
            val stars=positions.mapIndexed { i, center ->
                    val row=i/5;val col=i%5
                    val radius=w*.055f;val color=palette[(row+col)%palette.size];val rotation=row*9f+col*13f
                    fun point(n:Int):Offset {
                        val angle=(n*36f+rotation-90f)*PI/180
                        val r=radius*if(n%2==0)1f else .47f
                        return center+Offset((cos(angle)*r).toFloat(),(sin(angle)*r).toFloat())
                    }
                    val outline=Path().apply { val p=point(0);moveTo(p.x,p.y);for(n in 1..9){val a=point(n);lineTo(a.x,a.y)};close() }
                    val facets=(0..4).map { n ->
                        val a=point(n*2);val b=point(n*2+1);val c=point((n*2+2)%10)
                        val facet=Path().apply { moveTo(center.x,center.y);lineTo(a.x,a.y);lineTo(b.x,b.y);lineTo(c.x,c.y);close() }
                        WishStarFacet(facet,lerp(color,if(n%2==0)Color.White else Color(0xFF897798),if(n%2==0).25f else .10f))
                    }
                    CachedWishStar(outline,color,facets)
                }
            onDrawBehind {
                val count=(fill.value*stars.size).roundToInt().coerceIn(0,stars.size)
                clipPath(interior) {
                    repeat(count) { i ->
                        val star=stars[i]
                        drawPath(star.outline,star.color)
                        repeat(star.facets.size) { n -> val facet=star.facets[n];drawPath(facet.path,facet.color) }
                    }
                }
                // Clear-glass glints remain in front of the paper stars.
                drawLine(Color.White.copy(alpha=.58f),Offset(w*.27f,h*.54f),Offset(w*.31f,h*.64f),w*.016f,StrokeCap.Round)
                drawLine(Color.White.copy(alpha=.25f),Offset(w*.72f,h*.55f),Offset(w*.69f,h*.65f),w*.01f,StrokeCap.Round)
            }
        })
    }
}

private data class WishStarFacet(val path:Path,val color:Color)
private data class CachedWishStar(val outline:Path,val color:Color,val facets:List<WishStarFacet>)
