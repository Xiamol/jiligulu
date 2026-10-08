package com.jiligulu.app.data.prefs

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.jiligulu.app.core.ai.AiConfig
import com.jiligulu.app.core.ai.AiEndpointKind
import com.jiligulu.app.core.ai.AiProviderId
import com.jiligulu.app.core.ai.AiProviderProfile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AiProviderPrefsTest {
    @get:Rule val temporary = TemporaryFolder()
    private var fileIndex = 0

    @Test fun defaultsKeepTheExistingDeepSeekModelAndCustomCapabilitiesStartConservative() = runBlocking {
        withPrefs { prefs, _ ->
            val state = prefs.state.first()
            assertEquals(AiProviderId.DEEPSEEK, state.selected)
            assertEquals(AiConfig.MODEL, state.deepSeekModel)
            assertEquals(AiConfig.MODEL, state.selectedProfile.model)
            assertFalse(state.hasDeepSeekKey)
            assertFalse(state.hasCustomKey)
            assertFalse(state.custom.supportsImages)
            assertFalse(state.custom.jsonMode)
            assertFalse(state.custom.sendsTemperature)
        }
    }

    @Test fun legacyMigrationIsIdempotentAndOnlyStoresTheLegacyKeyInTheDeepSeekSlot() = runBlocking {
        withPrefs { prefs, _ ->
            prefs.saveCustom(custom(), "synthetic-custom-original")
            prefs.select(AiProviderId.CUSTOM)
            prefs.migrateLegacyDeepSeekKey(" synthetic-legacy-original ")
            prefs.migrateLegacyDeepSeekKey("synthetic-legacy-changed")
            assertEquals("synthetic-legacy-original", prefs.savedKey(AiProviderId.DEEPSEEK))
            assertEquals("synthetic-custom-original", prefs.savedKey(AiProviderId.CUSTOM))
            assertEquals(AiProviderId.CUSTOM, prefs.state.first().selected)
            assertEquals("synthetic-custom-original", prefs.connection("synthetic-another-legacy").apiKey)
            // Every persisted file is inside the disposable provider fixture directory.
            assertTrue(temporary.root.walkTopDown().filter { it.isFile }.all { it.name.startsWith("provider-") })
        }
    }

    @Test fun providerSwitchesAndLaterDeepSeekEditsNeverShareCredentialsOrCapabilities() = runBlocking {
        withPrefs { prefs, _ ->
            prefs.saveDeepSeekKey("synthetic-ds-key")
            prefs.saveCustom(custom().copy(supportsImages = true), "synthetic-custom-key")
            prefs.select(AiProviderId.CUSTOM)
            val customConnection = prefs.connection("synthetic-legacy-must-not-win")
            assertEquals(AiProviderId.CUSTOM, customConnection.profile.id)
            assertEquals("synthetic-custom-key", customConnection.apiKey)
            assertEquals("fixture-model", customConnection.profile.model)
            assertTrue(customConnection.profile.supportsImages)
            assertFalse(customConnection.profile.disablesDeepSeekThinking)
            prefs.saveDeepSeekKey("synthetic-ds-updated")
            assertEquals("synthetic-custom-key", prefs.connection("synthetic-legacy").apiKey)
            prefs.select(AiProviderId.DEEPSEEK)
            assertEquals("synthetic-ds-updated", prefs.connection("synthetic-legacy").apiKey)
            assertEquals(AiConfig.MODEL, prefs.connection("synthetic-legacy").profile.model)
            prefs.select(AiProviderId.CUSTOM)
            assertEquals(customConnection.profile, prefs.connection("synthetic-legacy").profile)
        }
    }

    @Test fun clearingCustomKeyAllowsAnonymousLocalEndpointsAndNeverFallsBackToDeepSeek() = runBlocking {
        withPrefs { prefs, _ ->
            prefs.saveDeepSeekKey("synthetic-ds-never-for-custom")
            prefs.saveCustom(custom(), "synthetic-old-custom")
            prefs.select(AiProviderId.CUSTOM)
            prefs.saveCustom(custom(), "")
            assertEquals("", prefs.savedKey(AiProviderId.CUSTOM))
            assertFalse(prefs.state.first().hasCustomKey)
            assertEquals("", prefs.connection("synthetic-legacy").apiKey)
            assertEquals("synthetic-ds-never-for-custom", prefs.savedKey(AiProviderId.DEEPSEEK))
        }
    }

    @Test fun deepSeekModelChoiceIsSeparateFromTheCustomProfileAndProCannotSendImages() = runBlocking {
        withPrefs { prefs, _ ->
            prefs.saveDeepSeekKey("synthetic-ds")
            prefs.saveCustom(custom(), "synthetic-custom")
            prefs.setDeepSeekModel(AiConfig.DEEPSEEK_PRO_MODEL)
            val ds = prefs.connection("synthetic-legacy")
            assertEquals(AiConfig.DEEPSEEK_PRO_MODEL, ds.profile.model)
            assertFalse(ds.profile.supportsImages)
            assertTrue(ds.profile.disablesDeepSeekThinking)
            assertTrue(ds.profile.supportsLegacyTokenLimit)
            assertEquals("fixture-model", prefs.state.first().custom.model)
            assertTrue(runCatching { prefs.setDeepSeekModel("unknown-model") }.isFailure)
            assertEquals(AiConfig.DEEPSEEK_PRO_MODEL, prefs.state.first().deepSeekModel)
        }
    }

    @Test fun savedProfilesKeysAndSelectionSurviveAnActualStoreReopen() = runBlocking {
        val file = File(temporary.root, "provider-persistent.preferences_pb")
        val profile = custom().copy(name = "Fixture images", address = "https://fixture.invalid/complete?version=1",
            endpointKind = AiEndpointKind.CHAT_ENDPOINT, supportsImages = true, jsonMode = true)
        withPrefs(file) { prefs, _ ->
            prefs.saveDeepSeekKey("synthetic-ds")
            prefs.setDeepSeekModel(AiConfig.DEEPSEEK_PRO_MODEL)
            prefs.saveCustom(profile, "synthetic-custom-persisted")
            prefs.select(AiProviderId.CUSTOM)
        }
        withPrefs(file) { prefs, _ ->
            val state = prefs.state.first()
            assertEquals(AiProviderId.CUSTOM, state.selected)
            assertEquals(profile, state.custom)
            assertEquals(AiConfig.DEEPSEEK_PRO_MODEL, state.deepSeekModel)
            assertEquals("synthetic-custom-persisted", prefs.connection("synthetic-legacy").apiKey)
            assertEquals(profile.endpoint, prefs.connection("synthetic-legacy").profile.endpoint)
            assertEquals("synthetic-ds", prefs.savedKey(AiProviderId.DEEPSEEK))
        }
    }

    @Test fun invalidProfilesAndHeaderKeysDoNotReplaceAnExistingValidConnection() = runBlocking {
        withPrefs { prefs, _ ->
            val good = custom()
            prefs.saveCustom(good, "synthetic-good-key")
            prefs.select(AiProviderId.CUSTOM)
            val bad = listOf(good.copy(model = ""), good.copy(address = "file:///invalid"),
                good.copy(address = "https://user:password@fixture.invalid/v1"), good.copy(name = ""))
            for (profile in bad) assertTrue(runCatching { prefs.saveCustom(profile, "synthetic-other") }.isFailure)
            assertTrue(runCatching { prefs.saveCustom(good, "synthetic\nheader") }.isFailure)
            assertTrue(runCatching { prefs.saveDeepSeekKey("synthetic\nheader") }.isFailure)
            assertEquals(good, prefs.state.first().custom)
            assertEquals("synthetic-good-key", prefs.connection("synthetic-legacy").apiKey)
            assertTrue(prefs.state.first().hasCustomKey)
        }
    }

    private fun custom() = AiProviderProfile.custom().copy(name = "Fixture", address = "https://fixture.invalid/v1", model = "fixture-model")
    private suspend fun withPrefs(file: File = File(temporary.root, "provider-${fileIndex++}.preferences_pb"),
        action: suspend (AiProviderPrefs, DataStore<Preferences>) -> Unit) = withTimeout(10_000) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        try { action(AiProviderPrefs(store), store) } finally { job.cancelAndJoin() }
    }
}
