package com.jiligulu.app.ui.littleworld

import android.content.SharedPreferences
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The question/cast and bounded analyses survive reopening, including crossing midnight. */
internal class XiaoLiuRenStore(private val prefs: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Serializable private data class AnalysisCache(val entries: Map<String, String> = emptyMap())
    fun session(): LiuRenSession = runCatching {
        json.decodeFromString(LiuRenSession.serializer(), prefs.getString("session_v2", "").orEmpty()).also {
            // Keep an overlong, not-yet-valid draft so validation cannot silently erase it on reopen.
            require(it.question.length <= 4096 && it.digits.length <= 4096)
            it.cast?.checked()
            if (it.step == LiuRenStep.RESULT) require(it.cast != null)
        }
    }.getOrDefault(LiuRenSession())
    fun saveSession(value: LiuRenSession) {
        prefs.edit().putString("session_v2", json.encodeToString(LiuRenSession.serializer(), value)).apply()
    }
    fun analysis(key: String): String? = analysisCache()[key]?.takeIf { it.isNotBlank() && it.length <= LiuRenReadingPolicy.MAX_STORED_CHARS }
    @Synchronized fun saveAnalysis(key: String, reply: String) {
        val values = LinkedHashMap(analysisCache())
        require(reply.length <= LiuRenReadingPolicy.MAX_STORED_CHARS)
        values.remove(key); values[key] = reply
        while (values.size > 16) values.remove(values.keys.first())
        prefs.edit().putString("analysis_v2", json.encodeToString(AnalysisCache.serializer(), AnalysisCache(values))).apply()
    }
    private fun analysisCache(): Map<String, String> = runCatching {
        json.decodeFromString(AnalysisCache.serializer(), prefs.getString("analysis_v2", "").orEmpty()).entries
    }.getOrDefault(emptyMap())
    fun loadForDay(today: LocalDate, initial: () -> XiaoLiuRenInput): XiaoLiuRenInput {
        val values = prefs.all
        val stored = if (values["anchor"] == today.toString()) runCatching {
            val date = LocalDate.parse(values["date"] as String)
            require(date.year in 1900..2100)
            XiaoLiuRenInput(date, values["month"] as Int, values["day"] as Int, values["shichen"] as Int,
                values["leap"] as? Boolean ?: false, values["manual"] as? Boolean ?: false)
        }.getOrNull() else null
        return stored ?: initial().also { save(today, it) }
    }
    fun save(today: LocalDate, input: XiaoLiuRenInput) {
        prefs.edit().putString("anchor", today.toString()).putString("date", input.date.toString())
            .putInt("month", input.lunarMonth).putInt("day", input.lunarDay).putInt("shichen", input.shichen)
            .putBoolean("leap", input.leapMonth).putBoolean("manual", input.manualNumbers).apply()
    }
}
