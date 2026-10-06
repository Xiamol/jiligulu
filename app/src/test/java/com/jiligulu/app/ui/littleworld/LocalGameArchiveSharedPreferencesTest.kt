package com.jiligulu.app.ui.littleworld

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LocalGameArchiveSharedPreferencesTest {
    @Test fun frameworkPreferencesReopenAsPausedAndBadTypedSlotDoesNotAffectTheOtherMode(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences(LocalGameArchive.PREFERENCES, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val archive = LocalGameArchive(context)
        val initial = GomokuEngine.newGame()
        val moved = GomokuEngine.play(initial, 7, 7)
        archive.saveGomoku(LocalGomokuSave(LocalGameMode.CPU, moved, listOf(initial), paused = false))
        archive.saveGomoku(LocalGomokuSave(LocalGameMode.HOTSEAT, initial, paused = false))
        assertTrue(archive.flush())
        val reopened = LocalGameArchive(context)
        assertEquals(moved, reopened.loadGomoku(LocalGameMode.CPU)?.game)
        assertTrue(reopened.loadGomoku(LocalGameMode.CPU)!!.paused)
        preferences.edit().putInt(LocalGameArchive.gomokuKey(LocalGameMode.CPU), 7).commit()
        assertNull(reopened.loadGomoku(LocalGameMode.CPU))
        assertFalse(preferences.contains(LocalGameArchive.gomokuKey(LocalGameMode.CPU)))
        assertEquals(initial, reopened.loadGomoku(LocalGameMode.HOTSEAT)?.game)
        preferences.edit().clear().commit()
    }
}
