package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.jiligulu.app.ui.components.CompactFormField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiligulu.app.core.audio.UiSound
import com.jiligulu.app.ui.components.SpringScrollColumn

@Composable
internal fun ColumnScope.NearbyChessLobby(nearby: NearbyRoomsState?, status: String, error: String?,
    onJoin: (String) -> Unit, onRetry: () -> Unit, onControlsBottom: (Float) -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            NearbyRadar(nearby?.searching==true && error==null && nearby?.error==null)
            Text("附近的棋桌", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium, color = Color(0xFF665762))
            Text("两个人连接同一个 Wi-Fi，就能在这里找到彼此。", Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF9C8D98))
            Spacer(Modifier.height(20.dp))
            if (nearby?.rooms.isNullOrEmpty()) {
                Text("正在找棋友，邀请对方也打开“附近的人”。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF887D89))
            } else SpringScrollColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                nearby?.rooms?.forEach { room ->
                    val localId = nearby?.localId.orEmpty()
                    val mayJoin = localId.isNotBlank() && room.id < localId
                    Row(Modifier.fillMaxWidth().clickable(enabled = mayJoin) {
                        UiSound.tap(context); onJoin(room.address)
                    }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(10.dp)) { drawCircle(Color(0xFF9EBD9A)) }
                        Text(room.name, Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF6D5D72), maxLines = 1)
                        if (mayJoin) Icon(Icons.AutoMirrored.Outlined.ArrowForward, "坐下来", Modifier.size(18.dp), tint = Color(0xFF98869F))
                        else Text("等待靠近", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9C8D98))
                    }
                    HorizontalDivider(color = Color(0xFFEAE1E5))
                }
            }
            Text(error ?: nearby?.error ?: status, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
                color = if (error != null || nearby?.error != null) MaterialTheme.colorScheme.error else Color(0xFF9C8D98))
            TextButton(onClick = { UiSound.tap(context); onRetry() }) { Text("再找找") }
            Spacer(Modifier.height(8.dp).onGloballyPositioned { onControlsBottom(it.boundsInRoot().bottom) })
        }
    }
}

@Composable
internal fun ColumnScope.OnlineChessLobby(active: Boolean, busy: Boolean, code: String, status: String, error: String?,
    onHost: (String) -> Unit, onJoin: (String) -> Unit, onDisconnect: () -> Unit, onControlsBottom: (Float) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var address by rememberSaveable { mutableStateOf("") }
    var copied by remember(code) { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var desiredCode by rememberSaveable { mutableStateOf("") }
    if(creating) SecretWoodDialog("给棋桌起个房间码",{creating=false},confirmLabel="创建",dismissLabel="稍后",
        compactWidth=280.dp,confirmEnabled=desiredCode.isBlank() || RoomRoundRules.code(desiredCode)!=null,
        onConfirm={creating=false;onHost(desiredCode.trim())}) {
        CompactFormField("房间码",desiredCode,{desiredCode=it.uppercase().filter {char->char in 'A'..'Z' || char in '0'..'9'}.take(12)},
            placeholder="留空自动生成",keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii))
        Text("4–12位英文或数字，棋友输入同一个码就能找到你。",style=MaterialTheme.typography.bodySmall)
    }
    Box(Modifier.fillMaxWidth().weight(1f).imePadding(), contentAlignment = Alignment.Center) {
        SpringScrollColumn(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TwoChessStones()
            Text(if (active) "等棋友坐下来" else "和远方的棋友下一盘", Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.titleMedium, color = Color(0xFF665762))
            Text("创建房间，把房间码告诉对方就好。", Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF9C8D98))
            Spacer(Modifier.height(20.dp))
            if (active) {
                if (code.isNotEmpty()) {
                    Text(code, fontSize = 24.sp, color = Color(0xFF766A7F))
                    TextButton(onClick = { UiSound.tap(context); clipboard.setText(AnnotatedString(code)); copied = true }) {
                        Text(if (copied) "复制好啦 ♡" else "复制房间码")
                    }
                }
                TextButton(onClick = { UiSound.tap(context); onDisconnect() }) { Text("取消等待") }
            } else {
                Button(onClick = { UiSound.tap(context); creating=true }, enabled = !busy,
                    modifier = Modifier.widthIn(max = 250.dp).fillMaxWidth(.8f).height(42.dp)) { Text("创建房间") }
                Text("或者加入棋友", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFF9C8D98))
                Row(Modifier.fillMaxWidth().widthIn(max = 330.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(address, { address = it.take(16) }, singleLine = true,
                        placeholder = { Text("输入房间码", style = MaterialTheme.typography.bodySmall) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.weight(1f).heightIn(max = 58.dp))
                    TextButton(onClick = { UiSound.tap(context); onJoin(address.trim()) }, enabled = address.isNotBlank() && !busy) { Text("加入") }
                }
            }
            Text(error ?: status, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error else Color(0xFF9C8D98))
            Text("切去分享时，棋桌会替你留两分钟。", Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.labelSmall, color = Color(0xFF9C8D98))
            Spacer(Modifier.height(8.dp).onGloballyPositioned { onControlsBottom(it.boundsInRoot().bottom) })
        }
    }
}

@Composable
private fun NearbyRadar(scanning:Boolean) {
    val sweep=if(scanning) {
        val animation=rememberInfiniteTransition(label="nearby-radar")
        val angle by animation.animateFloat(0f,360f,infiniteRepeatable(tween(2800,easing=LinearEasing)),label="radar-sweep")
        angle
    } else 0f
    Canvas(Modifier.size(124.dp)) {
        val ink=Color(0xFF9184A1);val radius=size.minDimension*.45f
        drawCircle(Color(0xFFF0ECEF),radius)
        for(scale in listOf(.35f,.68f,1f)) drawCircle(ink.copy(alpha=.18f),radius*scale,style=Stroke(1.dp.toPx()))
        drawLine(ink.copy(alpha=.1f),Offset(center.x-radius,center.y),Offset(center.x+radius,center.y),1.dp.toPx())
        drawLine(ink.copy(alpha=.1f),Offset(center.x,center.y-radius),Offset(center.x,center.y+radius),1.dp.toPx())
        if(scanning) rotate(sweep) {drawArc(Color(0xFF9DAF9E).copy(alpha=.20f),-36f,36f,true,
            topLeft=Offset(center.x-radius,center.y-radius),size=androidx.compose.ui.geometry.Size(radius*2,radius*2));
            drawLine(Color(0xFF93AA98).copy(alpha=.7f),center,Offset(center.x+radius,center.y),1.5.dp.toPx())}
        drawCircle(ink.copy(alpha=.6f),3.dp.toPx())
        listOf(Offset(-.45f,-.28f),Offset(.45f,.3f),Offset(.1f,-.6f)).forEach {point->
            drawCircle(Color(0xFFB5A1BD).copy(alpha=if(scanning) .65f else .25f),2.5.dp.toPx(),center+point*radius)}
    }
}

@Composable
private fun TwoChessStones() {
    Canvas(Modifier.size(64.dp, 38.dp)) {
        listOf(Offset(size.width*.30f, size.height*.53f), Offset(size.width*.68f, size.height*.47f)).forEachIndexed { i, p ->
            drawCircle(Color(0xFFB8A087).copy(alpha=.18f), size.height*.33f, p+Offset(0f,2.dp.toPx()))
            drawCircle(if (i==0) Color(0xFF625565) else Color(0xFFF3E3CB), size.height*.33f, p)
            drawCircle(Color.White.copy(alpha=.25f), size.height*.08f, p-Offset(2.dp.toPx(),2.dp.toPx()))
        }
    }
}
