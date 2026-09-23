package com.jiligulu.app.data.announcement

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime

@Serializable
data class Announcement(
    val id: String,
    val title: String,
    val body: String,
    val summary: String = "",
    val emoji: String = "💌",
    val startsAt: String? = null,
    val endsAt: String? = null,
    val priority: Int = 0,
    val enabled: Boolean = true
) {
    fun activeAt(now: Long): Boolean = enabled &&
        (startsAt == null || now >= instant(startsAt)) && (endsAt == null || now < instant(endsAt))

    companion object {
        internal fun instant(value: String): Long = OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }
}

@Serializable
data class AnnouncementFeed(val schemaVersion: Int = 1, val announcements: List<Announcement> = emptyList())

object AnnouncementCodec {
    const val MAX_BYTES = 64 * 1024
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun decode(raw: String): List<Announcement> {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "公告内容过大" }
        val feed = json.decodeFromString(AnnouncementFeed.serializer(), raw)
        require(feed.schemaVersion == 1 && feed.announcements.size <= 20) { "公告格式暂不支持" }
        require(feed.announcements.map { it.id }.distinct().size == feed.announcements.size) { "公告编号重复" }
        feed.announcements.forEach {
            require(it.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")))
            require(it.title.isNotBlank() && it.title.length <= 80 && it.body.isNotBlank() && it.body.length <= 6000)
            require(it.summary.length <= 160 && it.emoji.length <= 12)
            val start = it.startsAt?.let(Announcement::instant)
            val end = it.endsAt?.let(Announcement::instant)
            require(start == null || end == null || start < end)
        }
        return feed.announcements.sortedWith(compareByDescending<Announcement> { it.priority }.thenBy { it.id })
    }
}
