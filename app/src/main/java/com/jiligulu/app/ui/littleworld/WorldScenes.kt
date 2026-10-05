package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.jiligulu.app.R
import com.jiligulu.app.ui.theme.GuluBrandFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

enum class WorldScene { COURIER, INBOX, ARCHIVE, ALBUM, TIME_MACHINE, SECRET }

@Composable
fun WorldSceneArt(scene:WorldScene,modifier:Modifier=Modifier,onClick:(()->Unit)?=null,sleeping:Boolean=true) {
    val resources=LocalContext.current.resources
    val awake=scene==WorldScene.SECRET&&!sleeping
    val resource=if(awake) R.drawable.world_secret_awake_anime else R.drawable.world_scene_atlas_anime
    val decoded by produceState<Pair<Int,ImageBitmap>?>(null,resources,resource) {
        value=resource to withContext(Dispatchers.IO) { LittleWorldArtwork.image(resources,resource) }
    }
    val art=decoded?.takeIf {it.first==resource}?.second ?: LittleWorldArtwork.cachedImage(resource)
    val rect=remember(scene,art,awake) { art?.let { image ->
        if(awake) return@let IntRect(0,0,image.width,image.height)
        val column=scene.ordinal%2;val row=scene.ordinal/2
        val w=image.width/2;val h=image.height/3
        IntRect(column*w+2,row*h+2,(column+1)*w-2,(row+1)*h-2)
    } }
    Canvas(modifier.semantics { contentDescription=when(scene) {
        WorldScene.COURIER->"阿噜背着小包送信"; WorldScene.INBOX->"花园里的收件箱"; WorldScene.ARCHIVE->"存放旧信的木匣"
        WorldScene.ALBUM->"打开的生活纪念册"; WorldScene.TIME_MACHINE->"阿噜的时光列车"; WorldScene.SECRET->"阿噜的秘密基地"
    } }.then(if(onClick!=null) Modifier.clickable(onClick=onClick) else Modifier)) {
        val image=art;val crop=rect
        if(image!=null&&crop!=null) {
            val scale=min(size.width/crop.width,size.height/crop.height)
            val dst=IntSize((crop.width*scale).roundToInt(),(crop.height*scale).roundToInt())
            drawImage(image,srcOffset=IntOffset(crop.left,crop.top),srcSize=IntSize(crop.width,crop.height),
                dstOffset=IntOffset(((size.width-dst.width)/2).roundToInt(),((size.height-dst.height)/2).roundToInt()),dstSize=dst,colorFilter=MutedSceneColorFilter)
        }
    }
}

@Composable
fun WorldSceneBanner(scene:WorldScene,title:String,body:String,count:String?=null,onSceneClick:(()->Unit)?=null,sleeping:Boolean=true) {
    Column(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        WorldSceneArt(scene,Modifier.fillMaxWidth().aspectRatio(1.5f),onSceneClick,sleeping)
        Column(Modifier.fillMaxWidth().padding(horizontal=3.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(title,fontFamily=GuluBrandFont,fontWeight=FontWeight.Normal,fontSize=20.sp,color=MaterialTheme.colorScheme.primary)
            Text(body,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            count?.let { Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.primaryContainer) {
                Text(it,Modifier.padding(horizontal=10.dp,vertical=5.dp),style=MaterialTheme.typography.labelMedium,
                    color=MaterialTheme.colorScheme.onPrimaryContainer)
            } }
        }
    }
}

/** Book spine and rings belong to the measured page; photos and controls stay inside the paper. */
@Composable
fun AlbumPaperPage(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    val paper=MaterialTheme.colorScheme.tertiaryContainer
    val ink=MaterialTheme.colorScheme.primary
    Box(modifier.padding(horizontal=3.dp).rotate(.65f).drawWithCache {
        val u=1.dp.toPx();val corner=CornerRadius(16*u)
        onDrawBehind {
            drawRoundRect(Color.Black.copy(alpha=.045f),Offset(1*u,3*u),size,corner)
            drawRoundRect(paper,cornerRadius=corner)
            drawRoundRect(ink.copy(alpha=.12f),size=Size(22*u,size.height),cornerRadius=corner)
            listOf(.18f,.4f,.62f,.84f).forEach { f ->
                drawCircle(Color(0xFFAE9272),4*u,Offset(14*u,size.height*f))
                drawArc(Color(0xFFD3B78B),-70f,260f,false,Offset(7*u,size.height*f-5*u),Size(13*u,10*u),style=Stroke(2*u))
            }
        }
    }) { Column(Modifier.fillMaxWidth().padding(start=34.dp,end=18.dp,top=18.dp,bottom=18.dp),
        verticalArrangement=Arrangement.spacedBy(7.dp),content=content) }
}

@Composable
fun CountedTab(label:String,count:Int,selected:Boolean,onClick:()->Unit) {
    FilterChip(selected=selected,onClick=onClick,label={Text(label)},modifier=Modifier.semantics {contentDescription="$label，$count 项"})
}

/** A small physical wood/ivory plaque, with a top bevel, darker side and contact shadow. */
@Composable
fun ScenePlaqueButton(label:String,modifier:Modifier=Modifier,selected:Boolean=false,enabled:Boolean=true,onClick:()->Unit) {
    val accent=MaterialTheme.colorScheme.primary
    Box(modifier.clickable(enabled=enabled,role=Role.Button,onClick=onClick).drawWithCache {
        val u=1.dp.toPx();val radius=CornerRadius(2*u)
        onDrawBehind {
            drawRoundRect(Color(0xFF3D2715).copy(alpha=.18f),Offset(1*u,2*u),size,radius)
            drawRoundRect(if(selected) Color(0xFFE8CE99) else Color(0xFFD9BE8B),cornerRadius=radius)
            drawLine(Color(0xFFFFEDBC),Offset(2*u,2*u),Offset(size.width-2*u,2*u),1.2f*u)
            drawLine(Color(0xFF947246),Offset(2*u,size.height-1*u),Offset(size.width-2*u,size.height-1*u),1.5f*u)
            drawLine(Color(0xFFAF8E59).copy(alpha=.28f),Offset(4*u,size.height*.65f),Offset(size.width-4*u,size.height*.65f),.5f*u)
            if(selected) drawCircle(accent,2*u,Offset(6*u,6*u))
        }
    },contentAlignment=Alignment.Center) {
        Text(label,Modifier.padding(horizontal=7.dp,vertical=5.dp),style=MaterialTheme.typography.labelSmall,
            color=Color(0xFF5E4229),maxLines=1)
    }
}

/** Controls are attached to the scenery; only the depicted object is a reading hotspot. */
@Composable
fun WorldScenePanel(scene:WorldScene,title:String,onBack:()->Unit,
    tabs:List<String> = emptyList(),selected:Int=0,onTab:(Int)->Unit={},
    action:String?=null,onAction:()->Unit={},onObject:()->Unit={},sleeping:Boolean=true) {
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(8.dp))) {
        WorldSceneArt(scene,Modifier.matchParentSize(),sleeping=sleeping)
        val hotspot=when(scene) {
            WorldScene.COURIER->floatArrayOf(.18f,.39f,.46f,.49f)
            WorldScene.INBOX->floatArrayOf(.34f,.15f,.43f,.61f)
            WorldScene.ARCHIVE->floatArrayOf(.21f,.29f,.61f,.54f)
            WorldScene.ALBUM->floatArrayOf(.19f,.43f,.75f,.40f)
            WorldScene.TIME_MACHINE->floatArrayOf(.22f,.12f,.51f,.74f)
            WorldScene.SECRET->floatArrayOf(.36f,.30f,.50f,.51f)
        }
        Box(Modifier.offset(maxWidth*hotspot[0],maxHeight*hotspot[1])
            .size(maxWidth*hotspot[2],maxHeight*hotspot[3]).clickable(onClickLabel=title,onClick=onObject)
            .semantics {contentDescription=title})
        ScenePlaqueButton("‹ 小窝",Modifier.align(Alignment.TopStart).padding(8.dp).rotate(-2f),onClick=onBack)
        action?.let { ScenePlaqueButton(it,Modifier.align(Alignment.TopEnd).padding(8.dp).rotate(2f),onClick=onAction) }
        if(tabs.isNotEmpty()) Row(Modifier.align(Alignment.BottomCenter).padding(8.dp),
            horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            tabs.forEachIndexed { i,label -> ScenePlaqueButton(label,Modifier.weight(1f).rotate(if(i%2==0) -1f else 1f),
                selected=i==selected,onClick={onTab(i)}) }
        } else ScenePlaqueButton(title,Modifier.align(Alignment.BottomCenter).padding(8.dp),onClick=onObject)
    }
}
