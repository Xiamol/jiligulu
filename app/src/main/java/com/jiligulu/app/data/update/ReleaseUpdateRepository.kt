package com.jiligulu.app.data.update

import com.jiligulu.app.BuildConfig
import com.jiligulu.app.data.prefs.UserPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int = compareValuesBy(this, other,
        ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)
    companion object {
        fun parse(value: String): ReleaseVersion? {
            val match = Regex("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(value.trim()) ?: return null
            val numbers = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            return ReleaseVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}

data class ReleaseInfo(val version: String, val notes: String, val downloadUrl: String, val pageUrl: String)
data class UpdateState(val checking: Boolean = false, val checked: Boolean = false,
    val available: ReleaseInfo? = null, val error: String? = null)

object GithubReleases {
    fun normalizeRepository(input: String): String? {
        val raw = input.trim().removeSuffix("/").removePrefix("https://github.com/").removeSuffix(".git")
        return raw.takeIf {
            it.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}")) &&
                it.substringAfter('/') !in listOf(".", "..")
        }
    }

    fun parseRelease(repository: String, payload: String): ReleaseInfo {
        require(normalizeRepository(repository) == repository)
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root["draft"]?.jsonPrimitive?.booleanOrNull != true &&
            root["prerelease"]?.jsonPrimitive?.booleanOrNull != true) { "还没有正式发布的版本" }
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(ReleaseVersion.parse(tag) != null) { "版本标签需使用 v0.5.2 这样的格式" }
        val assets = root["assets"]?.jsonArray.orEmpty()
        val download = assets.mapNotNull { asset ->
            val entry = asset.jsonObject
            val name = entry["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val url = entry["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val uri = runCatching { URI(url) }.getOrNull()
            url.takeIf { name.endsWith(".apk", true) && uri?.scheme == "https" &&
                uri.host == "github.com" && uri.rawUserInfo == null &&
                uri.path.startsWith("/$repository/releases/download/", ignoreCase = true) }
        }.firstOrNull() ?: throw IllegalArgumentException("该版本还没有可下载的 APK")
        return ReleaseInfo(tag.removePrefix("v").removePrefix("V"),
            root["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(2500), download,
            "https://github.com/$repository/releases/latest")
    }
}

/** Public release metadata only. No account password, token, or automatic APK installation. */
class ReleaseUpdateRepository(
    private val prefs: UserPrefs,
    private val fetch: suspend (String) -> String = ::fetchGithubRelease
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(UpdateState())
    val state = _state.asStateFlow()

    suspend fun configure(input: String) {
        val repository = if (input.isBlank()) "" else requireNotNull(GithubReleases.normalizeRepository(input)) {
            "请填写 owner/repository 或 GitHub 仓库链接"
        }
        lock.withLock {
            prefs.setUpdateRepository(repository)
            _state.value = UpdateState()
        }
    }

    suspend fun check(automatic: Boolean = false) {
        if (lock.isLocked) return
        lock.withLock {
            try {
                val repository = prefs.updateRepository.first()
                if (repository.isBlank()) return@withLock
                require(GithubReleases.normalizeRepository(repository) == repository) { "更新源格式不正确，请重新设置。" }
                val now = System.currentTimeMillis()
                if (automatic && (!prefs.autoCheckUpdates.first() ||
                    now - prefs.updateCheckedAt.first() in 0 until TimeUnit.HOURS.toMillis(6))) return@withLock
                _state.value = UpdateState(checking = true)
                val release = GithubReleases.parseRelease(repository, fetch(repository))
                val current = requireNotNull(ReleaseVersion.parse(BuildConfig.VERSION_NAME))
                val remote = requireNotNull(ReleaseVersion.parse(release.version))
                _state.value = UpdateState(checked = true, available = release.takeIf { remote > current })
                prefs.setUpdateCheckedAt(now)
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(checking = false)
                throw cancelled
            } catch (failure: Exception) {
                _state.value = UpdateState(error = when (failure) {
                    is ReleaseHttpException -> when (failure.status) {
                        404 -> "还没有找到公开发布的版本，请检查仓库和 Releases。"
                        403, 429 -> "GitHub 暂时限制了请求，晚点再试吧。"
                        else -> "更新服务暂时不可用，请稍后再试。"
                    }
                    is IllegalArgumentException -> failure.message ?: "版本信息暂时无法识别。"
                    else -> "暂时连不上更新服务，请稍后再试。"
                })
                // Back off failed automatic checks as well; a manual check always remains possible.
                try { prefs.setUpdateCheckedAt(System.currentTimeMillis()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* A failed preference write must not claim the check succeeded. */ }
            }
        }
    }
}

private class ReleaseHttpException(val status: Int) : IOException()
private val updateClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()

private suspend fun fetchGithubRelease(repository: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder().url("https://api.github.com/repos/$repository/releases/latest")
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "Jiligulu/${BuildConfig.VERSION_NAME}").build()
    val call = updateClient.newCall(request)
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
        if (!it.isSuccessful) throw ReleaseHttpException(it.code)
        it.body?.string().orEmpty()
    }
}
