package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiligulu.app.core.audio.UiSound

/** An anchored result inscription, present until the board is restarted or the player leaves. */
@Composable
internal fun GameFinishPlate(result: GameFinishPresentation, onAgain: () -> Unit, onExit: () -> Unit,
    network: Boolean = false, roomEnded: Boolean = false, secondsLeft: Int = 0,
    myRematchRequested: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val accent = when (result.mood) { FinishMood.WIN -> Color(0xFF8D6F39); FinishMood.LOSE -> Color(0xFF836A63)
        FinishMood.DRAW -> Color(0xFF738373); FinishMood.SHARED -> Color(0xFF97654D) }
    SecretWoodSurface(modifier.fillMaxWidth().semantics { contentDescription = "本局结果：${result.headline}" },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(40.dp)) {
                drawCircle(accent.copy(alpha=.10f), size.width*.46f)
                drawCircle(accent.copy(alpha=.65f), size.width*.44f, style=Stroke(1.dp.toPx()))
                if(result.mood==FinishMood.LOSE) {
                    drawLine(accent,Offset(size.width*.34f,size.height*.34f),Offset(size.width*.66f,size.height*.66f),2.dp.toPx())
                    drawLine(accent,Offset(size.width*.66f,size.height*.34f),Offset(size.width*.34f,size.height*.66f),2.dp.toPx())
                } else if(result.mood==FinishMood.DRAW) {
                    drawLine(accent, Offset(size.width*.28f,size.height*.41f), Offset(size.width*.72f,size.height*.41f),2.dp.toPx())
                    drawLine(accent, Offset(size.width*.28f,size.height*.59f), Offset(size.width*.72f,size.height*.59f),2.dp.toPx())
                } else {
                    drawLine(accent,Offset(size.width*.31f,size.height*.36f),Offset(size.width*.50f,size.height*.65f),2.dp.toPx())
                    drawLine(accent,Offset(size.width*.50f,size.height*.65f),Offset(size.width*.73f,size.height*.31f),2.dp.toPx())
                }
            }
            Column(Modifier.weight(1f).padding(start=12.dp)) {
                Text(result.headline,fontSize=25.sp,fontWeight=FontWeight.SemiBold,color=SecretWoodInk)
                Text(result.detail,style=MaterialTheme.typography.bodySmall,color=SecretWoodInk.copy(alpha=.78f),maxLines=2)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top=4.dp),horizontalArrangement=Arrangement.End,
            verticalAlignment=Alignment.CenterVertically) {
            if(network) Text(if(roomEnded) "棋桌已收好" else "${secondsLeft.coerceAtLeast(0)} 秒",Modifier.weight(1f),
                style=MaterialTheme.typography.labelSmall,color=SecretWoodInk.copy(alpha=.7f))
            TextButton(onClick={UiSound.navigate(context);onExit()},colors=ButtonDefaults.textButtonColors(contentColor=SecretWoodInk)) {Text("收桌")}
            TextButton(onClick={UiSound.select(context);onAgain()},enabled=!myRematchRequested,
                colors=ButtonDefaults.textButtonColors(contentColor=SecretWoodInk)) {
                Text(if(myRematchRequested) "等棋友点头" else if(network&&roomEnded) "再约一盘" else "再来一局")
            }
        }
    }
}
