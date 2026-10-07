package com.jiligulu.app.ui.littleworld

import java.net.URI
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiligulu.app.R

data class ChessPlayerProfile(val name: String = "阿噜的朋友", val avatarId: String = "aru") {
    fun normalized() = copy(name = name.filterNot(Char::isISOControl).trim().take(16).ifEmpty { "阿噜的朋友" },
        avatarId = avatarId.takeIf { it in AVATARS } ?: "aru")
    companion object { val AVATARS = listOf("aru", "cat", "leaf", "moon", "star") }
}

data class ChessRoomInvite(val game: String, val code: String) {
    val uri: String get() = "jiligulu://chess/join?game=$game&code=$code"
    companion object {
        fun parse(value: String?): ChessRoomInvite? = runCatching {
            val uri = URI(value ?: return null)
            if (uri.scheme != "jiligulu" || uri.host != "chess" || uri.path != "/join" || uri.fragment != null) return null
            val fields = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
            if (fields.any { it.size != 2 } || fields.map { it[0] }.distinct().size != fields.size) return null
            val query = fields.associate { it[0] to it[1] }
            val game = query["game"]?.takeIf { it == "xiangqi" || it == "gomoku" } ?: return null
            val code = RoomRoundRules.code(query["code"].orEmpty()) ?: return null
            ChessRoomInvite(game, code)
        }.getOrNull()
    }
}

@Composable
internal fun ChessAvatar(profile: ChessPlayerProfile, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(ChessLobbyColors.wash), contentAlignment = Alignment.Center) {
        if (profile.avatarId == "aru") Image(painterResource(R.drawable.gulu_idle), null, Modifier.fillMaxSize().padding(3.dp))
        else Text(when (profile.avatarId) { "cat" -> "🐱"; "leaf" -> "🌿"; "moon" -> "🌙"; else -> "⭐" }, fontSize = 23.sp)
    }
}

@Composable
internal fun ChessProfileEditor(profile: ChessPlayerProfile, onDismiss: () -> Unit, onSave: (ChessPlayerProfile) -> Unit) {
    var name by remember(profile) { mutableStateOf(profile.name) }
    var avatar by remember(profile) { mutableStateOf(profile.avatarId) }
    SecretWoodDialog("棋友名片", onDismiss, confirmLabel = "收好", onConfirm = { onSave(ChessPlayerProfile(name, avatar).normalized()) },
        dismissLabel = "稍后", compactWidth = 300.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ChessPlayerProfile.AVATARS.forEach { id ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { avatar = id }.padding(4.dp)) {
                    ChessAvatar(ChessPlayerProfile(name, id), Modifier.size(38.dp))
                    Text(if (id == avatar) "•" else " ", color = ChessLobbyColors.accent)
                }
            }
        }
        androidx.compose.foundation.text.BasicTextField(name, { name = it.filterNot(Char::isISOControl).take(16) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(color = SecretWoodInk),
            decorationBox = { inner -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("昵称", style = MaterialTheme.typography.bodySmall, color = ChessLobbyColors.muted)
                Box(Modifier.weight(1f).padding(start = 16.dp)) { inner() }
            } })
        HorizontalDivider(color = ChessLobbyColors.accent.copy(alpha = .4f))
    }
}

@Composable
internal fun ChessPlayerSeat(profile: ChessPlayerProfile, color: Color, side: String, active: Boolean,
    status: String, modifier: Modifier = Modifier, alignEnd: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start) {
        ChessAvatar(profile, Modifier.size(38.dp))
        Column(Modifier.padding(start = 7.dp), horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
            Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                color = if (active) ChessLobbyColors.ink else ChessLobbyColors.muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Text(" $side · $status", maxLines = 1, style = MaterialTheme.typography.labelSmall,
                    color = if (active) ChessLobbyColors.accent else ChessLobbyColors.muted)
            }
        }
    }
}
