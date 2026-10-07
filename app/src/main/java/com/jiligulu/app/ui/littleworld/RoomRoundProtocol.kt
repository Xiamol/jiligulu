package com.jiligulu.app.ui.littleworld

import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID

internal data class RoomAssignment(val round: Int, val hostPlayer: Int, val revision: Int)
internal enum class RoomCloseReason(val hint: String) {
    DECLINED("棋友没有接受这次邀请"), INVITE_TIMEOUT("邀请等待超时，回附近再找找吧"),
    RESULT_TIMEOUT("这局已结束，房间已收好"), NO_REMATCH("这局已结束，期待下次再见"), LEFT("棋友离开了，房间已关闭"),
}
internal sealed interface RoomControl {
    data class Hello(val name: String,val guestId:String="",val targetId:String="",val avatarId:String="aru") : RoomControl
    data class Start(val assignment: RoomAssignment) : RoomControl
    data class Vote(val round: Int, val revision: Int, val accept: Boolean, val sequence: Int = 1) : RoomControl
    data class Votes(val round: Int, val revision: Int, val hostReady: Boolean, val guestReady: Boolean, val guestSequence: Int = 0) : RoomControl
    data class Close(val round: Int, val revision: Int, val reason: RoomCloseReason) : RoomControl
    data class Presence(val round: Int, val revision: Int, val player: Int, val background: Boolean) : RoomControl
    data class Resign(val round: Int, val revision: Int, val player: Int) : RoomControl
    data class Resigned(val round: Int, val revision: Int, val player: Int) : RoomControl
}

/** Host identity and playing color are independent. Consent belongs to a single round/revision. */
internal object RoomRoundRules {
    const val RESULT_SECONDS = 30
    const val INVITE_MILLIS = 20_000L
    const val WAITING_MILLIS = 300_000L
    const val RECONNECT_GRACE_MILLIS = 300_000L
    const val CONNECTION_QUIET_MILLIS = 20_000L
    const val MAX_ROUND = 10_000
    fun initial(hostPlayer: Int): RoomAssignment { require(hostPlayer in 1..2); return RoomAssignment(1, hostPlayer, 0) }
    fun next(current: RoomAssignment, revision: Int, hostReady: Boolean, guestReady: Boolean): RoomAssignment? =
        if (!hostReady || !guestReady || revision == Int.MAX_VALUE || current.round >= MAX_ROUND) null
        else RoomAssignment(current.round + 1, 3 - current.hostPlayer, revision + 1)
    fun accepts(current: RoomAssignment?, revision: Int, candidate: RoomAssignment, localAgreed: Boolean, bothReady: Boolean): Boolean {
        if (current == null) return candidate.round == 1 && candidate.revision == 0 && candidate.hostPlayer in 1..2
        return localAgreed && bothReady && candidate == next(current, revision, true, true)
    }
    fun willingNearbyHost(localId: String?, hello: RoomControl.Hello): Boolean =
        localId != null && localId.matches(Regex("[a-f0-9]{12}")) && hello.targetId == localId &&
            hello.guestId.matches(Regex("[a-f0-9]{12}")) && hello.guestId > localId
    fun code(value: String): String? = value.trim().uppercase().takeIf { it.matches(Regex("[A-Z0-9]{4,12}")) }
    fun randomCode(): String = UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
    fun name(value: String): String = value.filter { !it.isISOControl() }.trim().take(16).ifEmpty { "棋友" }
    fun avatar(value: String): String = value.takeIf { it in setOf("aru", "cat", "leaf", "moon", "star") } ?: "aru"
}

internal object RoomControlCodec {
    fun encode(control: RoomControl): String = when (control) {
        is RoomControl.Hello -> "HELLO_NAME|" + Base64.getUrlEncoder().withoutPadding().encodeToString(RoomRoundRules.name(control.name).toByteArray(Charsets.UTF_8))+"|${control.guestId.ifEmpty {"-"}}|${control.targetId.ifEmpty {"-"}}|${RoomRoundRules.avatar(control.avatarId)}"
        is RoomControl.Start -> with(control.assignment) { "START|$round|$hostPlayer|$revision" }
        is RoomControl.Vote -> "VOTE|${control.round}|${control.revision}|${if(control.accept) 1 else 0}|${control.sequence}"
        is RoomControl.Votes -> "VOTES|${control.round}|${control.revision}|${if(control.hostReady) 1 else 0}|${if(control.guestReady) 1 else 0}|${control.guestSequence}"
        is RoomControl.Close -> "CLOSE|${control.round}|${control.revision}|${control.reason.name}"
        is RoomControl.Presence -> "PRESENCE|${control.round}|${control.revision}|${control.player}|${if(control.background) 1 else 0}"
        is RoomControl.Resign -> "RESIGN|${control.round}|${control.revision}|${control.player}"
        is RoomControl.Resigned -> "RESIGNED|${control.round}|${control.revision}|${control.player}"
    }
    fun decode(line: String): RoomControl {
        val p=line.split('|')
        fun number(at:Int, range:IntRange):Int { require(p[at].matches(Regex("\\d{1,10}")));return p[at].toInt().also {require(it in range)} }
        return try { when(p.firstOrNull()) {
            "HELLO_NAME" -> {require(p.size in 4..5 && p[1].matches(Regex("[A-Za-z0-9_-]{1,86}")))
                val bytes=Base64.getUrlDecoder().decode(p[1]);require(bytes.size<=64)
                val name=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                require(name==RoomRoundRules.name(name))
                require((p[2]=="-")==(p[3]=="-"))
                require(p[2]=="-" || p[2].matches(Regex("[a-f0-9]{12}")) && p[3].matches(Regex("[a-f0-9]{12}")) && p[2]!=p[3])
                val avatar = p.getOrNull(4) ?: "aru";require(avatar==RoomRoundRules.avatar(avatar))
                RoomControl.Hello(name,if(p[2]=="-") "" else p[2],if(p[3]=="-") "" else p[3],avatar)}
            "START" -> {require(p.size==4);RoomControl.Start(RoomAssignment(number(1,1..RoomRoundRules.MAX_ROUND),number(2,1..2),number(3,0..Int.MAX_VALUE)))}
            "VOTE" -> {require(p.size==5);RoomControl.Vote(number(1,1..RoomRoundRules.MAX_ROUND),number(2,0..Int.MAX_VALUE),number(3,0..1)==1,number(4,1..Int.MAX_VALUE))}
            "VOTES" -> {require(p.size==6);RoomControl.Votes(number(1,1..RoomRoundRules.MAX_ROUND),number(2,0..Int.MAX_VALUE),number(3,0..1)==1,number(4,0..1)==1,number(5,0..Int.MAX_VALUE))}
            "CLOSE" -> {require(p.size==4);RoomControl.Close(number(1,0..RoomRoundRules.MAX_ROUND),number(2,0..Int.MAX_VALUE),RoomCloseReason.valueOf(p[3]))}
            "PRESENCE" -> {require(p.size==5);RoomControl.Presence(number(1,1..RoomRoundRules.MAX_ROUND),number(2,0..Int.MAX_VALUE),number(3,1..2),number(4,0..1)==1)}
            "RESIGN" -> {require(p.size==4);RoomControl.Resign(number(1,1..RoomRoundRules.MAX_ROUND),number(2,0..Int.MAX_VALUE),number(3,1..2))}
            "RESIGNED" -> {require(p.size==4);RoomControl.Resigned(number(1,1..RoomRoundRules.MAX_ROUND),number(2,1..Int.MAX_VALUE),number(3,1..2))}
            else -> throw LanProtocolException()
        } } catch(_:Exception) {throw LanProtocolException()}
    }
}
