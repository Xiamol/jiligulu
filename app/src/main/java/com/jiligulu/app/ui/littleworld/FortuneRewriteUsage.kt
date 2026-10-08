package com.jiligulu.app.ui.littleworld

import android.content.SharedPreferences
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class FortuneRewriteKind(val preferenceKey: String) {
    HOROSCOPE("rewritten_day"), LIU_REN("liuren_rewritten_day")
}

/** Preserve the existing horoscope stamp without spending the other game's daily stamp. */
internal class FortuneRewriteUsage(private val prefs: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Serializable private data class Receipts(val entries: List<LiuRenRewriteReceipt> = emptyList())
    companion object { const val LIU_REN_RECEIPTS_KEY = "liuren_rewrite_receipts_v1" }
    fun used(kind: FortuneRewriteKind, date: LocalDate): Boolean = prefs.getString(kind.preferenceKey, "") == date.toString()
    fun mark(kind: FortuneRewriteKind, date: LocalDate): Boolean = synchronized(prefs) {
        if (used(kind, date)) return false
        prefs.edit().putString(kind.preferenceKey, date.toString()).apply()
        return true
    }
    fun liuRenState(cast: LiuRenCast, date: LocalDate): LiuRenRewriteState = synchronized(prefs) {
        val key = LiuRenRewrite.key(cast)
        val receipt = receipts().firstOrNull { it.castKey == key && it.question == cast.question }
        LiuRenRewriteState(receipt, available = receipt == null && !used(FortuneRewriteKind.LIU_REN, date))
    }
    fun rewriteLiuRen(cast: LiuRenCast, date: LocalDate, atMillis: Long = System.currentTimeMillis()): Boolean = synchronized(prefs) {
        require(atMillis >= 0)
        val state = liuRenState(cast, date)
        if (state.applied || !state.available) return false
        val values = receipts().takeLast(15) + LiuRenRewrite.receipt(cast, date.toString(), atMillis)
        // One atomic preference update records both ownership and the spent daily quota.
        prefs.edit().putString(FortuneRewriteKind.LIU_REN.preferenceKey, date.toString())
            .putString(LIU_REN_RECEIPTS_KEY, json.encodeToString(Receipts.serializer(), Receipts(values))).apply()
        true
    }
    private fun receipts(): List<LiuRenRewriteReceipt> = runCatching {
        val raw = prefs.getString(LIU_REN_RECEIPTS_KEY, "").orEmpty()
        if (raw.length > 30_000) return emptyList()
        json.decodeFromString(Receipts.serializer(), raw).entries.takeLast(16).filter {
            it.castKey.matches(Regex("[0-9a-f]{64}")) && it.question.length in 1..180 && it.createdAtMillis >= 0 &&
                it.title.length in 1..40 && it.conclusion.length in 1..180 && it.action.length in 1..180 &&
                runCatching { LocalDate.parse(it.day) }.isSuccess
        }
    }.getOrDefault(emptyList())
}
