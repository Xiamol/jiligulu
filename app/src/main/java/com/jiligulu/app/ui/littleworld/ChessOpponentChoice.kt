package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiligulu.app.core.audio.UiSound

internal enum class ChessOpponent { ARU, NEARBY, ROOM, SAME_PHONE }

@Composable
internal fun ColumnScope.ChessOpponentChoicePage(chess: Boolean, onChoose: (ChessOpponent) -> Unit) {
    val context=LocalContext.current
    Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center) {
        SecretWoodSurface(Modifier.widthIn(max=332.dp).fillMaxWidth(.92f),
            contentPadding=PaddingValues(horizontal=23.dp,vertical=25.dp)) {
            Row(Modifier.fillMaxWidth().padding(start=44.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("和谁下一盘？",style=MaterialTheme.typography.titleLarge,color=SecretWoodInk)
                Spacer(Modifier.weight(1f))
                Canvas(Modifier.size(42.dp,32.dp)) {
                    val left=Offset(size.width*.3f,size.height*.45f);val right=Offset(size.width*.72f,size.height*.60f)
                    drawCircle(Color(0xFF967955).copy(alpha=.20f),size.height*.28f,left+Offset(0f,2.dp.toPx()))
                    drawCircle(if(chess)Color(0xFFE5C79A)else Color(0xFF47414A),size.height*.28f,left)
                    drawCircle(Color(0xFF967955).copy(alpha=.20f),size.height*.28f,right+Offset(0f,2.dp.toPx()))
                    drawCircle(Color(0xFFFFF3D8),size.height*.28f,right)
                    drawCircle(Color(0xFFAF8A5F),size.height*.28f,right,style=Stroke(.7.dp.toPx()))
                }
            }
            val rows=listOf(
                Triple(ChessOpponent.ARU,"和阿噜下","阿噜在这儿，随时陪你过两招"),
                Triple(ChessOpponent.NEARBY,"附近的人","同一个 Wi-Fi，雷达找棋友"),
                Triple(ChessOpponent.ROOM,"房间约棋","自己起个房间号，或加入伙伴"),
                Triple(ChessOpponent.SAME_PHONE,"同屏双人","两个人，一部手机，轮流落子"))
            rows.forEachIndexed { index,(value,title,detail)->
                Row(Modifier.fillMaxWidth().height(68.dp).clickable(role=Role.Button){UiSound.select(context);onChoose(value)},
                    verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp),contentAlignment=Alignment.Center) {
                        Canvas(Modifier.matchParentSize()){drawCircle(Color(0xFFAF8A5F).copy(alpha=.55f),style=Stroke(.8.dp.toPx()))}
                        Text(listOf("阿","近","约","双")[index],fontSize=14.sp,color=SecretWoodInk)
                    }
                    Column(Modifier.weight(1f).padding(start=12.dp)) {
                        Text(title,style=MaterialTheme.typography.titleMedium,color=SecretWoodInk)
                        Text(detail,style=MaterialTheme.typography.bodySmall,color=SecretWoodInk.copy(alpha=.72f),maxLines=1)
                    }
                    Text("›",fontSize=23.sp,color=SecretWoodInk.copy(alpha=.6f))
                }
                if(index<rows.lastIndex) HorizontalDivider(color=Color(0xFF99764D).copy(alpha=.18f))
            }
        }
    }
}
