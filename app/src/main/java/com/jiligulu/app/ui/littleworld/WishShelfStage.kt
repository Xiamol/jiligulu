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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ShelfWish(val id:String,val title:String,val progress:Float,val subtitle:String)

/** Actual items only. Feet, contact shadows and labels share the painted shelf's coordinates. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WishShelfStage(items:List<ShelfWish>,kind:Int,onBack:()->Unit,onAdd:()->Unit,onKind:(Int)->Unit,minHeight:Dp=0.dp,onSelect:(String)->Unit) {
    val resources=LocalContext.current.resources
    val art by produceState<ImageBitmap?>(null,resources) {
        value=withContext(Dispatchers.IO) { LittleWorldArtwork.image(resources,R.drawable.wish_shelf_lavender_v2) }
    }
    var page by rememberSaveable(kind) { mutableIntStateOf(0) }
    var whisper by remember { mutableStateOf(false) }
    val pages=maxOf(1,(items.size+11)/12)
    LaunchedEffect(pages) {page=page.coerceIn(0,pages-1)}
    LaunchedEffect(whisper) {if(whisper){kotlinx.coroutines.delay(2600);whisper=false}}
    Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(maxOf(minHeight,LocalConfiguration.current.screenWidthDp.dp/(9f/16f)))
            .combinedClickable(onClick={},onLongClick={whisper=true})) {
            art?.let {Image(it,null,Modifier.matchParentSize(),contentScale=ContentScale.FillBounds)}
            val visible=items.drop(page*12).take(12)
            ScenePlaqueButton("‹ 小窝",Modifier.align(Alignment.TopStart).padding(8.dp),onClick=onBack)
            ScenePlaqueButton("＋ 愿望",Modifier.align(Alignment.TopEnd).padding(8.dp),onClick=onAdd)
            Text("星星愿望册",Modifier.offset(x=maxWidth*.31f,y=maxHeight*.084f).width(maxWidth*.38f),
                textAlign=androidx.compose.ui.text.style.TextAlign.Center,fontFamily=GuluBrandFont,
                fontSize=18.sp,color=Color(0xFF795E49))
            listOf("正在攒","候场","纪念").forEachIndexed { i,label->
                Box(Modifier.offset(x=maxWidth*(.27f+i*.23f)-maxWidth*.11f,y=maxHeight*.14f-22.dp)
                    .size(maxWidth*.22f,44.dp).clickable {onKind(i)},contentAlignment=Alignment.Center) {
                    Text(if(kind==i) "· $label ·" else label,style=MaterialTheme.typography.labelMedium,
                        fontWeight=if(kind==i) FontWeight.Bold else FontWeight.Normal,
                        color=if(kind==i) Color(0xFF73568E) else Color(0xFF806446))
                }
            }
            val bottleWidth=maxWidth*.19f
            val bottleHeight=bottleWidth/.75f
            visible.forEachIndexed { index,item ->
                val row=index/3;val col=index%3
                val centerX=maxWidth*(.27f+col*.23f)
                val floor=maxHeight*listOf(.321f,.506f,.697f,.857f)[row]
                Canvas(Modifier.offset(x=centerX-bottleWidth*.32f,y=floor-4.dp).size(bottleWidth*.64f,5.dp)) {
                    drawOval(Color(0xFF776386).copy(alpha=.18f),topLeft=Offset.Zero,size=Size(size.width,size.height))
                }
                StarWishJar(item.progress,Modifier.width(bottleWidth).offset(x=centerX-bottleWidth/2,
                    y=floor-bottleHeight*.92f).clickable(onClickLabel="查看${item.title}"){onSelect(item.id)},complete=kind==2)
                Box(Modifier.offset(x=centerX-bottleWidth/2,y=floor+maxHeight*.006f-18.dp)
                    .size(bottleWidth,36.dp).clickable {onSelect(item.id)},contentAlignment=Alignment.Center) {
                    Text(item.title,style=MaterialTheme.typography.labelSmall,color=Color(0xFF71543F),
                        maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
            if(items.isEmpty()) Text("等一个小愿望 ♡",Modifier.align(Alignment.Center),
                style=MaterialTheme.typography.bodySmall,color=Color(0xFF765B3C))
            if(whisper) Surface(Modifier.align(Alignment.TopCenter).padding(12.dp),shape=MaterialTheme.shapes.medium,
                color=MaterialTheme.colorScheme.surface.copy(alpha=.94f)) {
                Text("阿噜悄悄给每个愿望留了位置，慢一点也没关系 ♡",Modifier.padding(10.dp),style=MaterialTheme.typography.bodySmall)
            }
            if(pages>1) Row(Modifier.align(Alignment.BottomCenter).padding(bottom=18.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                ScenePlaqueButton("‹ 上一架",enabled=page>0,onClick={page--})
                Text("${page+1} / $pages",style=MaterialTheme.typography.labelSmall,color=Color(0xFF71543F))
                ScenePlaqueButton("下一架 ›",enabled=page<pages-1,onClick={page++})
            }
        }
    }
}
