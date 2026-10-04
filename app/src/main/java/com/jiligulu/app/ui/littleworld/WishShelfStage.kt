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
        value=withContext(Dispatchers.IO) { LittleWorldArtwork.image(resources,R.drawable.wish_shelf_room_anime) }
    }
    var page by rememberSaveable(kind) { mutableIntStateOf(0) }
    var whisper by remember { mutableStateOf(false) }
    val pages=maxOf(1,(items.size+8)/9)
    LaunchedEffect(pages) {page=page.coerceIn(0,pages-1)}
    LaunchedEffect(whisper) {if(whisper){kotlinx.coroutines.delay(2600);whisper=false}}
    Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(maxOf(minHeight,LocalConfiguration.current.screenWidthDp.dp/.75f))
            .combinedClickable(onClick={},onLongClick={whisper=true})) {
            art?.let {Image(it,null,Modifier.matchParentSize(),contentScale=ContentScale.FillBounds)}
            val visible=items.drop(page*9).take(9)
            ScenePlaqueButton("‹ 小窝",Modifier.align(Alignment.TopStart).padding(8.dp),onClick=onBack)
            ScenePlaqueButton("＋ 愿望",Modifier.align(Alignment.TopEnd).padding(8.dp),onClick=onAdd)
            Row(Modifier.offset(x=maxWidth*.17f,y=maxHeight*.095f).width(maxWidth*.66f),
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("正在攒","候场","纪念").forEachIndexed { i,label->
                    ScenePlaqueButton(label,Modifier.weight(1f),selected=kind==i,onClick={onKind(i)})
                }
            }
            val bottleWidth=maxWidth*.19f
            val bottleHeight=bottleWidth/.75f
            visible.forEachIndexed { index,item ->
                val row=index/3;val col=index%3
                val count=minOf(3,visible.size-row*3)
                val centerX=maxWidth*(.51f+(col-(count-1)/2f)*.205f)
                val floor=maxHeight*listOf(.36f,.603f,.867f)[row]
                Canvas(Modifier.offset(x=centerX-bottleWidth*.32f,y=floor-4.dp).size(bottleWidth*.64f,5.dp)) {
                    drawOval(Color(0xFF66503D).copy(alpha=.16f),topLeft=Offset.Zero,size=Size(size.width,size.height))
                }
                Column(Modifier.width(bottleWidth).offset(x=centerX-bottleWidth/2,
                    y=floor-bottleHeight*.92f),horizontalAlignment=Alignment.CenterHorizontally) {
                    StarWishJar(item.progress,Modifier.width(bottleWidth).clickable(onClickLabel="查看${item.title}"){onSelect(item.id)},
                        complete=kind==2)
                    ScenePlaqueButton(item.title,Modifier.width(bottleWidth),onClick={onSelect(item.id)})
                }
            }
            if(items.isEmpty()) Text("等一个小愿望 ♡",Modifier.align(Alignment.Center),
                style=MaterialTheme.typography.bodySmall,color=Color(0xFF765B3C))
            if(whisper) Surface(Modifier.align(Alignment.TopCenter).padding(12.dp),shape=MaterialTheme.shapes.medium,
                color=MaterialTheme.colorScheme.surface.copy(alpha=.94f)) {
                Text("阿噜悄悄给每个愿望留了位置，慢一点也没关系 ♡",Modifier.padding(10.dp),style=MaterialTheme.typography.bodySmall)
            }
        }
        if(pages>1) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
            TextButton(onClick={page--},enabled=page>0){Text("上一架")}
            Text("${page+1} / $pages",style=MaterialTheme.typography.labelSmall)
            TextButton(onClick={page++},enabled=page<pages-1){Text("下一架")}
        }
    }
}
