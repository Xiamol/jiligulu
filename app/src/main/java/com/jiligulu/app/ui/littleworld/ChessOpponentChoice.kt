package com.jiligulu.app.ui.littleworld

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiligulu.app.R
import com.jiligulu.app.core.audio.UiSound
import com.jiligulu.app.ui.theme.GuluBrandFont

internal enum class ChessOpponent { ARU, NEARBY, ROOM, SAME_PHONE }

@Composable
internal fun ColumnScope.ChessOpponentChoicePage(chess: Boolean, onChoose: (ChessOpponent) -> Unit) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = 28.dp), contentAlignment = Alignment.TopCenter) {
        if (maxHeight > 610.dp) ChessLobbyFooter(Modifier.align(Alignment.BottomCenter))
        Column(Modifier.widthIn(max = 312.dp).fillMaxWidth().padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("棋友会", fontFamily = GuluBrandFont, fontSize = 28.sp, color = ChessLobbyColors.ink)
            Text("好棋友，从这里相遇", Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = ChessLobbyColors.muted)
            Box(Modifier.fillMaxWidth().height(132.dp), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.gulu_idle), null, Modifier.size(106.dp).padding(bottom = 10.dp))
                TwoChessStones(Modifier.align(Alignment.BottomCenter).size(66.dp, 32.dp))
            }
            Spacer(Modifier.height(14.dp))
            val rows = listOf(
                OpponentRow(ChessOpponent.ARU, "和阿噜下", "随时陪你过两招", Icons.Outlined.Spa),
                OpponentRow(ChessOpponent.NEARBY, "附近的人", "同一 Wi-Fi 的棋友", Icons.Outlined.Radar),
                OpponentRow(ChessOpponent.ROOM, "房间约棋", "一张邀请，连起远方", Icons.Outlined.Home),
                OpponentRow(ChessOpponent.SAME_PHONE, "同屏双人", "两个人，轮流落子", Icons.Outlined.Groups))
            rows.forEachIndexed { i, item ->
                Row(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button) { UiSound.select(context); onChoose(item.value) }
                    .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(item.icon, null, Modifier.size(23.dp), tint = ChessLobbyColors.accent)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium, color = ChessLobbyColors.ink)
                        Text(item.detail, style = MaterialTheme.typography.bodySmall, color = ChessLobbyColors.muted)
                    }
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(17.dp), tint = ChessLobbyColors.accent)
                }
                if (i < rows.lastIndex) HorizontalDivider(Modifier.padding(start = 44.dp), color = ChessLobbyColors.line.copy(alpha = .7f))
            }
        }
    }
}

private data class OpponentRow(val value: ChessOpponent, val title: String, val detail: String, val icon: ImageVector)
