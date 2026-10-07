package com.jiligulu.app.data.repository

import android.app.Application
import android.content.Context
import com.jiligulu.app.core.ai.AiMemoryUpdate
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.domain.persona.CompanionMemoryPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class CompanionMemoryPreferenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private suspend fun ready(): UserPrefs = UserPrefs(context).also {
        it.clearCompanionMemories(); it.setCompanionMemoryEnabled(true)
    }
    private fun fact() = CompanionMemoryPolicy.accepted("我喜欢骑车", listOf(AiMemoryUpdate("interest", "骑车", "我喜欢骑车")), 1)

    @Test fun localFactsSurviveNewRepositoryInstancesAndCanBeCorrectedDeletedAndDisabled() = runBlocking {
        val prefs = ready(); val original = prefs.companionMemory.first()
        assertTrue(prefs.rememberCompanionFactsIfCurrent(original.revision, fact()))
        val reopened = UserPrefs(context)
        assertEquals("骑车", reopened.companionMemory.first().facts.single().value)
        reopened.correctCompanionMemory(reopened.companionMemory.first().facts.single().id, "游泳")
        val corrected = prefs.companionMemory.first().facts.single()
        assertEquals("游泳", corrected.value); assertTrue(corrected.editedByUser)
        assertEquals("", corrected.evidence)
        reopened.setCompanionMemoryEnabled(false)
        val disabled = prefs.companionMemory.first()
        assertFalse(disabled.enabled)
        assertFalse(disabled.renderForAi().contains("游泳"))
        reopened.removeCompanionMemory(corrected.id)
        assertTrue(prefs.companionMemory.first().facts.isEmpty())
    }

    @Test fun clearingDuringAnInflightReplyCannotRecreateTheRemovedFacts() = runBlocking {
        val prefs = ready(); val snapshot = prefs.companionMemory.first()
        prefs.clearCompanionMemories()
        assertFalse(prefs.rememberCompanionFactsIfCurrent(snapshot.revision, fact()))
        assertTrue(prefs.companionMemory.first().facts.isEmpty())
        val afterClear = prefs.companionMemory.first()
        prefs.correctCompanionMemory("interest:already-removed", "骑车")
        assertFalse(prefs.rememberCompanionFactsIfCurrent(afterClear.revision, fact()))
        assertTrue(prefs.companionMemory.first().facts.isEmpty())
    }

    @Test fun disablingAndReenablingStillInvalidatesOlderReplies() = runBlocking {
        val prefs = ready(); val snapshot = prefs.companionMemory.first()
        prefs.setCompanionMemoryEnabled(false); prefs.setCompanionMemoryEnabled(true)
        assertFalse(prefs.rememberCompanionFactsIfCurrent(snapshot.revision, fact()))
        assertTrue(prefs.companionMemory.first().facts.isEmpty())
    }

    @Test fun correctingOrDeletingDuringAReplyCannotResurrectItsPreviousValue() = runBlocking {
        val prefs = ready(); var snapshot = prefs.companionMemory.first()
        prefs.rememberCompanionFactsIfCurrent(snapshot.revision, fact())
        snapshot = prefs.companionMemory.first()
        prefs.correctCompanionMemory(snapshot.facts.single().id, "游泳")
        assertFalse(prefs.rememberCompanionFactsIfCurrent(snapshot.revision, fact()))
        assertEquals("游泳", prefs.companionMemory.first().facts.single().value)
        snapshot = prefs.companionMemory.first()
        prefs.removeCompanionMemory(snapshot.facts.single().id)
        assertFalse(prefs.rememberCompanionFactsIfCurrent(snapshot.revision, fact()))
        assertTrue(prefs.companionMemory.first().facts.isEmpty())
    }
}
