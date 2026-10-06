package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.Canvas
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
            TwoChessStones()
            Text("附近的棋桌", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium, color = Color(0xFF665762))
            Text("两个人连接同一个 Wi-Fi，就能在这里找到彼此。", Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF9C8D98))
            Spacer(Modifier.height(20.dp))
            if (nearby?.rooms.isNullOrEmpty()) {
                Text("暂时还没看到伙伴，邀请对方也打开“附近的人”。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF887D89))
            } else SpringScrollColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                nearby?.rooms?.forEach { room ->
                    Row(Modifier.fillMaxWidth().clickable {
                        UiSound.tap(context); onJoin(room.address)
                    }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(10.dp)) { drawCircle(Color(0xFF9EBD9A)) }
                        Text(room.name, Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF6D5D72), maxLines = 1)
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, "坐下来", Modifier.size(18.dp), tint = Color(0xFF98869F))
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
    onHost: () -> Unit, onJoin: (String) -> Unit, onDisconnect: () -> Unit, onControlsBottom: (Float) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var address by rememberSaveable { mutableStateOf("") }
    var copied by remember(code) { mutableStateOf(false) }
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
                Button(onClick = { UiSound.tap(context); onHost() }, enabled = !busy,
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
private fun TwoChessStones() {
    Canvas(Modifier.size(64.dp, 38.dp)) {
        listOf(Offset(size.width*.30f, size.height*.53f), Offset(size.width*.68f, size.height*.47f)).forEachIndexed { i, p ->
            drawCircle(Color(0xFFB8A087).copy(alpha=.18f), size.height*.33f, p+Offset(0f,2.dp.toPx()))
            drawCircle(if (i==0) Color(0xFF625565) else Color(0xFFF3E3CB), size.height*.33f, p)
            drawCircle(Color.White.copy(alpha=.25f), size.height*.08f, p-Offset(2.dp.toPx(),2.dp.toPx()))
        }
    }
}
