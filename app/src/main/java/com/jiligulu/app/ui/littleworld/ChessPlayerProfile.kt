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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import com.jiligulu.app.core.audio.UiSound
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiligulu.app.R

data class ChessPlayerProfile(val name: String = "阿噜的朋友", val avatarId: String = "aru", val avatarJpeg: String = "") {
    fun normalized() = copy(name = name.filterNot(Char::isISOControl).trim().take(16).ifEmpty { "阿噜的朋友" },
        avatarId = avatarId.takeIf { it in AVATARS } ?: "aru", avatarJpeg = ChessAvatarPhoto.normalizedJpeg(avatarJpeg))
    override fun toString() = "ChessPlayerProfile(avatarId=$avatarId, hasPhoto=${avatarJpeg.isNotEmpty()})"
    companion object { val AVATARS = listOf("aru", "cat", "leaf", "moon", "star") }
}

data class ChessRoomInvite(val game: String, val code: String) {
    val uri: String get() = "jiligulu://chess/join?game=$game&code=$code"
    val webUri: String get() = "https://xiamol.github.io/jiligulu/join/?game=$game&code=$code"
    /** A self invitation is navigation back to the existing desk, never a reconnect request. */
    fun matchesLiveRoom(currentGame: String?, currentCode: String?, active: Boolean): Boolean =
        active && currentGame == game && RoomRoundRules.code(currentCode.orEmpty())?.let { it == code } == true
    companion object {
        fun parse(value: String?): ChessRoomInvite? = runCatching {
            val uri = URI(value ?: return null)
            if (uri.rawUserInfo != null || uri.port != -1 || uri.fragment != null) return null
            val supported = when (uri.scheme) {
                "jiligulu" -> uri.host == "chess" && uri.rawPath == "/join"
                "https" -> uri.host == "xiamol.github.io" && uri.rawPath in setOf("/jiligulu/join", "/jiligulu/join/")
                else -> false
            }
            if (!supported) return null
            val fields = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
            if (fields.size != 2 || fields.any { it.size != 2 } || fields.map { it[0] }.toSet() != setOf("game", "code")) return null
            val query = fields.associate { it[0] to it[1] }
            val game = query["game"]?.takeIf { it == "xiangqi" || it == "gomoku" } ?: return null
            val code = RoomRoundRules.code(query["code"].orEmpty()) ?: return null
            ChessRoomInvite(game, code)
        }.getOrNull()
    }
}

@Composable
internal fun ChessAvatar(profile: ChessPlayerProfile, modifier: Modifier = Modifier) {
    val photograph = remember(profile.avatarJpeg) { ChessAvatarPhoto.decodePreview(profile.avatarJpeg)?.asImageBitmap() }
    Box(modifier.clip(CircleShape).background(ChessLobbyColors.wash), contentAlignment = Alignment.Center) {
        if (photograph != null) Image(photograph, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (profile.avatarId == "aru") Image(painterResource(R.drawable.gulu_idle), null, Modifier.fillMaxSize().padding(3.dp))
        else Text(when (profile.avatarId) { "cat" -> "🐱"; "leaf" -> "🌿"; "moon" -> "🌙"; else -> "⭐" }, fontSize = 23.sp)
    }
}

@Composable
internal fun ChessProfileEditor(profile: ChessPlayerProfile, onDismiss: () -> Unit, onSave: (ChessPlayerProfile) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(profile) { mutableStateOf(TextFieldValue(profile.name)) }
    var avatar by remember(profile) { mutableStateOf(profile.avatarId) }
    var photograph by remember(profile) { mutableStateOf(profile.avatarJpeg) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var requestEpoch by remember { mutableLongStateOf(0) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            importJob?.cancel()
            val epoch = ++requestEpoch
            importing = true; error = null
            importJob = scope.launch {
                try {
                    val imported = ChessAvatarPhoto.importPhoto(context, uri)
                    currentCoroutineContext().ensureActive()
                    if (epoch == requestEpoch) photograph = imported
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (epoch == requestEpoch) error = "这张图片没能读到，原头像还在" }
                finally { if (epoch == requestEpoch) importing = false }
            }
        }
    }
    SecretWoodDialog("棋友名片", onDismiss, confirmLabel = "保存名片", confirmEnabled = !importing,
        onConfirm = { onSave(ChessPlayerProfile(name.text, avatar, photograph).normalized()) }, compactWidth = 300.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ChessAvatar(ChessPlayerProfile(name.text, avatar, photograph), Modifier.size(60.dp).testTag("chess-profile-avatar-preview"))
            Column {
                Text("给棋友认认你", style = MaterialTheme.typography.labelMedium, color = ChessLobbyColors.muted)
                TextButton(onClick = {
                    UiSound.navigate(context)
                    runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        .onFailure { error = "相册暂不可用，原头像还在" }
                }, enabled = !importing, modifier = Modifier.testTag("chess-profile-photo")) {
                    Text(if (importing) "正在整理头像…" else "从相册选择")
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ChessPlayerProfile.AVATARS.forEach { id ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                    UiSound.select(context)
                    requestEpoch++; importJob?.cancel(); importing = false; error = null
                    avatar = id; photograph = ""
                }.padding(4.dp)) {
                    ChessAvatar(ChessPlayerProfile(name.text, id), Modifier.size(38.dp))
                    Text(if (id == avatar && photograph.isEmpty()) "•" else " ", color = ChessLobbyColors.accent)
                }
            }
        }
        androidx.compose.foundation.text.BasicTextField(name, { name = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("chess-profile-name"), singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(color = SecretWoodInk),
            decorationBox = { inner -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("昵称", style = MaterialTheme.typography.bodySmall, color = ChessLobbyColors.muted)
                Box(Modifier.weight(1f).padding(start = 16.dp)) { inner() }
            } })
        HorizontalDivider(color = ChessLobbyColors.accent.copy(alpha = .4f))
        Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.CenterStart) {
            Text(error ?: "照片头像用于新版棋友，旧版仍显示原来的小头像", style = MaterialTheme.typography.labelSmall,
                color = if (error == null) ChessLobbyColors.muted else MaterialTheme.colorScheme.error, maxLines = 2)
        }
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
