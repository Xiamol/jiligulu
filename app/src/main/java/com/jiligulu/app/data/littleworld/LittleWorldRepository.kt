package com.jiligulu.app.data.littleworld

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable data class Sticker(val id: String = UUID.randomUUID().toString(), val title: String = "",
    val emoji: String = "⭐", val amountFen: Long = 0, val type: String = "EXPENSE", val categoryId: Long = -1)
@Serializable data class WishDeposit(val id: String = UUID.randomUUID().toString(), val amountFen: Long,
    val createdAt: Long = System.currentTimeMillis(), val note: String = "")
@Serializable data class Wish(val id: String = UUID.randomUUID().toString(), val title: String,
    val targetFen: Long, val emoji: String = "⭐", val deposits: List<WishDeposit> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(), val completedAt: Long? = null,
    val photoPath: String = "", val caption: String = "") {
    val savedFen: Long get() = deposits.sumOf { it.amountFen }
}
@Serializable data class WaitingWish(val id: String = UUID.randomUUID().toString(), val title: String,
    val amountFen: Long = 0, val emoji: String = "🌱", val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis())
@Serializable data class FutureNote(val id: String = UUID.randomUUID().toString(), val title: String,
    val body: String, val dueAt: Long, val notificationEnabled: Boolean = false,
    val presentedAt: Long? = null, val readAt: Long? = null, val notifiedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis())
@Serializable data class MemoryCard(val id: String = UUID.randomUUID().toString(), val title: String,
    val caption: String = "", val imagePath: String, val createdAt: Long = System.currentTimeMillis())
@Serializable data class LittleWorldState(val stickers: List<Sticker> = defaultStickers(),
    val wishes: List<Wish> = emptyList(), val waiting: List<WaitingWish> = emptyList(),
    val futureNotes: List<FutureNote> = emptyList(), val cards: List<MemoryCard> = emptyList(),
    val favoriteFortunes: Set<Int> = emptySet(), val timeMachineEnabled: Boolean = true)

fun defaultStickers(): List<Sticker> = listOf(
    Triple("早餐", "🍳", 800L), Triple("地铁", "🚇", 300L), Triple("咖啡", "☕", 1200L),
    Triple("午餐", "🍚", 1800L), Triple("奶茶", "🧋", 1000L), Triple("水果", "🍊", 1500L),
    Triple("买菜", "🥬", 2000L), Triple("日用品", "🧻", 1600L), Triple("打车", "🚕", 1200L),
    Triple("停车", "🅿️", 500L), Triple("猫粮", "🐱", 3000L), Triple("晚餐", "🍜", 2000L)
).mapIndexed { index, (title, emoji, amount) -> Sticker("preset-$index", title, emoji, amount) }

private val Context.littleWorldStore by preferencesDataStore(name = "little_world")

/** User collections are independent of ledger totals; updates are atomic and survive upgrades. */
class LittleWorldRepository(context: Context) {
    private val store = context.applicationContext.littleWorldStore
    private val key = stringPreferencesKey("state_v1")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val state: Flow<LittleWorldState> = store.data.map { decode(it[key]) }
    private fun decode(raw: String?): LittleWorldState =
        if (raw == null) LittleWorldState() else json.decodeFromString(LittleWorldState.serializer(), raw)
    suspend fun snapshot() = state.first()
    private suspend fun update(block: (LittleWorldState) -> LittleWorldState) {
        store.edit { preferences -> preferences[key] = json.encodeToString(LittleWorldState.serializer(), block(decode(preferences[key]))) }
    }
    suspend fun saveSticker(sticker: Sticker) {
        require(sticker.title.trim().isNotEmpty() && sticker.amountFen > 0 && sticker.amountFen <= 99_999_999_999L)
        require(sticker.type in listOf("EXPENSE", "INCOME"))
        update { s -> s.copy(stickers = if (s.stickers.any { it.id == sticker.id }) s.stickers.map { if (it.id == sticker.id) sticker.copy(title = sticker.title.trim()) else it } else s.stickers + sticker.copy(title = sticker.title.trim())) }
    }
    suspend fun deleteSticker(id: String) = update { it.copy(stickers = it.stickers.filterNot { s -> s.id == id }) }
    suspend fun moveSticker(id: String, delta: Int) = update { s ->
        val rows = s.stickers.toMutableList(); val from = rows.indexOfFirst { it.id == id }
        if (from >= 0) { val to = (from + delta).coerceIn(0, rows.lastIndex); rows.add(to, rows.removeAt(from)) }
        s.copy(stickers = rows)
    }
    suspend fun saveWish(wish: Wish) {
        require(wish.title.trim().isNotEmpty() && wish.targetFen in 1..99_999_999_999L)
        update { s ->
            val old = s.wishes.firstOrNull { it.id == wish.id }
            val deposits = old?.deposits ?: wish.deposits
            val saved = deposits.sumOf { it.amountFen }
            val next = wish.copy(title = wish.title.trim(), deposits = deposits,
                createdAt = old?.createdAt ?: wish.createdAt,
                completedAt = if (saved >= wish.targetFen) old?.completedAt ?: System.currentTimeMillis() else null)
            s.copy(wishes = if (old == null) s.wishes + next else s.wishes.map { if (it.id == wish.id) next else it })
        }
    }
    suspend fun deposit(wishId: String, amountFen: Long, note: String = "") {
        require(amountFen in 1..99_999_999_999L)
        update { s ->
            check(s.wishes.any { it.id == wishId }) { "愿望已经不在这里了" }
            s.copy(wishes = s.wishes.map { w ->
            if (w.id != wishId) w else {
                check(w.completedAt == null) { "这个愿望已经实现啦" }
                require(w.savedFen <= 99_999_999_999L - amountFen) { "金额太大了" }
                val deposits = w.deposits + WishDeposit(amountFen = amountFen, note = note.trim())
                w.copy(deposits = deposits, completedAt = if (deposits.sumOf { it.amountFen } >= w.targetFen) System.currentTimeMillis() else null)
            }
        }) }
    }
    suspend fun removeDeposit(wishId: String, depositId: String) = update { s ->
        s.copy(wishes = s.wishes.map { w -> if (w.id != wishId) w else {
            val deposits = w.deposits.filterNot { it.id == depositId }
            w.copy(deposits = deposits, completedAt = if (deposits.sumOf { it.amountFen } >= w.targetFen) w.completedAt else null)
        } })
    }
    suspend fun promoteWaiting(waitingId: String, targetFen: Long): String {
        require(targetFen in 1..99_999_999_999L)
        val newId = UUID.randomUUID().toString()
        update { s ->
            val waiting = checkNotNull(s.waiting.firstOrNull { it.id == waitingId && !it.archived }) { "这个候场愿望已处理" }
            val wish = Wish(id = newId, title = waiting.title, targetFen = targetFen, emoji = waiting.emoji)
            s.copy(wishes = s.wishes + wish, waiting = s.waiting.map { if (it.id == waitingId) it.copy(archived = true) else it })
        }
        return newId
    }
    suspend fun deleteWish(id: String) = update { it.copy(wishes = it.wishes.filterNot { w -> w.id == id }) }
    suspend fun saveWaiting(wish: WaitingWish) {
        require(wish.title.trim().isNotEmpty() && wish.amountFen in 0..99_999_999_999L)
        update { s -> s.copy(waiting = if (s.waiting.any { it.id == wish.id }) s.waiting.map { if (it.id == wish.id) wish else it } else s.waiting + wish) }
    }
    suspend fun archiveWaiting(id: String) = update { it.copy(waiting = it.waiting.map { w -> if (w.id == id) w.copy(archived = true) else w }) }
    suspend fun saveFutureNote(note: FutureNote) {
        require(note.title.trim().isNotEmpty() && note.body.trim().isNotEmpty() && note.dueAt > 0)
        update { s -> s.copy(futureNotes = if (s.futureNotes.any { it.id == note.id }) s.futureNotes.map { if (it.id == note.id) note else it } else s.futureNotes + note) }
    }
    suspend fun deleteFutureNote(id: String) = update { it.copy(futureNotes = it.futureNotes.filterNot { n -> n.id == id }) }
    suspend fun markNotePresented(id: String) = update { it.copy(futureNotes = it.futureNotes.map { n -> if (n.id == id) n.copy(presentedAt = System.currentTimeMillis()) else n }) }
    suspend fun markNoteRead(id: String) = update { it.copy(futureNotes = it.futureNotes.map { n -> if (n.id == id) n.copy(readAt = System.currentTimeMillis(), presentedAt = n.presentedAt ?: System.currentTimeMillis()) else n }) }
    suspend fun markNoteNotified(id: String) = update { it.copy(futureNotes = it.futureNotes.map { n -> if (n.id == id) n.copy(notifiedAt = System.currentTimeMillis()) else n }) }
    suspend fun saveCard(card: MemoryCard) = update { it.copy(cards = it.cards.filterNot { c -> c.id == card.id } + card) }
    suspend fun deleteCard(id: String) = update { it.copy(cards = it.cards.filterNot { c -> c.id == id }) }
    suspend fun toggleFortune(id: Int) = update { it.copy(favoriteFortunes = if (id in it.favoriteFortunes) it.favoriteFortunes - id else it.favoriteFortunes + id) }
    suspend fun setTimeMachine(enabled: Boolean) = update { it.copy(timeMachineEnabled = enabled) }
}
