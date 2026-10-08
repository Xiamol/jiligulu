package com.jiligulu.app.ui.littleworld

import java.security.MessageDigest

/** Optional metadata channel. Every frame remains below the unchanged 1024-byte game limit. */
internal class RoomAvatarExchange(
    private val localJpeg: String,
    private val online: Boolean,
    private val hosting: Boolean,
    private val advertisedPeerSupport: Boolean,
    private val sendFrames: (List<String>) -> Unit,
    private val showPeer: (String) -> Unit,
    private val nowMillis: () -> Long,
    private val normalize: (String) -> String = ChessAvatarPhoto::normalizedJpeg,
) {
    private data class Transfer(val id: String, val length: Int, val hash: String, val deadline: Long,
        val chunks: MutableList<String> = mutableListOf())
    private var profileAccepted = false
    private var peerCapable = false
    private var capabilitySent = false
    private var photoSent = false
    private var receivedPhoto = false
    private var packets = 0
    private var disabled = false
    private var incoming: Transfer? = null
    val deadline: Long? get() = incoming?.deadline

    fun connected() {
        // An old LAN host never sees a new opcode: only its new NSD TXT enables this offer.
        if (!online && !hosting && advertisedPeerSupport && !capabilitySent) {
            capabilitySent = true; sendFrames(listOf(CAPABILITY))
        }
    }
    fun profileAccepted() { profileAccepted = true; sendPhotoIfReady() }
    fun transportCapability() {
        if (online) { peerCapable = true; sendPhotoIfReady() }
    }
    fun expire() { if (incoming?.let { nowMillis() >= it.deadline } == true) disable() }

    /** Consumes even bad avatar frames so optional artwork cannot invalidate a legal chess move. */
    fun receive(line: String): Boolean {
        if (!line.startsWith(PREFIX)) return false
        expire()
        if (disabled || ++packets > MAX_PACKETS || line.length > 1024) { disable(); return true }
        val p = line.split('|')
        if (p == listOf("AV1", "CAP", "1")) {
            if (!profileAccepted || online || !hosting && !advertisedPeerSupport) return true
            peerCapable = true
            if (!capabilitySent) { capabilitySent = true; sendFrames(listOf(CAPABILITY)) }
            sendPhotoIfReady()
            return true
        }
        if (!profileAccepted || !peerCapable) return true
        if (receivedPhoto) return true
        try {
            when (p.getOrNull(1)) {
                "BEGIN" -> {
                    require(p.size == 5 && incoming == null && p[2].matches(ID) && p[4].matches(HASH))
                    require(p[3].matches(Regex("[0-9]{1,4}")))
                    val length = p[3].toInt()
                    require(length in 4..MAX_BASE64 && length % 4 == 0)
                    incoming = Transfer(p[2], length, p[4], nowMillis() + TIMEOUT_MILLIS)
                }
                "CHUNK" -> {
                    require(p.size == 5)
                    val transfer = checkNotNull(incoming)
                    require(p[2] == transfer.id && p[3].matches(Regex("[0-9]{1,2}")) && p[3].toInt() == transfer.chunks.size)
                    require(transfer.chunks.size < MAX_CHUNKS && p[4].length in 1..CHUNK_CHARS && p[4].matches(BASE64))
                    require(transfer.chunks.sumOf { it.length } + p[4].length <= transfer.length)
                    transfer.chunks += p[4]
                }
                "END" -> {
                    require(p.size == 3)
                    val transfer = checkNotNull(incoming)
                    require(p[2] == transfer.id)
                    val value = transfer.chunks.joinToString("")
                    require(value.length == transfer.length && hash(value) == transfer.hash)
                    val valid = normalize(value)
                    require(valid.isNotEmpty() && valid == value)
                    incoming = null; receivedPhoto = true; showPeer(valid)
                }
                else -> disable()
            }
        } catch (_: Exception) { disable() }
        return true
    }

    private fun disable() { disabled = true; incoming = null }
    private fun sendPhotoIfReady() {
        if (disabled || photoSent || !profileAccepted || !peerCapable) return
        photoSent = true
        val valid = runCatching { normalize(localJpeg) }.getOrDefault("")
        if (valid.isNotEmpty()) runCatching { frames(valid) }.getOrNull()?.let(sendFrames)
    }

    companion object {
        const val PREFIX = "AV1|"
        const val CAPABILITY = "AV1|CAP|1"
        const val MAX_BASE64 = 8192
        const val CHUNK_CHARS = 640
        const val MAX_CHUNKS = 13
        const val MAX_PACKETS = 32
        const val TIMEOUT_MILLIS = 5000L
        private val ID = Regex("[a-f0-9]{16}")
        private val HASH = Regex("[a-f0-9]{64}")
        private val BASE64 = Regex("[A-Za-z0-9+/=]+")
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it.toInt() and 255) }
        internal fun frames(value: String): List<String> {
            require(value.length in 4..MAX_BASE64 && value.length % 4 == 0 && value.matches(BASE64))
            val hash = hash(value)
            val id = hash.take(16)
            return listOf("AV1|BEGIN|$id|${value.length}|$hash") + value.chunked(CHUNK_CHARS).mapIndexed { i, part ->
                "AV1|CHUNK|$id|$i|$part"
            } + "AV1|END|$id"
        }
    }
}
