package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.launch
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.jiligulu.app.R
import com.jiligulu.app.ui.theme.GuluBrandFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The room fills its viewport; art, painted captions and touch targets share one transform. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InteractiveRoomStage(onWishes:()->Unit,onLetters:()->Unit,onAlbum:()->Unit,
    onCalculator:()->Unit,onFortune:()->Unit,onTimeMachine:()->Unit,onPet:()->Unit,
    modifier:Modifier=Modifier, onMailbox:()->Unit={}, onSettings:(()->Unit)?=null,
    controlsActive:Boolean=true, mailboxLoading:Boolean=false, unreadCount:Int=0,onSecretLogo:(()->Unit)?=null) {
    var whisper by remember { mutableStateOf<String?>(null) }
    var whisperToken by remember { mutableIntStateOf(0) }
    var petTaps by remember { mutableIntStateOf(0) }
    val sparkle=remember {Animatable(0f)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(whisperToken) {if(whisper!=null){kotlinx.coroutines.delay(2700);whisper=null}}
    fun speak(text:String) {whisper=text;whisperToken++;scope.launch {sparkle.snapTo(1f);sparkle.animateTo(0f,tween(900))}}
    val resources=LocalContext.current.resources
    val art by produceState<ImageBitmap?>(LittleWorldArtwork.cachedImage(R.drawable.world_interactive_room_v3),resources) {
        value=withContext(Dispatchers.IO) {LittleWorldArtwork.image(resources,R.drawable.world_interactive_room_v3)}
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
                    filterQuality=FilterQuality.Medium,colorFilter=MutedSceneColorFilter)
            }
        }
        SkinSceneDecor(Modifier.matchParentSize(),paintBackground=false)
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
            .combinedClickable(onClick={speak("小窝营业啦，今天也有一张椅子留给你 ♡")},onLongClick={onSecretLogo?.invoke()})
            .wrapContentSize(Alignment.Center),fontFamily=GuluBrandFont,fontSize=18.sp,color=Color(0xFF6A4C33))
        signs.forEach { sign ->
            Box(Modifier.offset(sceneLeft+sceneWidth*sign.hitX,sceneTop+sceneHeight*sign.hitY)
                .size(sceneWidth*sign.hitW,sceneHeight*sign.hitH)
                .combinedClickable(onClickLabel=sign.text,onClick=sign.action,onLongClick={speak(when(sign.text){
                    "时光列车"->"阿噜把旧车票都替你收好啦，慢慢回去看看。"
                    "星星愿望"->"愿望慢慢攒，小星星不会催你 ♡"
                    "小算盘"->"拨拨小珠子，再难的数字也能慢慢算。"
                    else->"这里藏着一页小日子，阿噜替你保管着。"
                })}).semantics {contentDescription=sign.text})
            Text(sign.text,Modifier.offset(sceneLeft+sceneWidth*(sign.x-sign.w/2),sceneTop+sceneHeight*(sign.y-sign.h/2))
                .size(sceneWidth*sign.w,sceneHeight*sign.h).clickable(onClickLabel=sign.text,onClick=sign.action)
                .wrapContentSize(Alignment.Center),fontSize=12.sp,fontWeight=FontWeight.Medium,color=Color(0xFF694F37),maxLines=1)
        }
        Box(Modifier.offset(sceneLeft+sceneWidth*.34f,sceneTop+sceneHeight*.713f).size(sceneWidth*.33f,sceneHeight*.190f)
            .combinedClickable(onClickLabel="摸摸阿噜",onClick={
                val lines=listOf("摸摸收到了，阿噜又精神一点啦 ♡","今天可以慢一点，我陪你。","偷偷说，阿噜刚刚给你留了一颗好运。","摸摸的好运，阿噜都替你收到了。")
                speak(lines[petTaps++%lines.size])
            },onLongClick=onPet)
            .semantics {contentDescription="听阿噜说一句悄悄话"})

        Canvas(Modifier.matchParentSize()) {
            val a=sparkle.value
            if(a>0f) repeat(5) {i ->
                val x=(sceneLeft+sceneWidth*(.40f+i*.05f)).toPx()
                val y=(sceneTop+sceneHeight*(.80f-(1f-a)*.10f)).toPx()
                drawCircle(Color(0xFFF2C9C9).copy(alpha=a*.65f),3.dp.toPx(),androidx.compose.ui.geometry.Offset(x,y))
            }
        }
        whisper?.let {text -> Surface(Modifier.offset(sceneLeft+sceneWidth*.25f,sceneTop+sceneHeight*.61f)
            .width(sceneWidth*.5f).clickable {whisper=null},shape=MaterialTheme.shapes.medium,
            color=MaterialTheme.colorScheme.tertiaryContainer.copy(alpha=.94f)) {
            Text(text,Modifier.padding(10.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurface)
        } }
        // The physical envelope and brass gear are painted into the room. Their
        // transparent adjoining targets avoid overlap and stay below system chrome.
        val density=LocalDensity.current
        val statusInset=with(density) { WindowInsets.statusBars.getTop(density).toDp() }
        val controlWidth=48.dp
        val split=(sceneLeft+sceneWidth*.784f).coerceIn(controlWidth,maxWidth-controlWidth)
        val controlTop=maxOf(statusInset,sceneTop+sceneHeight*.028f)
        val controlHeight=maxOf(48.dp,sceneHeight*.078f)
        val mailboxDescription=when {
            mailboxLoading -> "阿噜的小信箱，正在整理来信"
            unreadCount>0 -> "阿噜的小信箱，${unreadCount}封未读来信"
            else -> "阿噜的小信箱"
        }
        Box(Modifier.offset(split-controlWidth,controlTop).size(controlWidth,controlHeight)
            .then(if(controlsActive) Modifier.testTag("main-mailbox") else Modifier)
            .clickable(enabled=controlsActive&&!mailboxLoading,role=Role.Button,
                onClickLabel="打开阿噜的小信箱",onClick=onMailbox)
            .semantics { contentDescription=mailboxDescription })
        Box(Modifier.offset(split,controlTop).size(controlWidth,controlHeight)
            .then(if(controlsActive) Modifier.testTag("room-settings") else Modifier)
            .clickable(enabled=controlsActive&&onSettings!=null,role=Role.Button,
                onClickLabel="打开设置",onClick={onSettings?.invoke()})
            .semantics { contentDescription="设置" })
        Text("信箱",Modifier.offset(sceneLeft+sceneWidth*.696f,sceneTop+sceneHeight*.081f)
            .size(sceneWidth*.078f,sceneHeight*.024f).wrapContentSize(Alignment.Center),
            fontSize=11.sp,fontWeight=FontWeight.Medium,color=Color(0xFF694F37),maxLines=1)
        Text("设置",Modifier.offset(sceneLeft+sceneWidth*.801f,sceneTop+sceneHeight*.081f)
            .size(sceneWidth*.064f,sceneHeight*.024f).wrapContentSize(Alignment.Center),
            fontSize=11.sp,fontWeight=FontWeight.Medium,color=Color(0xFF694F37),maxLines=1)
        if(unreadCount>0||mailboxLoading) Text(if(mailboxLoading) "…" else if(unreadCount>9) "9+" else unreadCount.toString(),
            Modifier.offset(sceneLeft+sceneWidth*.7635f,sceneTop+sceneHeight*.0305f)
                .size(sceneWidth*.019f,sceneHeight*.016f).wrapContentSize(Alignment.Center),
            fontSize=7.sp,lineHeight=8.sp,fontWeight=FontWeight.Medium,color=Color(0xFF694F37),maxLines=1)
    }
}

private data class RoomSign(val text:String,val x:Float,val y:Float,val w:Float,val h:Float,
    val hitX:Float,val hitY:Float,val hitW:Float,val hitH:Float,val action:()->Unit)
