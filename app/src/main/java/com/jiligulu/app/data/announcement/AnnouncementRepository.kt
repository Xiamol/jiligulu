package com.jiligulu.app.data.announcement

import com.jiligulu.app.data.prefs.UserPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

data class AnnouncementState(
    val loading: Boolean = true,
    val entries: List<Announcement> = emptyList(),
    val automaticId: String? = null,
    val manualId: String? = null,
    val emptyMailboxOpen: Boolean = false,
    val saving: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null
) {
    val opened: Announcement? get() = entries.find { it.id == (manualId ?: automaticId) }
}

/** AppContainer owns one instance per process. Closing is session-local; muting is durable per id. */
class AnnouncementRepository(
    private val prefs: UserPrefs,
    private val fetch: suspend (String) -> String = ::fetchAnnouncements,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(AnnouncementState())
    val state = mutable.asStateFlow()
    private var initialized = false
    private var feed: List<Announcement> = emptyList()
    private var muted = emptySet<String>()

    suspend fun initialize() = mutex.withLock {
        if (initialized) return@withLock
        try {
            val saved = prefs.readAnnouncements()
            muted = saved.mutedIds
            feed = runCatching { AnnouncementCodec.decode(saved.cachedFeed) }.getOrDefault(emptyList())
            mutable.value = AnnouncementState(loading = true, entries = feed.filter { it.activeAt(now()) })
            var offline = false
            if (saved.source.isBlank()) {
                feed = emptyList()
            } else {
                try {
                    val raw = fetch(saved.source)
                    val parsed = AnnouncementCodec.decode(raw)
                    prefs.cacheAnnouncements(raw)
                    feed = parsed
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { offline = true }
            }
            val active = feed.filter { it.activeAt(now()) }
            mutable.value = AnnouncementState(loading = false, entries = active,
                automaticId = active.firstOrNull { it.id !in muted }?.id, offline = offline)
            initialized = true
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            mutable.value = AnnouncementState(loading = false, offline = true)
            initialized = true // Optional content must never block access to the ledger.
        }
    }

    /** User refresh updates the mailbox without scheduling another automatic popup. */
    suspend fun refresh() = mutex.withLock {
        mutable.value = mutable.value.copy(loading = true, error = null)
        try {
            val source = prefs.readAnnouncements().source
            val raw = if (source.isBlank()) "{\"schemaVersion\":1,\"announcements\":[]}" else fetch(source)
            val parsed = AnnouncementCodec.decode(raw)
            prefs.cacheAnnouncements(raw)
            feed = parsed
            updateTime()
            mutable.value = mutable.value.copy(offline = false)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(offline = true, error = "暂时没收到新来信，已保留原来的信笺。")
        } finally { mutable.value = mutable.value.copy(loading = false) }
    }

    /** Expiration changes the home list; it never schedules a second automatic popup this process. */
    fun updateTime() {
        val active = feed.filter { it.activeAt(now()) }
        mutable.value = mutable.value.copy(entries = active,
            automaticId = mutable.value.automaticId?.takeIf { id -> active.any { it.id == id } },
            manualId = mutable.value.manualId?.takeIf { id -> active.any { it.id == id } })
    }

    fun open(id: String) {
        updateTime()
        if (id.isBlank() && mutable.value.entries.isEmpty()) {
            mutable.value = mutable.value.copy(emptyMailboxOpen = true, error = null)
        } else if (mutable.value.entries.any { it.id == id }) {
            mutable.value = mutable.value.copy(manualId = id, emptyMailboxOpen = false, error = null)
        }
    }

    fun close() {
        if (!mutable.value.saving) mutable.value = mutable.value.copy(automaticId = null, manualId = null, emptyMailboxOpen = false, error = null)
    }

    suspend fun muteOpened() = mutex.withLock {
        val id = mutable.value.opened?.id ?: return@withLock
        if (mutable.value.saving) return@withLock
        mutable.value = mutable.value.copy(saving = true, error = null)
        try {
            prefs.muteAnnouncement(id)
            muted = muted + id
            mutable.value = mutable.value.copy(saving = false, automaticId = null, manualId = null)
        } catch (cancelled: CancellationException) {
            mutable.value = mutable.value.copy(saving = false)
            throw cancelled
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(saving = false, error = "这次没能记住选择，请再试一次。")
        }
    }
}

private val announcementClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).followSslRedirects(false).build()

private suspend fun fetchAnnouncements(url: String): String = withContext(Dispatchers.IO) {
    val uri = URI(url)
    require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null)
    val call = announcementClient.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
    val response = suspendCancellableCoroutine<Response> { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }
    response.use {
        check(it.isSuccessful) { "Announcement HTTP ${it.code}" }
        val body = checkNotNull(it.body)
        require(body.contentLength() <= AnnouncementCodec.MAX_BYTES)
        val bytes = body.byteStream().readBytesLimited(AnnouncementCodec.MAX_BYTES)
        bytes.toString(Charsets.UTF_8)
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        require(output.size() + read <= limit)
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
