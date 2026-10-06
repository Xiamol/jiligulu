package com.jiligulu.app.ui.littleworld

import android.content.SharedPreferences
import android.app.Application
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LocalGameArchiveTest {
    @Test fun fiveIndependentSlotsRestorePausedWithExactClockAndPositions() = runBlocking {
        val prefs = ArchivePreferences()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val archive = LocalGameArchive(prefs, worker)
            val goCpu = go(2, LocalGameMode.CPU)
            val goHuman = go(3, LocalGameMode.HOTSEAT)
            val xqCpu = chess(2, LocalGameMode.CPU)
            val xqHuman = chess(3, LocalGameMode.HOTSEAT)
            val snake = LocalSnakeSave(SnakeEngine.newGame(kotlin.random.Random(4)), started = true, paused = false)
            archive.saveGomoku(goCpu); archive.saveGomoku(goHuman)
            archive.saveXiangqi(xqCpu); archive.saveXiangqi(xqHuman); archive.saveSnake(snake)
            assertTrue(archive.flush())
            val reopened = LocalGameArchive(prefs, worker)
            assertEquals(goCpu.copy(paused = true), reopened.loadGomoku(LocalGameMode.CPU))
            assertEquals(goHuman.copy(paused = true), reopened.loadGomoku(LocalGameMode.HOTSEAT))
            assertEquals(xqCpu.copy(paused = true), reopened.loadXiangqi(LocalGameMode.CPU))
            assertEquals(xqHuman.copy(paused = true), reopened.loadXiangqi(LocalGameMode.HOTSEAT))
            assertEquals(snake.copy(paused = true), reopened.loadSnake())
            assertEquals(37_123L, reopened.loadXiangqi(LocalGameMode.CPU)!!.clock.remainingMillis)
            assertEquals(LocalGameMode.HOTSEAT, reopened.preferredGomokuMode())
            assertEquals(LocalGameMode.HOTSEAT, reopened.preferredXiangqiMode())
        } finally { worker.shutdownNow() }
    }

    @Test fun coalescingKeepsTheNewestSnapshotAndNewestSelectedModeDespiteMapOrder() = runBlocking {
        val prefs = ArchivePreferences(); val worker = Executors.newSingleThreadExecutor()
        val blocked = CountDownLatch(1)
        worker.execute { blocked.await() }
        try {
            val archive = LocalGameArchive(prefs, worker)
            archive.saveGomoku(go(1, LocalGameMode.CPU))
            archive.saveGomoku(go(2, LocalGameMode.HOTSEAT))
            archive.saveGomoku(go(3, LocalGameMode.CPU))
            blocked.countDown()
            assertTrue(archive.flush())
            assertEquals(go(3, LocalGameMode.CPU).copy(paused = true), archive.loadGomoku(LocalGameMode.CPU))
            assertEquals(LocalGameMode.CPU, archive.preferredGomokuMode())
            assertEquals(1, prefs.commits)
            assertFalse("Encoding/commit must never execute in the caller thread", prefs.commitThread == Thread.currentThread().name)
        } finally { blocked.countDown(); worker.shutdownNow() }
    }

    @Test fun synchronouslyClaimedSnapshotsCannotBeChangedByLaterCallerMutation() = runBlocking {
        val prefs = ArchivePreferences(); val worker = Executors.newSingleThreadExecutor(); val blocked = CountDownLatch(1)
        worker.execute { blocked.await() }
        try {
            val archive = LocalGameArchive(prefs, worker)
            val original = go(1, LocalGameMode.CPU)
            val callerBoard = original.game.board.toMutableList()
            archive.saveGomoku(original.copy(game = original.game.copy(board = callerBoard)))
            callerBoard.fill(0)
            blocked.countDown()
            assertTrue(archive.flush())
            assertEquals(original.copy(paused = true), archive.loadGomoku(LocalGameMode.CPU))
        } finally { blocked.countDown(); worker.shutdownNow() }
    }

    @Test fun corruptedClockAndBoardResetOnlyTheirOwnSlots() = runBlocking {
        val prefs = ArchivePreferences(); val worker = Executors.newSingleThreadExecutor()
        try {
            val archive = LocalGameArchive(prefs, worker)
            archive.saveXiangqi(chess(2, LocalGameMode.CPU)); archive.saveXiangqi(chess(3, LocalGameMode.HOTSEAT))
            archive.saveGomoku(go(2, LocalGameMode.CPU)); archive.saveSnake(LocalSnakeSave(SnakeEngine.newGame()))
            assertTrue(archive.flush())
            val badClock = JSONObject(prefs.getString(LocalGameArchive.xiangqiKey(LocalGameMode.CPU), null)!!)
            badClock.getJSONObject("clock").put("remaining", 999_999)
            prefs.edit().putString(LocalGameArchive.xiangqiKey(LocalGameMode.CPU), badClock.toString()).commit()
            assertNull(archive.loadXiangqi(LocalGameMode.CPU))
            assertFalse(prefs.contains(LocalGameArchive.xiangqiKey(LocalGameMode.CPU)))
            assertEquals(chess(3, LocalGameMode.HOTSEAT).copy(paused = true), archive.loadXiangqi(LocalGameMode.HOTSEAT))
            prefs.edit().putString(LocalGameArchive.gomokuKey(LocalGameMode.CPU), "{".repeat(200)).commit()
            assertNull(archive.loadGomoku(LocalGameMode.CPU))
            assertNotNull(archive.loadSnake())
        } finally { worker.shutdownNow() }
    }

    @Test fun anUndoHistoryWithAGapOrAReorderedBoardCannotBeRestored() {
        val snapshot = go(3, LocalGameMode.CPU)
        val bad = JSONObject(LocalGameCodec.encode(snapshot))
        bad.put("history", JSONArray().put(bad.getJSONArray("history").getJSONObject(0)))
        rejected { LocalGameCodec.gomoku(bad.toString(), LocalGameMode.CPU) }
        val badXq = JSONObject(LocalGameCodec.encode(chess(3, LocalGameMode.CPU)))
        val history = badXq.getJSONArray("history")
        badXq.put("history", JSONArray().put(history.getJSONObject(1)).put(history.getJSONObject(0)).put(history.getJSONObject(2)))
        rejected { LocalGameCodec.xiangqi(badXq.toString(), LocalGameMode.CPU) }
    }

    @Test fun terminalGamesPreserveResultAndLegalUndoHistory() {
        var game = GomokuEngine.newGame(); val history = mutableListOf<GomokuState>()
        for (cell in listOf(GridCell(0, 7), GridCell(0, 0), GridCell(1, 7), GridCell(2, 0), GridCell(2, 7),
            GridCell(4, 0), GridCell(3, 7), GridCell(6, 0), GridCell(4, 7))) {
            history += game; game = GomokuEngine.play(game, cell.x, cell.y)
        }
        val goSave = LocalGomokuSave(LocalGameMode.HOTSEAT, game, history, paused = false)
        val restored = LocalGameCodec.gomoku(LocalGameCodec.encode(goSave), LocalGameMode.HOTSEAT)
        assertEquals(GomokuOutcome.HUMAN_WON, restored.game.outcome)
        assertEquals(goSave.copy(paused = true), restored)
        val puzzle = XiangqiPuzzles.all.first()
        val won = XiangqiEngine.play(puzzle.position, puzzle.solution)
        val xqSave = LocalXiangqiSave(LocalGameMode.CPU, won, listOf(puzzle.position), paused = false)
        assertEquals(xqSave.copy(paused = true), LocalGameCodec.xiangqi(LocalGameCodec.encode(xqSave), LocalGameMode.CPU))
    }

    @Test fun snakeWraparoundAndGrowthRestoreExactBodyFoodAndPendingDirection() {
        val before = SnakeState(body = listOf(GridCell(15, 8), GridCell(14, 8), GridCell(13, 8)), food = GridCell(0, 8))
        val wrapped = SnakeEngine.tick(before, kotlin.random.Random(9))
        assertEquals(GridCell(0, 8), wrapped.body.first())
        val save = LocalSnakeSave(SnakeEngine.turn(wrapped, SnakeDirection.UP), started = true, paused = false)
        assertEquals(save.copy(paused = true), LocalGameCodec.snake(LocalGameCodec.encode(save)))
        rejected { LocalGameCodec.encode(save.copy(game = save.game.copy(body = listOf(GridCell(0, 8), GridCell(0, 8), GridCell(13, 8))))) }
    }

    @Test fun longHistoryIsBoundedWithoutBreakingTheRemainingLegalChain() = runBlocking {
        val prefs = ArchivePreferences(); val worker = Executors.newSingleThreadExecutor()
        try {
            var game = XiangqiEngine.newGame(); val history = mutableListOf<XiangqiState>()
            val cycle = listOf(XiangqiMove(GridCell(0, 9), GridCell(0, 8)), XiangqiMove(GridCell(0, 0), GridCell(0, 1)),
                XiangqiMove(GridCell(0, 8), GridCell(0, 9)), XiangqiMove(GridCell(0, 1), GridCell(0, 0)))
            repeat(140) { index -> history += game; game = XiangqiEngine.play(game, cycle[index % 4]); assertEquals(index + 1, game.ply) }
            val archive = LocalGameArchive(prefs, worker)
            archive.saveXiangqi(LocalXiangqiSave(LocalGameMode.CPU, game, history))
            assertTrue(archive.flush())
            val restored = requireNotNull(archive.loadXiangqi(LocalGameMode.CPU))
            assertEquals(128, restored.undoHistory.size)
            assertEquals(12, restored.undoHistory.first().ply)
            assertEquals(game, restored.game)
        } finally { worker.shutdownNow() }
    }

    @Test fun failedDiskCommitReportsFailureAndDoesNotDiscardAQueuedRetry() = runBlocking {
        val prefs = ArchivePreferences(); val worker = Executors.newSingleThreadExecutor()
        try {
            val archive = LocalGameArchive(prefs, worker)
            prefs.failCommits = true
            archive.saveGomoku(go(1, LocalGameMode.CPU)); assertFalse(archive.flush())
            prefs.failCommits = false
            archive.saveGomoku(go(2, LocalGameMode.CPU)); assertTrue(archive.flush())
            assertEquals(go(2, LocalGameMode.CPU).copy(paused = true), archive.loadGomoku(LocalGameMode.CPU))
        } finally { worker.shutdownNow() }
    }

    @Test fun boundedExitWaitNeverDropsTheSaveEvenWhenTheWorkerWasTemporarilyBusy() = runBlocking {
        val prefs = ArchivePreferences(); val worker = Executors.newSingleThreadExecutor(); val blocked = CountDownLatch(1)
        worker.execute { blocked.await() }
        try {
            val archive = LocalGameArchive(prefs, worker)
            archive.saveGomoku(go(2, LocalGameMode.CPU))
            assertFalse(archive.flushBlocking(10))
            blocked.countDown()
            assertTrue(archive.flush())
            assertNotNull(archive.loadGomoku(LocalGameMode.CPU))
        } finally { blocked.countDown(); worker.shutdownNow() }
    }

    @Test fun networkModesUnknownVersionsAndMalformedNumericFieldsAreRejected() {
        assertEquals(listOf("CPU", "HOTSEAT"), LocalGameMode.entries.map { it.name })
        val raw = LocalGameCodec.encode(go(1, LocalGameMode.CPU))
        rejected { LocalGameCodec.gomoku(JSONObject(raw).put("mode", "ONLINE").toString(), LocalGameMode.CPU) }
        rejected { LocalGameCodec.gomoku(JSONObject(raw).put("v", 2).toString(), LocalGameMode.CPU) }
        rejected { LocalGameCodec.gomoku(JSONObject(raw).put("paused", "true").toString(), LocalGameMode.CPU) }
        rejected { LocalGameCodec.gomoku(raw.replace("\"size\":15", "\"size\":15.5"), LocalGameMode.CPU) }
        rejected { LocalGameCodec.gomoku("[".repeat(50) + "]".repeat(50), LocalGameMode.CPU) }
        rejected { LocalGameCodec.gomoku(" ".repeat(100_001), LocalGameMode.CPU) }
    }

    private fun go(moves: Int, mode: LocalGameMode): LocalGomokuSave {
        var game = GomokuEngine.newGame(); val history = mutableListOf<GomokuState>()
        for (cell in listOf(GridCell(7, 7), GridCell(8, 8), GridCell(7, 8)).take(moves)) {
            history += game; game = GomokuEngine.play(game, cell.x, cell.y)
        }
        return LocalGomokuSave(mode, game, history, paused = false)
    }
    private fun chess(moves: Int, mode: LocalGameMode): LocalXiangqiSave {
        var game = XiangqiEngine.newGame(); val history = mutableListOf<XiangqiState>()
        for (move in listOf(XiangqiMove(GridCell(0, 6), GridCell(0, 5)), XiangqiMove(GridCell(0, 3), GridCell(0, 4)),
            XiangqiMove(GridCell(2, 6), GridCell(2, 5))).take(moves)) { history += game; game = XiangqiEngine.play(game, move) }
        return LocalXiangqiSave(mode, game, history, XiangqiThinkingClock.reset(game, 90).copy(remainingMillis = 37_123), 90, paused = false)
    }
    private fun rejected(action: () -> Unit) { try { action(); fail("Corrupted slot was accepted") } catch (_: Exception) { } }
}

private class ArchivePreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any>()
    @Volatile var failCommits = false
    @Volatile var commits = 0
    @Volatile var commitThread = ""
    @Synchronized override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    @Synchronized override fun getString(key: String, default: String?): String? = values[key]?.let { it as String } ?: default
    @Synchronized override fun getStringSet(key: String, default: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") ((values[key] as? Set<String>)?.toMutableSet() ?: default)
    @Synchronized override fun getInt(key: String, default: Int) = values[key]?.let { it as Int } ?: default
    @Synchronized override fun getLong(key: String, default: Long) = values[key]?.let { it as Long } ?: default
    @Synchronized override fun getFloat(key: String, default: Float) = values[key]?.let { it as Float } ?: default
    @Synchronized override fun getBoolean(key: String, default: Boolean) = values[key]?.let { it as Boolean } ?: default
    @Synchronized override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>(); private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, value: MutableSet<String>?) = apply { changes[key] = value?.toSet() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clear = true }
        override fun apply() { commit() }
        override fun commit(): Boolean = synchronized(this@ArchivePreferences) {
            if (failCommits) false else {
                if (clear) values.clear()
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                commits++; commitThread = Thread.currentThread().name; true
            }
        }
    }
}
