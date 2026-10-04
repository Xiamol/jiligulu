package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.jiligulu.app.R
import com.jiligulu.app.ui.theme.GuluBrandFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Labels sit on the painted hanging signs. Objects and signs share one generous touch target. */
@Composable
fun InteractiveRoomStage(onWishes:()->Unit,onLetters:()->Unit,onAlbum:()->Unit,
    onCalculator:()->Unit,onFortune:()->Unit,onTimeMachine:()->Unit,onPet:()->Unit,minHeight:Dp=0.dp) {
    val resources=LocalContext.current.resources
    val art by produceState<ImageBitmap?>(LittleWorldArtwork.cachedImage(R.drawable.world_interactive_room_v1),resources) {
        value=withContext(Dispatchers.IO) {LittleWorldArtwork.image(resources,R.drawable.world_interactive_room_v1)}
    }
    val frameHeight=maxOf(minHeight,LocalConfiguration.current.screenWidthDp.dp/.75f)
    BoxWithConstraints(Modifier.fillMaxWidth().height(frameHeight)) {
        art?.let { Image(it,null,Modifier.matchParentSize(),contentScale=ContentScale.FillBounds) }
        val signs=listOf(
            RoomSign("星星愿望",.357f,.277f,.166f,.035f,.205f,.127f,.302f,.192f,onWishes),
            RoomSign("未来信箱",.739f,.283f,.163f,.035f,.626f,.116f,.242f,.220f,onLetters),
            RoomSign("生活纪念册",.356f,.487f,.175f,.032f,.196f,.329f,.333f,.190f,onAlbum),
            RoomSign("小算盘",.713f,.489f,.165f,.035f,.625f,.369f,.186f,.161f,onCalculator),
            RoomSign("今日小签",.185f,.703f,.168f,.034f,.112f,.509f,.160f,.224f,onFortune),
            RoomSign("时光列车",.837f,.694f,.171f,.034f,.740f,.558f,.199f,.168f,onTimeMachine)
        )
        Text("阿噜的小窝",Modifier.offset(maxWidth*.354f,maxHeight*.037f).size(maxWidth*.285f,maxHeight*.046f)
            .wrapContentSize(Alignment.Center),fontFamily=GuluBrandFont,fontSize=18.sp,color=Color(0xFF6A4C33))
        signs.forEach { sign ->
            Box(Modifier.offset(maxWidth*sign.hitX,maxHeight*sign.hitY).size(maxWidth*sign.hitW,maxHeight*sign.hitH)
                .clickable(onClickLabel=sign.text,onClick=sign.action).semantics {contentDescription=sign.text})
            Text(sign.text,Modifier.offset(maxWidth*(sign.x-sign.w/2),maxHeight*(sign.y-sign.h/2))
                .size(maxWidth*sign.w,maxHeight*sign.h).clickable(onClickLabel=sign.text,onClick=sign.action)
                .wrapContentSize(Alignment.Center),fontSize=12.sp,fontWeight=FontWeight.Medium,color=Color(0xFF694F37),maxLines=1)
        }
        Box(Modifier.offset(maxWidth*.34f,maxHeight*.713f).size(maxWidth*.33f,maxHeight*.190f)
            .clickable(onClickLabel="听阿噜说一句悄悄话",onClick=onPet)
            .semantics {contentDescription="听阿噜说一句悄悄话"})
    }
}

private data class RoomSign(val text:String,val x:Float,val y:Float,val w:Float,val h:Float,
    val hitX:Float,val hitY:Float,val hitW:Float,val hitH:Float,val action:()->Unit)
