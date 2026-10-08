package com.jiligulu.app.ui.littleworld

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiligulu.app.R
import com.jiligulu.app.core.audio.UiSound
import com.jiligulu.app.ui.components.SpringScrollColumn
import com.jiligulu.app.ui.theme.GuluBrandFont

internal object ChessLobbyColors {
    val ink = Color(0xFF554B5D)
    val accent = Color(0xFF8F7AB4)
    val wash = Color(0xFFEFE9F5)
    val muted = Color(0xFF9B929F)
    val line = Color(0xFFE6DFE7)
}

@Composable
internal fun ColumnScope.NearbyChessLobby(nearby: NearbyRoomsState?, status: String, error: String?,
    onJoin: (String) -> Unit, onRetry: () -> Unit, onControlsBottom: (Float) -> Unit) {
    val context = LocalContext.current
    val failure = error ?: nearby?.error
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = 28.dp), contentAlignment = Alignment.TopCenter) {
        if (maxHeight > 610.dp) ChessLobbyFooter(Modifier.align(Alignment.BottomCenter))
        Column(Modifier.widthIn(max = 328.dp).fillMaxWidth().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("寻找附近的棋友", fontFamily = GuluBrandFont, fontSize = 24.sp, color = ChessLobbyColors.ink)
            Spacer(Modifier.height(20.dp))
            NearbyRadar(nearby?.searching == true && failure == null)
            Spacer(Modifier.height(20.dp))
            if (!nearby?.rooms.isNullOrEmpty()) {
                SpringScrollColumn(Modifier.fillMaxWidth().heightIn(max = 164.dp)) {
                    nearby?.rooms?.forEach { room ->
                        val localId = nearby?.localId.orEmpty()
                        val mayJoin = localId.isNotBlank() && room.id < localId
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = mayJoin, role = Role.Button) {
                                UiSound.select(context); onJoin(room.address)
                            }.padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            TwoChessStones(Modifier.size(38.dp, 30.dp))
                            Text(room.name, Modifier.weight(1f).padding(horizontal = 12.dp),
                                style = MaterialTheme.typography.bodyMedium, color = ChessLobbyColors.ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (mayJoin) Icon(Icons.AutoMirrored.Outlined.ArrowForward, "坐下来",
                                Modifier.size(20.dp), tint = ChessLobbyColors.accent)
                            else Text("等你入座", style = MaterialTheme.typography.labelSmall, color = ChessLobbyColors.muted)
                        }
                        HorizontalDivider(color = ChessLobbyColors.line.copy(alpha = .65f))
                    }
                }
            } else Text(if (failure == null) "棋桌已摆好，等一位小伙伴" else failure,
                Modifier.fillMaxWidth().heightIn(min = 40.dp), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = if (failure == null) ChessLobbyColors.muted else MaterialTheme.colorScheme.error)
            if (failure != null && !nearby?.rooms.isNullOrEmpty()) LobbyStatus(failure, true)
            TextButton(onClick = { UiSound.select(context); onRetry() },
                colors = ButtonDefaults.textButtonColors(contentColor = ChessLobbyColors.accent)) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("再找找")
            }
            Spacer(Modifier.height(4.dp).onGloballyPositioned { onControlsBottom(it.boundsInRoot().bottom) })
        }
    }
}

@Composable
internal fun ColumnScope.OnlineChessLobby(active: Boolean, busy: Boolean, code: String, status: String, error: String?,
    onHost: (String) -> Unit, onJoin: (String) -> Unit, onDisconnect: () -> Unit, onControlsBottom: (Float) -> Unit,
    game: String = "xiangqi") {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var joining by rememberSaveable { mutableStateOf(false) }
    var enteredCode by rememberSaveable(game, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var copied by remember(code) { mutableStateOf(false) }
    val canSubmit = !busy
    val submit = {
        if (canSubmit) {
            val request = roomCodeSubmission(enteredCode.text, joining)
            inputError = request.error
            request.code?.let { normalized ->
                keyboard?.hide(); UiSound.select(context)
                if (joining) onJoin(normalized) else onHost(normalized)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).imePadding().padding(top = 28.dp), contentAlignment = Alignment.TopCenter) {
        if (maxHeight > 610.dp) ChessLobbyFooter(Modifier.align(Alignment.BottomCenter))
        val compact = maxHeight < 440.dp
        val scroll = rememberScrollState()
        val formModifier = Modifier.widthIn(max = 312.dp).fillMaxWidth().padding(horizontal = 16.dp)
        Column(if (compact) formModifier.heightIn(max = maxHeight).verticalScroll(scroll) else formModifier,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (active) "给你留了一张棋桌" else "房间约棋", fontFamily = GuluBrandFont,
                fontSize = 25.sp, color = ChessLobbyColors.ink)
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            if (compact) {
                if (active) Text(code.ifEmpty { "等棋友入座" }, fontSize = 24.sp,
                    letterSpacing = 2.sp, color = ChessLobbyColors.ink)
                else TwoChessStones(Modifier.size(48.dp, 28.dp))
            } else RoomInvitation(if (active) code else "一起下一盘")
            Spacer(Modifier.height(if (compact) 10.dp else 18.dp))
            if (active) {
                if (code.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = {
                        UiSound.select(context); clipboard.setText(AnnotatedString(code)); copied = true
                    }, modifier = Modifier.testTag("room-copy"),
                        colors = ButtonDefaults.textButtonColors(contentColor = ChessLobbyColors.accent)) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp)); Text(if (copied) "已复制" else "复制")
                    }
                    TextButton(onClick = {
                        UiSound.select(context)
                        val invite = ChessRoomInvite(game, code)
                        val text = "来和我下一盘${if (game == "xiangqi") "象棋" else "五子棋"}吧 ♡\n${invite.webUri}\n房间码：$code"
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text)
                        }
                        runCatching { context.startActivity(android.content.Intent.createChooser(send, "邀请棋友")) }
                    }, modifier = Modifier.testTag("room-share"),
                        colors = ButtonDefaults.textButtonColors(contentColor = ChessLobbyColors.accent)) {
                        Icon(Icons.Outlined.Share, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp)); Text("邀请")
                    }
                }
                Spacer(Modifier.height(10.dp))
                LobbyStatus(error ?: status, error != null)
                TextButton(onClick = { UiSound.select(context); onDisconnect() },
                    colors = ButtonDefaults.textButtonColors(contentColor = ChessLobbyColors.accent)) { Text("取消等待") }
            } else {
                Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to "创建", true to "加入").forEach { (value, label) ->
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp))
                            .background(if (joining == value) ChessLobbyColors.wash else Color.Transparent)
                            .clickable(enabled = !busy, role = Role.Tab) { UiSound.select(context); joining = value; inputError = null },
                            contentAlignment = Alignment.Center) {
                            Text(label, color = if (joining == value) ChessLobbyColors.accent else ChessLobbyColors.muted,
                                fontWeight = if (joining == value) FontWeight.Medium else FontWeight.Normal)
                        }
                    }
                }
                Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
                Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("房间码", style = MaterialTheme.typography.bodySmall, color = ChessLobbyColors.muted)
                    key(game) {
                        RoomCodeEditor(initialValue = enteredCode, enabled = !busy,
                            hint = if (joining) "输入棋友的房间码" else "留空自动生成",
                            onValueChange = { enteredCode = it; inputError = null }, onSubmit = submit,
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp).testTag("room-code-input"))
                    }
                }
                HorizontalDivider(color = ChessLobbyColors.accent.copy(alpha = .55f), thickness = 1.dp)
                Spacer(Modifier.height(if (compact) 10.dp else 18.dp))
                Button(onClick = submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth().height(44.dp)
                    .testTag("room-submit"), shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ChessLobbyColors.accent)) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (busy) "正在连接" else if (joining) "加入棋桌" else "邀请棋友")
                }
                if (inputError != null || error != null || busy) LobbyStatus(inputError ?: error ?: status, inputError != null || error != null)
            }
            if (!compact) Text("棋桌保留 5 分钟", Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = ChessLobbyColors.muted)
            Spacer(Modifier.height(4.dp).onGloballyPositioned { onControlsBottom(it.boundsInRoot().bottom) })
        }
    }
}

@Composable
internal fun ChessLobbyFooter(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.game_lobby_footer), null,
        modifier.fillMaxWidth().height(128.dp).alpha(.7f))
}

@Composable
private fun LobbyStatus(text: String, error: Boolean) {
    Text(text, Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center,
        maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else ChessLobbyColors.muted)
}

@Composable
private fun RoomInvitation(code: String) {
    Box(Modifier.size(224.dp, 164.dp), contentAlignment = Alignment.BottomCenter) {
        Image(painterResource(R.drawable.game_room_invitation), null, Modifier.matchParentSize())
        Text(code.ifEmpty { "等棋友入座" }, Modifier.padding(bottom = 24.dp),
            fontSize = if (code.length > 8) 19.sp else 23.sp, letterSpacing = 2.sp,
            fontWeight = FontWeight.Medium, color = ChessLobbyColors.ink)
    }
}

@Composable
private fun NearbyRadar(scanning: Boolean) {
    val sweep = if (scanning) {
        val animation = rememberInfiniteTransition(label = "nearby-radar")
        val angle by animation.animateFloat(0f, 360f, infiniteRepeatable(tween(3500, easing = LinearEasing)), label = "radar-sweep")
        angle
    } else 0f
    Box(Modifier.size(184.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val radius = size.minDimension * .47f
            drawCircle(Brush.radialGradient(listOf(ChessLobbyColors.wash.copy(alpha = .22f),
                ChessLobbyColors.wash.copy(alpha = .8f))), radius)
            for (scale in listOf(.42f, .7f, 1f)) drawCircle(ChessLobbyColors.accent.copy(alpha = .18f),
                radius * scale, style = Stroke(.8.dp.toPx()))
            if (scanning) rotate(sweep) {
                drawArc(ChessLobbyColors.accent.copy(alpha = .10f), -34f, 34f, true,
                    topLeft = center - Offset(radius, radius), size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2))
                drawLine(ChessLobbyColors.accent.copy(alpha = .38f), center, center + Offset(radius, 0f), 1.dp.toPx())
            }
            listOf(Offset(-.61f, -.48f), Offset(.70f, .28f), Offset(.42f, -.71f)).forEachIndexed { index, point ->
                val p = center + point * radius
                drawCircle(Color(0xFF6A606F).copy(alpha = .12f), 8.dp.toPx(), p + Offset(0f, 1.5.dp.toPx()))
                drawCircle(if (index % 2 == 0) Color(0xFF635C68) else Color(0xFFFFFCF3), 7.dp.toPx(), p)
                drawCircle(Color.White.copy(alpha = .3f), 2.dp.toPx(), p - Offset(2.dp.toPx(), 2.dp.toPx()))
            }
        }
        Image(painterResource(R.drawable.gulu_idle), null, Modifier.size(76.dp))
    }
}

@Composable
internal fun TwoChessStones(modifier: Modifier = Modifier.size(64.dp, 38.dp)) {
    Canvas(modifier) {
        listOf(Offset(size.width * .30f, size.height * .53f), Offset(size.width * .68f, size.height * .47f)).forEachIndexed { i, p ->
            drawCircle(Color(0xFFB8A087).copy(alpha = .18f), size.height * .33f, p + Offset(0f, 2.dp.toPx()))
            drawCircle(if (i == 0) Color(0xFF625565) else Color(0xFFF3E3CB), size.height * .33f, p)
            drawCircle(Color.White.copy(alpha = .25f), size.height * .08f, p - Offset(2.dp.toPx(), 2.dp.toPx()))
        }
    }
}
