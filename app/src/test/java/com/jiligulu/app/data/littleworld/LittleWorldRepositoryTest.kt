package com.jiligulu.app.data.littleworld

import android.app.Application
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class LittleWorldRepositoryTest {
    @Test fun collectionsPersistAndWishDepositsAreAtomicAndUndoable() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = LittleWorldRepository(context)
        val id = UUID.randomUUID().toString()
        val wish = Wish(id = id, title = "测试愿望", targetFen = 200)
        repo.saveWish(wish)
        coroutineScope { (1..20).map { async { repo.deposit(id, 10) } }.awaitAll() }
        val done = repo.snapshot().wishes.first { it.id == id }
        assertEquals(200L, done.savedFen)
        assertEquals(20, done.deposits.size)
        assertNotNull(done.completedAt)
        assertTrue(runCatching { repo.deposit(id, 10) }.isFailure)
        repo.removeDeposit(id, done.deposits.last().id)
        val undone = repo.snapshot().wishes.first { it.id == id }
        assertEquals(190L, undone.savedFen)
        assertNull(undone.completedAt)
        val reloaded = LittleWorldRepository(context).snapshot().wishes.first { it.id == id }
        assertEquals(undone, reloaded)
        assertTrue(runCatching { repo.deposit(id,-1) }.isFailure)
    }
    @Test fun stickerAndFutureNoteLifecyclePreserveDataAndPromotionHappensOnce() = runBlocking {
        val repo = LittleWorldRepository(RuntimeEnvironment.getApplication())
        val id = UUID.randomUUID().toString()
        val sticker = Sticker(id=id,title="公交",emoji="🚌",amountFen=200)
        repo.saveSticker(sticker)
        repo.moveSticker(id,-1)
        assertEquals(1,repo.snapshot().stickers.count{it.id==id})
        assertTrue(runCatching {repo.saveSticker(sticker.copy(amountFen=0))}.isFailure)
        val waiting = WaitingWish(title="耳机",amountFen=60000)
        repo.saveWaiting(waiting)
        val wishId=repo.promoteWaiting(waiting.id,60000)
        assertEquals("耳机",repo.snapshot().wishes.first{it.id==wishId}.title)
        assertTrue(repo.snapshot().waiting.first{it.id==waiting.id}.archived)
        assertTrue(runCatching{repo.promoteWaiting(waiting.id,60000)}.isFailure)
        val note=FutureNote(title="未来",body="好好吃饭",dueAt=1)
        repo.saveFutureNote(note)
        repo.markNotePresented(note.id)
        val shown=repo.snapshot().futureNotes.first{it.id==note.id}
        assertNull(shown.readAt)
        assertEquals(note.body,shown.body)
        repo.markNoteRead(note.id)
        assertNotNull(repo.snapshot().futureNotes.first{it.id==note.id}.readAt)
        repo.toggleFortune(1);val once=repo.snapshot().favoriteFortunes.contains(1)
        repo.toggleFortune(1);assertNotEquals(once,repo.snapshot().favoriteFortunes.contains(1))
    }
}
