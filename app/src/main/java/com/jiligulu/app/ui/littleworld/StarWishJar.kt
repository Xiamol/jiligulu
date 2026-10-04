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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.jiligulu.app.R
import kotlin.math.*

/** Glass and ribbon surround shaded paper sprites; their count reflects the saved progress. */
@Composable
fun StarWishJar(progress:Float,modifier:Modifier=Modifier,complete:Boolean=progress>=1f) {
    val fill = animateFloatAsState(progress.coerceIn(0f,1f),tween(420),label="wishStars")
    val resources=LocalContext.current.resources
    val art=remember { LittleWorldArtwork.image(resources,R.drawable.wish_star_bottle) }
    val starArt=remember { LittleWorldArtwork.image(resources,R.drawable.wish_lucky_stars_atlas_v2) }
    Box(modifier.aspectRatio(.75f).semantics {
        contentDescription=if(complete)"已经装满星星的愿望瓶" else "愿望瓶，已装满 ${(progress.coerceIn(0f,1f)*100).toInt()}%"
    }) {
        Image(art,null,Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().drawWithCache {
            val w=size.width;val h=size.height
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
            val cell=IntSize(starArt.width/3,starArt.height/2)
            // Back rows are smaller and dimmer. Front sprites slightly overlap, making a
            // pile of physical paper objects rather than a flat row of painted symbols.
            val stars=positions.mapIndexed { i, center ->
                    val variant=(i*5+i/6)%6
                    val scale=when(i%4){0->.9f;1->1.08f;else->1f}
                    val side=(w*.15f*scale).roundToInt().coerceAtLeast(1)
                    CachedWishStar(center,IntOffset((variant%3)*cell.width,(variant/3)*cell.height),
                        IntOffset((center.x-side/2f).roundToInt(),(center.y-side/2f).roundToInt()),
                        IntSize(side,side),((i*31)%49-24).toFloat(),if(i%4==0).86f else .98f)
                }
            val glass=Brush.linearGradient(listOf(Color.White.copy(alpha=.10f),Color.Transparent,
                Color(0xFFBEAAE8).copy(alpha=.07f)),Offset(w*.15f,h*.32f),Offset(w*.85f,h*.84f))
            onDrawBehind {
                val count=(fill.value*stars.size).roundToInt().coerceIn(0,stars.size)
                clipPath(interior) {
                    // Higher rows sit behind the lower foreground objects.
                    for(i in count-1 downTo 0) {
                        val star=stars[i]
                        rotate(star.rotation,pivot=star.center) {
                            drawImage(starArt,srcOffset=star.source,srcSize=cell,
                                dstOffset=star.destination,dstSize=star.size,alpha=star.alpha)
                        }
                    }
                    drawPath(interior,glass)
                }
                // Clear-glass glints remain in front of the paper stars.
                drawLine(Color.White.copy(alpha=.58f),Offset(w*.27f,h*.54f),Offset(w*.31f,h*.64f),w*.016f,StrokeCap.Round)
                drawLine(Color.White.copy(alpha=.25f),Offset(w*.72f,h*.55f),Offset(w*.69f,h*.65f),w*.01f,StrokeCap.Round)
            }
        })
    }
}

private data class CachedWishStar(val center:Offset,val source:IntOffset,val destination:IntOffset,
    val size:IntSize,val rotation:Float,val alpha:Float)
