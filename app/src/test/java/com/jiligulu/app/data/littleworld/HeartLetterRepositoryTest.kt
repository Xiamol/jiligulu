package com.jiligulu.app.data.littleworld

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jiligulu.app.core.ai.AiProviderProfile
import com.jiligulu.app.core.ai.DeepSeekClient
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class HeartLetterRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owners = mutableListOf<CompletableJob>()
    private var fileIndex = 0
    private data class Handle(val world: LittleWorldRepository, val store: DataStore<Preferences>, val owner: CompletableJob)
    private fun open(file: File = File(temporary.root, "heart-${fileIndex++}.preferences_pb")): Handle {
        val owner = SupervisorJob().also { owners += it }
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(owner + Dispatchers.IO), produceFile = { file })
        return Handle(LittleWorldRepository(RuntimeEnvironment.getApplication(), store), store, owner)
    }
    private fun engine(world: LittleWorldRepository, factory: suspend () -> DeepSeekClient,
        onArrived: suspend (FutureNote) -> Unit = {}, timeout: Long = 5000): HeartLetterRepository {
        val owner = SupervisorJob().also { owners += it }
        return HeartLetterRepository(world, factory, onArrived, CoroutineScope(owner + Dispatchers.Default), timeout, { 2000L })
    }
    private val paper get() = SecretPaper("paper-one", "今天的心事", "开会时我没有说出想说的话，有点难过。", 1000)
    private suspend fun settled(repository: HeartLetterRepository) = withTimeout(5000) {
        repository.sendingIds.first { it.isEmpty() }
    }
    @After fun close() = runBlocking { withTimeout(5000) { owners.forEach { it.cancelAndJoin() } } }

    @Test fun sourceIsDurableBeforeNetworkAndDuplicateClicksProduceOneReceiptAndNotification() = runBlocking {
        val handle = open()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val wire = FakeHttp()
        var arrivals = 0
        val repository = engine(handle.world, { started.complete(Unit); release.await(); wire.client() }, { arrivals++ })
        handle.world.saveHeartDraft(paper)
        repository.send(paper)
        started.await()
        val waiting = handle.world.snapshot()
        assertEquals(paper, waiting.writtenSecretPapers.single())
        assertEquals(HeartLetterStatus.PENDING, waiting.heartLetters.single().status)
        assertNull(waiting.heartLetterDraft)
        repeat(3) { repository.send(paper) }
        assertTrue(wire.requests.isEmpty())
        release.complete(Unit)
        settled(repository)
        val done = handle.world.snapshot()
        assertEquals(1, wire.requests.size)
        assertEquals(1, arrivals)
        assertEquals(1, done.futureNotes.size)
        assertEquals("heart-reply-paper-one", done.futureNotes.single().id)
        assertEquals(paper.id, done.futureNotes.single().sourcePaperId)
        assertEquals(2000L, done.futureNotes.single().dueAt)
        assertTrue(done.futureNotes.single().notificationEnabled)
        assertEquals(HeartLetterStatus.REPLIED, done.heartLetters.single().status)
    }

    @Test fun generatedCommandsAreIgnoredAndRequestContainsOnlyThisPaperWithSelectedConnection() = runBlocking {
        val handle = open()
        handle.world.saveWish(Wish(id = "unrelated-wish", title = "不要传给模型的愿望", targetFen = 100))
        val wire = FakeHttp(content = """{"reply":"我听到了你的难过。","bills":[{"action":"delete","target_id":42}],"navigate":"settings","memory_updates":[{"kind":"fact","value":"fake","evidence":"fake"}]}""")
        val repository = engine(handle.world, { wire.client() })
        repository.send(paper)
        settled(repository)
        val request = wire.requests.single()
        assertEquals("fixture-model", request["model"]!!.jsonPrimitive.content)
        val messages = request["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        val user = messages.last().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(user.contains(paper.body))
        assertFalse(request.toString().contains("不要传给模型的愿望"))
        assertFalse("thinking" in request)
        val state = handle.world.snapshot()
        assertEquals("我听到了你的难过。", state.futureNotes.single().body)
        assertEquals("unrelated-wish", state.wishes.single().id)
        assertTrue(state.cards.isEmpty())
    }

    @Test fun failedSendCanRetryTheSameSourceWithoutDuplicatingOrOverwritingItsReceipt() = runBlocking {
        val handle = open()
        val wire = FakeHttp(status = 401)
        val repository = engine(handle.world, { wire.client() })
        repository.send(paper)
        settled(repository)
        assertEquals(HeartLetterStatus.FAILED, handle.world.snapshot().heartLetters.single().status)
        assertTrue(handle.world.snapshot().futureNotes.isEmpty())
        wire.status = 200
        repository.send(paper)
        settled(repository)
        val note = handle.world.snapshot().futureNotes.single()
        handle.world.markNoteRead(note.id)
        val read = handle.world.snapshot().futureNotes.single()
        repository.send(paper)
        assertEquals(2, wire.requests.size)
        assertEquals(read, handle.world.snapshot().futureNotes.single())
        assertEquals(paper, handle.world.snapshot().writtenSecretPapers.single())
        handle.world.deleteFutureNote(note.id)
        repository.send(paper)
        assertEquals(2, wire.requests.size)
        assertTrue(handle.world.snapshot().futureNotes.isEmpty())
    }

    @Test fun explicitCancellationRetainsSourceAndAllowsOneLaterRetry() = runBlocking {
        val handle = open()
        val entered = CompletableDeferred<Unit>()
        val wait = CompletableDeferred<Unit>()
        val wire = FakeHttp()
        var block = true
        val repository = engine(handle.world, {
            if (block) { entered.complete(Unit); wait.await() }
            wire.client()
        })
        repository.send(paper)
        entered.await()
        repository.cancelSending(paper.id)
        assertEquals(HeartLetterStatus.CANCELLED, handle.world.snapshot().heartLetters.single().status)
        assertEquals(paper, handle.world.snapshot().writtenSecretPapers.single())
        assertTrue(handle.world.snapshot().futureNotes.isEmpty())
        assertTrue(wire.requests.isEmpty())
        block = false
        repository.send(paper)
        settled(repository)
        assertEquals(1, handle.world.snapshot().futureNotes.size)
        assertEquals(1, wire.requests.size)
    }

    @Test fun closingTheCallingEditorScopeDoesNotCancelAnAlreadySavedSend() = runBlocking {
        val handle = open()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val wire = FakeHttp()
        val repository = engine(handle.world, { started.complete(Unit); release.await(); wire.client() })
        val editorOwner = SupervisorJob()
        CoroutineScope(editorOwner + Dispatchers.Default).async { repository.send(paper) }.await()
        started.await()
        editorOwner.cancelAndJoin()
        assertTrue(paper.id in repository.sendingIds.value)
        release.complete(Unit)
        settled(repository)
        assertEquals(HeartLetterStatus.REPLIED, handle.world.snapshot().heartLetters.single().status)
        assertEquals(1, wire.requests.size)
    }

    @Test fun boundedRequestTimesOutToRetryableFailureWithoutAnyAutomaticRetry() = runBlocking {
        val handle = open()
        val never = CompletableDeferred<Unit>()
        var calls = 0
        val wire = FakeHttp()
        val repository = engine(handle.world, { calls++; never.await(); wire.client() }, timeout = 40)
        repository.send(paper)
        settled(repository)
        assertEquals(1, calls)
        assertEquals(HeartLetterStatus.FAILED, handle.world.snapshot().heartLetters.single().status)
        assertTrue(wire.requests.isEmpty())
        assertTrue(handle.world.snapshot().futureNotes.isEmpty())
    }

    @Test fun unfinishedSourceSurvivesStoreReopenButConstructingARepositoryNeverResendsIt() = runBlocking {
        val file = File(temporary.root, "reopen.preferences_pb")
        val first = open(file)
        first.world.prepareHeartLetter(paper, 1234)
        first.owner.cancelAndJoin()
        val second = open(file)
        var calls = 0
        val wire = FakeHttp()
        val repository = engine(second.world, { calls++; wire.client() })
        assertTrue(repository.sendingIds.value.isEmpty())
        assertEquals(0, calls)
        assertEquals(paper, second.world.snapshot().heartLetters.single().paper)
        assertEquals(HeartLetterStatus.PENDING, second.world.snapshot().heartLetters.single().status)
        repository.send(paper)
        settled(repository)
        assertEquals(1, calls)
        assertEquals(1, second.world.snapshot().futureNotes.size)
    }

    @Test fun notificationFailureCannotUndoTheSavedReplyOrCauseAnotherPaidRequest() = runBlocking {
        val handle = open()
        val wire = FakeHttp()
        val repository = engine(handle.world, { wire.client() }, { error("synthetic notification failure") })
        repository.send(paper)
        settled(repository)
        assertEquals(HeartLetterStatus.REPLIED, handle.world.snapshot().heartLetters.single().status)
        assertEquals(1, handle.world.snapshot().futureNotes.size)
        repository.send(paper)
        assertEquals(1, wire.requests.size)
    }

    @Test fun draftsPersistAndLateAutosaveCannotRestoreASentOrSavedPaper() = runBlocking {
        val file = File(temporary.root, "draft.preferences_pb")
        val first = open(file)
        val incomplete = paper.copy(title = "", body = "还没写完")
        val future = FutureNote(id = "future-draft", title = "", body = "未来再读", dueAt = 3000)
        first.world.saveHeartDraft(incomplete)
        first.world.saveFutureDraft(future)
        first.owner.cancelAndJoin()
        val second = open(file)
        assertEquals(incomplete, second.world.snapshot().heartLetterDraft)
        assertEquals(future, second.world.snapshot().futureNoteDraft)
        second.world.prepareHeartLetter(paper, 1234)
        second.world.saveHeartDraft(incomplete)
        assertNull(second.world.snapshot().heartLetterDraft)
        val saved = future.copy(title = "未来")
        second.world.saveFutureNote(saved)
        second.world.saveFutureDraft(saved)
        assertNull(second.world.snapshot().futureNoteDraft)
    }

    @Test fun oldCollectionsDecodeWithoutHeartFieldsAndSourceAndReplyCommitAtomically() = runBlocking {
        val handle = open()
        handle.store.edit { it[stringPreferencesKey("state_v1")] = """{"futureNotes":[{"id":"old","title":"旧信","body":"旧正文","dueAt":1234}],"writtenSecretPapers":[]}""" }
        assertNull(handle.world.snapshot().heartLetterDraft)
        assertTrue(handle.world.snapshot().heartLetters.isEmpty())
        handle.world.prepareHeartLetter(paper, 1234)
        val first = handle.world.completeHeartLetter(paper.id, "第一封回信", 2000)!!
        val second = handle.world.completeHeartLetter(paper.id, "不应覆盖", 3000)!!
        assertEquals(first, second)
        val state = handle.world.snapshot()
        assertEquals(2, state.futureNotes.size)
        assertEquals("旧正文", state.futureNotes.first { it.id == "old" }.body)
        assertEquals(first.id, state.heartLetters.single().replyNoteId)
        assertEquals(paper, state.heartLetters.single().paper)
    }

    private class FakeHttp(var status: Int = 200, val content: String = """{"reply":"谢谢你把这张心事纸条寄给我。","bills":[]}""") {
        val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
        private val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            requests += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val response = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                put("message", buildJsonObject { put("content", content) })
            }) }) }.toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        // The interceptor ends the exchange before DNS/socket; all credentials are synthetic.
        fun client() = DeepSeekClient("synthetic-heart-key", AiProviderProfile.custom().copy(
            address = "https://heart-fixture.invalid/v1", model = "fixture-model"), http)
    }
}
