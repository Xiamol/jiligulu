package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import com.jiligulu.app.ui.theme.GuluBrandFont
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.jiligulu.app.R
import com.jiligulu.app.core.audio.UiCue
import com.jiligulu.app.ui.components.uiTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ShelfWish(val id:String,val title:String,val progress:Float,val subtitle:String)

/** Actual items only. Feet, contact shadows and labels share the painted shelf's coordinates. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WishShelfStage(items:List<ShelfWish>,kind:Int,onBack:()->Unit,onAdd:()->Unit,onKind:(Int)->Unit,minHeight:Dp=0.dp,onSelect:(String)->Unit) {
    val resources=LocalContext.current.resources
    val art by produceState<ImageBitmap?>(LittleWorldArtwork.cachedImage(R.drawable.wish_shelf_ocean_palace_v4),resources) {
        value=withContext(Dispatchers.IO) { LittleWorldArtwork.image(resources,R.drawable.wish_shelf_ocean_palace_v4) }
    }
    var page by rememberSaveable(kind) { mutableIntStateOf(0) }
    var whisper by remember { mutableStateOf(false) }
    val pages=maxOf(1,(items.size+11)/12)
    LaunchedEffect(pages) {page=page.coerceIn(0,pages-1)}
    LaunchedEffect(whisper) {if(whisper){kotlinx.coroutines.delay(2600);whisper=false}}
    Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(maxOf(minHeight,LocalConfiguration.current.screenWidthDp.dp/(9f/16f)))
            .combinedClickable(onClick={},onLongClick={whisper=true})) {
            art?.let {Image(it,null,Modifier.matchParentSize(),contentScale=ContentScale.FillBounds,colorFilter=MutedSceneColorFilter)}
            val visible=items.drop(page*12).take(12)
            ShelfPearlButton("‹ 小窝",Modifier.align(Alignment.TopStart).padding(8.dp),cue=UiCue.NAVIGATE,onClick=onBack)
            ShelfPearlButton("＋ 愿望",Modifier.align(Alignment.TopEnd).padding(8.dp),onClick=onAdd)
            Text("星星愿望册",Modifier.offset(x=maxWidth*.31f,y=maxHeight*.146f)
                .size(maxWidth*.38f,maxHeight*.033f).wrapContentSize(Alignment.Center),
                textAlign=androidx.compose.ui.text.style.TextAlign.Center,fontFamily=GuluBrandFont,
                fontSize=18.sp,color=Color(0xFF3D6977))
            listOf("正在攒","候场","纪念").forEachIndexed { i,label->
                Box(Modifier.offset(x=maxWidth*(.27f+i*.23f)-maxWidth*.11f,y=maxHeight*.198f-22.dp)
                    .size(maxWidth*.22f,44.dp).clickable(onClick=uiTap(UiCue.SELECT) {onKind(i)}),contentAlignment=Alignment.Center) {
                    Text(if(kind==i) "· $label ·" else label,style=MaterialTheme.typography.labelMedium,
                        fontWeight=if(kind==i) FontWeight.Bold else FontWeight.Normal,
                        color=if(kind==i) Color(0xFF278B99) else Color(0xFF66838B))
                }
            }
            val bottleWidth=maxWidth*.19f
            val bottleHeight=bottleWidth/.75f
            visible.forEachIndexed { index,item ->
                val row=index/3;val col=index%3
                val centerX=maxWidth*(.27f+col*.23f)
                val floor=maxHeight*listOf(.328f,.478f,.651f,.811f)[row]
                Canvas(Modifier.offset(x=centerX-bottleWidth*.32f,y=floor-4.dp).size(bottleWidth*.64f,5.dp)) {
                    drawOval(Color(0xFF497C8B).copy(alpha=.18f),topLeft=Offset.Zero,size=Size(size.width,size.height))
                }
                StarWishJar(item.progress,Modifier.width(bottleWidth).offset(x=centerX-bottleWidth/2,
                    y=floor-bottleHeight*.92f).clickable(onClickLabel="查看${item.title}",onClick=uiTap(UiCue.SELECT){onSelect(item.id)}),complete=kind==2)
                Box(Modifier.offset(x=centerX-bottleWidth/2,y=floor+maxHeight*.010f-18.dp)
                    .size(bottleWidth,36.dp).clickable(onClick=uiTap(UiCue.SELECT){onSelect(item.id)}),contentAlignment=Alignment.Center) {
                    Text(item.title,style=MaterialTheme.typography.labelSmall,color=Color(0xFF3D6977),
                        maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
            if(items.isEmpty()) Text("等一个小愿望 ♡",Modifier.align(Alignment.Center),
                style=MaterialTheme.typography.bodySmall,color=Color(0xFF66838B))
            if(whisper) Surface(Modifier.align(Alignment.TopCenter).padding(12.dp),shape=MaterialTheme.shapes.medium,
                color=MaterialTheme.colorScheme.surface.copy(alpha=.94f)) {
                Text("阿噜把小愿望收进漂流瓶里，慢慢攒也没关系 ♡",Modifier.padding(10.dp),style=MaterialTheme.typography.bodySmall)
            }
            if(pages>1) Row(Modifier.align(Alignment.BottomCenter).padding(bottom=18.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                ShelfPearlButton("‹ 上一架",enabled=page>0,onClick={page--})
                Text("${page+1} / $pages",style=MaterialTheme.typography.labelSmall,color=Color(0xFF3D6977))
                ShelfPearlButton("下一架 ›",enabled=page<pages-1,onClick={page++})
            }
        }
    }
}

/** Nacre tags belong to this seaside shelf; other scene materials remain independent. */
@Composable
private fun ShelfPearlButton(label:String,modifier:Modifier=Modifier,enabled:Boolean=true,cue:UiCue=UiCue.SELECT,onClick:()->Unit) {
    Box(modifier.clickable(enabled=enabled,role=Role.Button,onClick=uiTap(cue,onClick)).drawWithCache {
        val u=1.dp.toPx();val corner=CornerRadius(8*u)
        val pearl=Brush.linearGradient(listOf(Color(0xFFFFFFFF),Color(0xFFF2F8F5),Color(0xFFDCEEEE)))
        onDrawBehind {
            drawRoundRect(Color(0xFF497C8B).copy(alpha=.15f),Offset(u,2*u),size,corner)
            drawRoundRect(pearl,cornerRadius=corner)
            drawRoundRect(Color(0xFFA6CDD2),cornerRadius=corner,style=Stroke(u))
            drawLine(Color.White,Offset(8*u,3*u),Offset(size.width-8*u,3*u),u)
        }
    },contentAlignment=Alignment.Center) {
        Text(label,Modifier.padding(horizontal=9.dp,vertical=6.dp),style=MaterialTheme.typography.labelSmall,
            color=Color(0xFF3D6977).copy(alpha=if(enabled) 1f else .45f),maxLines=1)
    }
}
