package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.jiligulu.app.R
import com.jiligulu.app.ui.theme.GuluBrandFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The room fills its viewport; art, painted captions and touch targets share one transform. */
@Composable
fun InteractiveRoomStage(onWishes:()->Unit,onLetters:()->Unit,onAlbum:()->Unit,
    onCalculator:()->Unit,onFortune:()->Unit,onTimeMachine:()->Unit,onPet:()->Unit,
    modifier:Modifier=Modifier) {
    val resources=LocalContext.current.resources
    val art by produceState<ImageBitmap?>(LittleWorldArtwork.cachedImage(R.drawable.world_interactive_room_v1),resources) {
        value=withContext(Dispatchers.IO) {LittleWorldArtwork.image(resources,R.drawable.world_interactive_room_v1)}
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        val sourceRatio=art?.let { it.width.toFloat()/it.height } ?: .75f
        val naturalWidth=maxOf(maxWidth,maxHeight*sourceRatio)
        val naturalHeight=naturalWidth/sourceRatio
        // A uniform crop on a tall phone would cut off both outer hanging signs.
        // Bound the crop to source x=.07.. .93 and y=.025.. .975; resize only the
        // remaining excess so the room still covers the entire available page.
        val sceneWidth=naturalWidth.coerceAtMost(maxWidth/.86f)
        val sceneHeight=naturalHeight.coerceAtMost(maxHeight/.95f)
        val sceneLeft=(maxWidth-sceneWidth)/2f
        val sceneTop=(maxHeight-sceneHeight)/2f
        art?.let { bitmap ->
            Canvas(Modifier.matchParentSize()) {
                drawImage(bitmap,
                    dstOffset=IntOffset(sceneLeft.toPx().roundToInt(),sceneTop.toPx().roundToInt()),
                    dstSize=IntSize(sceneWidth.toPx().roundToInt(),sceneHeight.toPx().roundToInt()),
                    filterQuality=FilterQuality.Medium)
            }
        }
        val signs=listOf(
            RoomSign("星星愿望",.357f,.277f,.166f,.035f,.205f,.127f,.302f,.192f,onWishes),
            RoomSign("未来信箱",.739f,.283f,.163f,.035f,.626f,.116f,.242f,.220f,onLetters),
            RoomSign("生活纪念册",.356f,.487f,.175f,.032f,.196f,.329f,.333f,.190f,onAlbum),
            RoomSign("小算盘",.713f,.489f,.165f,.035f,.625f,.369f,.186f,.161f,onCalculator),
            RoomSign("今日小签",.185f,.703f,.168f,.034f,.112f,.509f,.160f,.224f,onFortune),
            RoomSign("时光列车",.837f,.694f,.171f,.034f,.740f,.558f,.199f,.168f,onTimeMachine)
        )
        Text("阿噜的小窝",Modifier.offset(sceneLeft+sceneWidth*.354f,sceneTop+sceneHeight*.037f)
            .size(sceneWidth*.285f,sceneHeight*.046f)
            .wrapContentSize(Alignment.Center),fontFamily=GuluBrandFont,fontSize=18.sp,color=Color(0xFF6A4C33))
        signs.forEach { sign ->
            Box(Modifier.offset(sceneLeft+sceneWidth*sign.hitX,sceneTop+sceneHeight*sign.hitY)
                .size(sceneWidth*sign.hitW,sceneHeight*sign.hitH)
                .clickable(onClickLabel=sign.text,onClick=sign.action).semantics {contentDescription=sign.text})
            Text(sign.text,Modifier.offset(sceneLeft+sceneWidth*(sign.x-sign.w/2),sceneTop+sceneHeight*(sign.y-sign.h/2))
                .size(sceneWidth*sign.w,sceneHeight*sign.h).clickable(onClickLabel=sign.text,onClick=sign.action)
                .wrapContentSize(Alignment.Center),fontSize=12.sp,fontWeight=FontWeight.Medium,color=Color(0xFF694F37),maxLines=1)
        }
        Box(Modifier.offset(sceneLeft+sceneWidth*.34f,sceneTop+sceneHeight*.713f).size(sceneWidth*.33f,sceneHeight*.190f)
            .clickable(onClickLabel="听阿噜说一句悄悄话",onClick=onPet)
            .semantics {contentDescription="听阿噜说一句悄悄话"})
    }
}

private data class RoomSign(val text:String,val x:Float,val y:Float,val w:Float,val h:Float,
    val hitX:Float,val hitY:Float,val hitW:Float,val hitH:Float,val action:()->Unit)
