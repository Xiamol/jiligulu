package com.jiligulu.app.ui.littleworld

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jiligulu.app.JiliguluApp
import com.jiligulu.app.R
import com.jiligulu.app.ui.components.SpringScrollColumn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private val tinyPrizes=listOf("喝一口水","歇两分钟","写封未来信","看一张照片","夸夸自己","伸个懒腰","想个小愿望","听一句悄悄话")
private val secretNotes=listOf(
    "这里没有待办清单，只有一张留给你的小椅子。","阿噜把今天的好运藏在了你的口袋里。",
    "小芽悄悄长高了一点，你也可以慢慢来。","今天不用成为厉害的大人，当个快乐的小孩也可以。",
    "一瓶星星装不下所有喜欢，那就慢慢再攒一瓶。","别担心，刚刚那点走神，阿噜替你保密。",
    "秘密基地营业中：疲惫可以先寄放在这里。","如果今天是普通的一天，那就收藏普通的快乐。",
    "阿噜不是来催你的，是来陪你坐一会儿的。","抬头看看窗外，那也是你今天的一页。",
    "你不需要每次都赢，玩得开心就已经算数。","这张纸条没有大道理：好好吃饭，睡个好觉 ♡"
)

@Composable
fun SecretBaseScreen(onBack:()->Unit,onOpenNotes:()->Unit,onOpenMemories:()->Unit) {
    val context=LocalContext.current
    val prefs=(context.applicationContext as JiliguluApp).container.userPrefs
    val best by prefs.secretStarBest.collectAsStateWithLifecycle(0)
    val sleeping by prefs.secretPetSleeping.collectAsStateWithLifecycle(false)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var spinning by remember { mutableStateOf(false) }
    var prize by rememberSaveable { mutableStateOf<String?>(null) }
    val rotation=remember { Animatable(0f) }
    val scope=rememberCoroutineScope()
    var spinJob by remember {mutableStateOf<kotlinx.coroutines.Job?>(null)}
    var running by remember { mutableStateOf(false) }
    var score by rememberSaveable { mutableIntStateOf(0) }
    var seconds by rememberSaveable { mutableIntStateOf(20) }
    var target by remember { mutableIntStateOf(4) }
    var caught by remember { mutableStateOf(false) }
    var note by rememberSaveable { mutableIntStateOf(0) }
    fun rest(value:Boolean) { running=false;spinJob?.cancel();scope.launch {kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable){prefs.setSecretPetSleeping(value)}} }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event-> if(event==Lifecycle.Event.ON_STOP) {running=false;spinJob?.cancel()} }
        lifecycle.addObserver(observer);onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(running) {
        if(running) {
            repeat(40) { tick-> delay(500);target=(target+Random.nextInt(1,9))%9;caught=false;seconds=20-(tick+1)/2 }
            prefs.keepSecretStarBest(score);running=false
        }
    }
    SpringScrollColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().padding(horizontal=18.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)) {
        WorldScenePanel(WorldScene.SECRET,if(sleeping) "轻轻叫醒阿噜" else "让阿噜打个盹",onBack,
            if(sleeping) emptyList() else listOf("小转盘","接星星","秘密纸条"),tab,{running=false;tab=it},
            if(sleeping) "叫醒阿噜" else "打个盹",{rest(!sleeping)},onObject={rest(!sleeping)},sleeping=sleeping)
        if(!sleeping) {
        when(tab) {
            0->{
                Box(Modifier.fillMaxWidth().height(272.dp),contentAlignment=Alignment.Center) {
                    val font=remember { context.resources.getFont(R.font.zcool_kuaile) }
                    Canvas(Modifier.size(250.dp).graphicsLayer { rotationZ=rotation.value }) {
                        val colors=listOf(Color(0xFFDCCFF1),Color(0xFFF6D5DD),Color(0xFFF8E5BB),Color(0xFFCEE5D6))
                        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            typeface=font;textSize=10.dp.toPx();color=android.graphics.Color.rgb(70,56,86);textAlign=android.graphics.Paint.Align.CENTER
                        }
                        repeat(8) { i ->
                            drawArc(colors[i%4],i*45f-90f,45f,true)
                            val canvas=drawContext.canvas.nativeCanvas;val saved=canvas.save()
                            canvas.rotate(i*45f-67.5f,center.x,center.y)
                            canvas.drawText(tinyPrizes[i],center.x+size.width*.27f,center.y+4.dp.toPx(),paint)
                            canvas.restoreToCount(saved)
                        }
                        drawCircle(Color(0xFFFFFBF4),size.width*.16f)
                        drawContext.canvas.nativeCanvas.drawText("阿噜",center.x,center.y+5.dp.toPx(),paint)
                    }
                    Text("▼",fontSize=24.sp,color=MaterialTheme.colorScheme.primary,modifier=Modifier.align(Alignment.TopCenter))
                }
                Button(onClick={if(!spinning) spinJob=scope.launch {
                    spinning=true;prize=null
                    try { val winner=Random.nextInt(8);val stop=360f-(winner*45f+22.5f)
                        rotation.animateTo(rotation.value+1800f+(stop-rotation.value%360f+360f)%360f,tween(1900,easing=FastOutSlowInEasing))
                        rotation.snapTo(rotation.value%360f);prize=tinyPrizes[winner]
                    } finally {spinning=false}
                } },enabled=!spinning,modifier=Modifier.align(Alignment.CenterHorizontally)) { Text(if(spinning) "好运在路上…" else "转一下，交给阿噜") }
                prize?.let { result ->
                    Text("这次的小任务：$result ♡",color=MaterialTheme.colorScheme.primary,modifier=Modifier.align(Alignment.CenterHorizontally))
                    if(result=="写封未来信") TextButton(onClick=onOpenNotes,modifier=Modifier.align(Alignment.CenterHorizontally)) {Text("去寄一封")}
                    if(result=="看一张照片") TextButton(onClick=onOpenMemories,modifier=Modifier.align(Alignment.CenterHorizontally)) {Text("翻翻纪念册")}
                    if(result=="听一句悄悄话") TextButton(onClick={tab=2},modifier=Modifier.align(Alignment.CenterHorizontally)) {Text("去听悄悄话")}
                }
                Text("今天的小开心，阿噜替你随机挑一个 ♡",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            1->{
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("还有 ${seconds}s · 接到 $score 颗",color=MaterialTheme.colorScheme.primary)
                    Text("最好 $best 颗",style=MaterialTheme.typography.bodySmall)
                }
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    repeat(3) { row->Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        repeat(3) { col->val i=row*3+col
                            Surface(onClick={if(running&&target==i&&!caught){score++;caught=true}},enabled=running,
                                shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.primaryContainer.copy(alpha=.3f),
                                modifier=Modifier.weight(1f).height(72.dp)) {
                                Box(contentAlignment=Alignment.Center) {
                                    Text(if(running&&target==i&&!caught) "🌟" else if(target==i&&caught) "♡" else "·",fontSize=28.sp)
                                }
                            }
                        }
                    } }
                }
                Button(onClick={score=0;seconds=20;caught=false;target=Random.nextInt(9);running=true},enabled=!running,
                    modifier=Modifier.align(Alignment.CenterHorizontally)) {Text(if(running) "阿噜帮你数着呢" else "开始接星星")}
                Text("星星亮起时点一下，20 秒后收好成绩。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else->{
                AlbumPaperPage { Text("阿噜偷偷写给你",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
                    Text(secretNotes[note%secretNotes.size],style=MaterialTheme.typography.bodyLarge,modifier=Modifier.padding(vertical=18.dp)) }
                TextButton(onClick={note=(note+1)%secretNotes.size},modifier=Modifier.align(Alignment.CenterHorizontally)) {Text("再翻一张小秘密 ♡")}
            }
        }
        } else AlbumPaperPage { Text("嘘，秘密基地暂时小声营业 ♡",style=MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(20.dp))
    }
}
