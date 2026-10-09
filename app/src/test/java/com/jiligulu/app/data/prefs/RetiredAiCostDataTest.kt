package com.jiligulu.app.data.prefs

import android.app.Application
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RetiredAiCostDataTest {
    @Test fun cleanupOnlyRemovesMeterFilesAndKeepsLedgerCredentialsAndMemories() {
        val app = RuntimeEnvironment.getApplication()
        val directory = File(app.filesDir, "datastore").apply { mkdirs() }
        val retired = listOf("ai_usage.preferences_pb", "ai_usage.preferences_pb.tmp", "ai_usage.preferences_pb.bak")
            .map { File(directory, it).apply { writeText("retired statistics") } }
        val database = app.getDatabasePath("ai_usage_v2.db").apply { parentFile!!.mkdirs(); writeText("retired database") }
        val preserved = listOf(File(directory, "ai_providers.preferences_pb"), File(directory, "user_settings.preferences_pb"),
            File(directory, "little_world.preferences_pb"), app.getDatabasePath("jiligulu.db"))
        preserved.forEach { it.parentFile!!.mkdirs(); it.writeText("keep:${it.name}") }
        RetiredAiCostData.remove(app)
        assertFalse(database.exists())
        assertTrue(retired.none { it.exists() })
        preserved.forEach { assertEquals("keep:${it.name}", it.readText()) }
        RetiredAiCostData.remove(app)
        preserved.forEach { assertEquals("keep:${it.name}", it.readText()) }
    }
}
