package com.jiligulu.app.ui.littleworld

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/** Network rooms intentionally have no representable local slot. */
enum class LocalGameMode { CPU, HOTSEAT }
data class LocalGomokuSave(val mode: LocalGameMode, val game: GomokuState,
    val undoHistory: List<GomokuState> = emptyList(), val started: Boolean = true, val paused: Boolean = true)
data class LocalXiangqiSave(val mode: LocalGameMode, val game: XiangqiState,
    val undoHistory: List<XiangqiState> = emptyList(), val clock: XiangqiThinkingClock = XiangqiThinkingClock.reset(game),
    val thinkingSeconds: Int = (clock.durationMillis / 1_000).toInt(), val started: Boolean = true, val paused: Boolean = true)
data class LocalSnakeSave(val game: SnakeState, val started: Boolean = false, val paused: Boolean = true)

/**
 * Claims a bounded immutable snapshot synchronously, then validates/serializes/commits on one worker.
 * Reads queue behind prior writes. Call flushBlocking only at ON_STOP, never per frame or move.
 */
class LocalGameArchive internal constructor(private val preferences: SharedPreferences,
    private val worker: ExecutorService = LocalArchiveWorker.executor) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE))

    private val lock = Any()
    private var sequence = 0L
    private val pending = linkedMapOf<String, Pending>()
    private var scheduled = false
    @Volatile private var lastCommitSucceeded = true
    private data class Pending(val sequence: Long, val kind: String, val mode: LocalGameMode?, val encode: () -> String)

    fun saveGomoku(save: LocalGomokuSave) {
        val snapshot = save.copy(game = save.game.copy(board = save.game.board.toList()),
            undoHistory = save.undoHistory.takeLast(GOMOKU_HISTORY_LIMIT).map { it.copy(board = it.board.toList()) })
        enqueue(gomokuKey(snapshot.mode), "gomoku", snapshot.mode) { LocalGameCodec.encode(snapshot) }
    }
    fun saveXiangqi(save: LocalXiangqiSave) {
        val snapshot = save.copy(game = save.game.copy(board = save.game.board.toList()),
            undoHistory = save.undoHistory.takeLast(XIANGQI_HISTORY_LIMIT).map { it.copy(board = it.board.toList()) },
            clock = save.clock.forPosition(save.game))
        enqueue(xiangqiKey(snapshot.mode), "xiangqi", snapshot.mode) { LocalGameCodec.encode(snapshot) }
    }
    fun saveSnake(save: LocalSnakeSave) {
        val snapshot = save.copy(game = save.game.copy(body = save.game.body.toList()))
        enqueue(SNAKE_KEY, "snake", null) { LocalGameCodec.encode(snapshot) }
    }

    suspend fun loadGomoku(mode: LocalGameMode): LocalGomokuSave? = readSlot(gomokuKey(mode)) { LocalGameCodec.gomoku(it, mode) }
    suspend fun loadXiangqi(mode: LocalGameMode): LocalXiangqiSave? = readSlot(xiangqiKey(mode)) { LocalGameCodec.xiangqi(it, mode) }
    suspend fun loadSnake(): LocalSnakeSave? = readSlot(SNAKE_KEY, LocalGameCodec::snake)
    suspend fun preferredGomokuMode(): LocalGameMode = preferred("gomoku")
    suspend fun preferredXiangqiMode(): LocalGameMode = preferred("xiangqi")

    suspend fun flush(): Boolean = onWorker { lastCommitSucceeded }
    fun flushBlocking(timeoutMillis: Long = 350): Boolean {
        require(timeoutMillis in 1..2_000)
        val done = CompletableFuture<Boolean>()
        worker.execute { done.complete(lastCommitSucceeded) }
        return runCatching { done.get(timeoutMillis, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    private fun enqueue(key: String, kind: String, mode: LocalGameMode?, encode: () -> String) {
        synchronized(lock) {
            pending[key] = Pending(++sequence, kind, mode, encode)
            if (!scheduled) { scheduled = true; worker.execute(::drain) }
        }
    }
    private fun drain() {
        while (true) {
            val batch = synchronized(lock) {
                if (pending.isEmpty()) { scheduled = false; return }
                pending.toMap().also { pending.clear() }
            }
            lastCommitSucceeded = runCatching {
                val editor = preferences.edit()
                var valid = true
                val accepted = mutableListOf<Pending>()
                for ((key, entry) in batch) {
                    val raw = runCatching(entry.encode).getOrNull()
                    if (raw == null) valid = false else { editor.putString(key, raw); accepted += entry }
                }
                for (kind in listOf("gomoku", "xiangqi")) {
                    accepted.filter { it.kind == kind }.maxByOrNull { it.sequence }?.mode?.let {
                        editor.putString(preferredKey(kind), it.name)
                    }
                }
                if (accepted.isEmpty()) false else editor.commit() && valid
            }.getOrDefault(false)
        }
    }
    private suspend fun <T> readSlot(key: String, decode: (String) -> T): T? = onWorker {
        try { preferences.getString(key, null)?.let(decode) }
        catch (_: Exception) { runCatching { preferences.edit().remove(key).commit() }; null }
    }
    private suspend fun preferred(kind: String): LocalGameMode = onWorker {
        try { preferences.getString(preferredKey(kind), null)?.let(LocalGameMode::valueOf) ?: LocalGameMode.CPU }
        catch (_: Exception) { runCatching { preferences.edit().remove(preferredKey(kind)).commit() }; LocalGameMode.CPU }
    }
    private suspend fun <T> onWorker(action: () -> T): T = suspendCancellableCoroutine { continuation ->
        worker.execute {
            try { continuation.resume(action()) }
            catch (failure: Throwable) { continuation.resumeWithException(failure) }
        }
    }
    companion object {
        const val PREFERENCES = "local_secret_games_v1"
        const val GOMOKU_HISTORY_LIMIT = 64
        const val XIANGQI_HISTORY_LIMIT = 128
        internal const val SNAKE_KEY = "snake"
        internal fun gomokuKey(mode: LocalGameMode) = "gomoku_${mode.name}"
        internal fun xiangqiKey(mode: LocalGameMode) = "xiangqi_${mode.name}"
        private fun preferredKey(kind: String) = "${kind}_preferred"
    }
}

private object LocalArchiveWorker {
    val executor: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "GuluLocalGameArchive").apply { isDaemon = true } }
}

/** Small JSON envelope with bounded nesting/size. Each slot is independently versioned and checked. */
internal object LocalGameCodec {
    private const val VERSION = 1
    private const val MAX_BYTES = 100_000
    private val numbers = Regex("-?\\d{1,10}")
    private val boardNumbers = Regex("-?\\d{1,4}")
    private val goAxes = arrayOf(GridCell(1, 0), GridCell(0, 1), GridCell(1, 1), GridCell(1, -1))
    fun encode(save: LocalGomokuSave): String {
        validateGomoku(save.game)
        validateHistory(save.undoHistory, save.game, LocalGameArchive.GOMOKU_HISTORY_LIMIT, ::validateGomoku) { before, next ->
            next.lastMove?.let { GomokuEngine.play(before, it.x, it.y) == next } == true
        }
        require(save.started || save.game.board.all { it == 0 })
        return envelope("gomoku", save.started, save.paused).put("mode", save.mode.name)
            .put("game", gomokuJson(save.game)).put("history", JSONArray(save.undoHistory.map(::gomokuJson))).toString()
    }
    fun encode(save: LocalXiangqiSave): String {
        validateXiangqi(save.game)
        validateHistory(save.undoHistory, save.game, LocalGameArchive.XIANGQI_HISTORY_LIMIT, ::validateXiangqi) { before, next ->
            next.lastMove?.let { XiangqiEngine.play(before, it) == next } == true
        }
        require(save.started || save.game.ply == 0)
        require(save.thinkingSeconds in XiangqiThinkingClock.MIN_SECONDS..XiangqiThinkingClock.MAX_SECONDS)
        require(save.clock.ply == save.game.ply && save.clock.side == save.game.turnSide && save.clock.durationMillis == save.thinkingSeconds * 1_000L)
        return envelope("xiangqi", save.started, save.paused).put("mode", save.mode.name).put("game", xiangqiJson(save.game))
            .put("history", JSONArray(save.undoHistory.map(::xiangqiJson))).put("thinking", save.thinkingSeconds)
            .put("clock", JSONObject().put("ply", save.clock.ply).put("side", save.clock.side.name)
                .put("remaining", save.clock.remainingMillis).put("duration", save.clock.durationMillis)).toString()
    }
    fun encode(save: LocalSnakeSave): String {
        validateSnake(save.game)
        require(save.started || save.game.score == 0 && !save.game.gameOver)
        return envelope("snake", save.started, save.paused).put("game", with(save.game) {
            JSONObject().put("width", width).put("height", height).put("body", body.joinToString(",") { (it.y * width + it.x).toString() })
                .put("direction", direction.name).put("pending", pendingDirection.name)
                .put("food", food?.let { it.y * width + it.x } ?: -1).put("score", score).put("over", gameOver).put("won", won)
        }).toString()
    }
    fun gomoku(raw: String, expected: LocalGameMode): LocalGomokuSave {
        val json = parse(raw, "gomoku")
        val mode = LocalGameMode.valueOf(json.getString("mode")); require(mode == expected)
        val game = readGomoku(json.getJSONObject("game"))
        val history = history(json, LocalGameArchive.GOMOKU_HISTORY_LIMIT, ::readGomoku)
        val save = LocalGomokuSave(mode, game, history, bool(json, "started"), true)
        encode(save) // The same engine/chain validation applies to incoming private files.
        return save
    }
    fun xiangqi(raw: String, expected: LocalGameMode): LocalXiangqiSave {
        val json = parse(raw, "xiangqi")
        val mode = LocalGameMode.valueOf(json.getString("mode")); require(mode == expected)
        val game = readXiangqi(json.getJSONObject("game"))
        val history = history(json, LocalGameArchive.XIANGQI_HISTORY_LIMIT, ::readXiangqi)
        val clockJson = json.getJSONObject("clock")
        val clock = XiangqiThinkingClock(int(clockJson, "ply", 0..1_000_000), XiangqiSide.valueOf(clockJson.getString("side")),
            number(clockJson, "remaining", 0L..600_000L), number(clockJson, "duration", 15_000L..600_000L))
        val save = LocalXiangqiSave(mode, game, history, clock, int(json, "thinking", 15..600), bool(json, "started"), true)
        encode(save)
        return save
    }
    fun snake(raw: String): LocalSnakeSave {
        val json = parse(raw, "snake"); val game = json.getJSONObject("game")
        val width = int(game, "width", 4..32); val height = int(game, "height", 4..32)
        fun cell(index: Int) = GridCell(index % width, index / width)
        val body = csv(game.getString("body"), 0..width * height - 1, width * height).map(::cell)
        val food = int(game, "food", -1..width * height - 1)
        val save = LocalSnakeSave(SnakeState(width, height, body, SnakeDirection.valueOf(game.getString("direction")),
            SnakeDirection.valueOf(game.getString("pending")), if (food < 0) null else cell(food),
            int(game, "score", 0..width * height - 3), bool(game, "over"), bool(game, "won")), bool(json, "started"), true)
        encode(save)
        return save
    }

    private fun envelope(kind: String, started: Boolean, paused: Boolean) = JSONObject()
        .put("v", VERSION).put("kind", kind).put("started", started).put("paused", paused)
    private fun parse(raw: String, kind: String): JSONObject {
        require(raw.length in 1..MAX_BYTES)
        var depth = 0; var quoted = false; var escaped = false
        for (char in raw) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 8) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(depth == 0 && !quoted)
        val json = JSONObject(raw)
        require(int(json, "v", VERSION..VERSION) == VERSION && json.getString("kind") == kind)
        bool(json, "paused")
        return json
    }
    private fun gomokuJson(game: GomokuState) = JSONObject().put("size", game.size).put("board", game.board.joinToString(","))
        .put("player", game.currentPlayer).put("outcome", game.outcome.name).put("last", game.lastMove?.let { it.y * game.size + it.x } ?: -1)
    private fun readGomoku(json: JSONObject): GomokuState {
        val size = int(json, "size", 15..15)
        val board = csv(json.getString("board"), 0..2, 225); require(board.size == 225)
        val last = int(json, "last", -1..224)
        return GomokuState(size, board, int(json, "player", 1..2), GomokuOutcome.valueOf(json.getString("outcome")),
            if (last < 0) null else GridCell(last % 15, last / 15))
    }
    private fun xiangqiJson(game: XiangqiState) = JSONObject().put("board", game.board.joinToString(","))
        .put("side", game.turnSide.name).put("outcome", game.outcome.name).put("ply", game.ply)
        .put("from", game.lastMove?.from?.let { it.y * 9 + it.x } ?: -1).put("to", game.lastMove?.to?.let { it.y * 9 + it.x } ?: -1)
    private fun readXiangqi(json: JSONObject): XiangqiState {
        val board = csv(json.getString("board"), -7..7, 90); require(board.size == 90)
        val from = int(json, "from", -1..89); val to = int(json, "to", -1..89); require((from == -1) == (to == -1))
        return XiangqiState(board, XiangqiSide.valueOf(json.getString("side")), XiangqiOutcome.valueOf(json.getString("outcome")),
            if (from < 0) null else XiangqiMove(GridCell(from % 9, from / 9), GridCell(to % 9, to / 9)), int(json, "ply", 0..1_000_000))
    }
    private fun <T> history(json: JSONObject, limit: Int, read: (JSONObject) -> T): List<T> {
        val array = json.getJSONArray("history"); require(array.length() <= limit)
        return (0 until array.length()).map { read(array.getJSONObject(it)) }
    }
    private fun <T> validateHistory(history: List<T>, game: T, limit: Int, validate: (T) -> Unit, advances: (T, T) -> Boolean) {
        require(history.size <= limit)
        history.firstOrNull()?.let(validate)
        (history + game).zipWithNext().forEach { (before, next) -> require(advances(before, next)) }
    }
    private fun validateGomoku(game: GomokuState) {
        require(game.size == 15 && game.board.size == 225 && game.board.all { it in 0..2 })
        val black = game.board.count { it == 1 }; val white = game.board.count { it == 2 }
        require(black == white || black == white + 1)
        if (black + white == 0) { require(game == GomokuEngine.newGame()); return }
        val last = requireNotNull(game.lastMove); require(last.x in 0..14 && last.y in 0..14)
        val player = if (black == white) 2 else 1
        require(game.cellAt(last.x, last.y) == player)
        val before = game.copy(board = game.board.toMutableList().apply { this[last.y * 15 + last.x] = 0 },
            currentPlayer = player, outcome = GomokuOutcome.PLAYING, lastMove = null)
        require(winners(before).isEmpty() && GomokuEngine.play(before, last.x, last.y) == game)
        require(if (game.outcome == GomokuOutcome.PLAYING || game.outcome == GomokuOutcome.DRAW) winners(game).isEmpty()
            else winners(game) == setOf(player))
    }
    private fun winners(game: GomokuState): Set<Int> = buildSet {
        for (y in 0..14) for (x in 0..14) {
            val player = game.cellAt(x, y)
            if (player == 0) continue
            for ((dx, dy) in goAxes) {
                val endX = x + dx * 4; val endY = y + dy * 4
                if (endX in 0..14 && endY in 0..14 && (1..4).all { game.cellAt(x + dx * it, y + dy * it) == player }) add(player)
            }
        }
    }
    private fun validateXiangqi(game: XiangqiState) {
        require(game.board.size == 90 && game.board.all { it in -7..7 } && game.ply in 0..1_000_000)
        require(game.turnSide == if (game.ply % 2 == 0) XiangqiSide.RED else XiangqiSide.BLACK)
        val limits = intArrayOf(0, 1, 2, 2, 2, 2, 2, 5)
        for (side in XiangqiSide.entries) {
            for (piece in 1..7) require(game.board.count { it == piece * side.sign } <= limits[piece])
            val general = game.board.indexOf(side.sign * XiangqiEngine.GENERAL)
            if (general >= 0) require(general % 9 in 3..5 && general / 9 in if (side == XiangqiSide.RED) 7..9 else 0..2)
        }
        if (game.ply == 0) require(game.lastMove == null) else {
            val last = requireNotNull(game.lastMove)
            require(last.from.x in 0..8 && last.to.x in 0..8 && last.from.y in 0..9 && last.to.y in 0..9 && last.from != last.to)
            require(game.pieceAt(last.from.x, last.from.y) == 0 && game.pieceAt(last.to.x, last.to.y) * game.turnSide.sign < 0)
        }
        require(!XiangqiEngine.isInCheck(game, game.turnSide.opponent))
        if (game.outcome == XiangqiOutcome.PLAYING) {
            require(game.board.count { it == 1 } == 1 && game.board.count { it == -1 } == 1 && XiangqiEngine.legalMoves(game).isNotEmpty())
        } else {
            require(game.ply > 0)
            val winner = if (game.outcome == XiangqiOutcome.RED_WON) XiangqiSide.RED else XiangqiSide.BLACK
            require(winner == game.turnSide.opponent && game.board.count { it == winner.sign } == 1)
            require(XiangqiEngine.legalMoves(game.copy(outcome = XiangqiOutcome.PLAYING)).isEmpty())
        }
    }
    private fun validateSnake(game: SnakeState) {
        require(game.width in 4..32 && game.height in 4..32 && game.body.size in 3..game.width * game.height)
        require(game.score == game.body.size - 3 && game.body.distinct().size == game.body.size)
        require(game.body.all { it.x in 0 until game.width && it.y in 0 until game.height })
        require(!game.pendingDirection.isOpposite(game.direction))
        game.body.zipWithNext().forEach { (a, b) ->
            val dx = abs(a.x - b.x); val dy = abs(a.y - b.y)
            require(minOf(dx, game.width - dx) + minOf(dy, game.height - dy) == 1)
        }
        if (game.won) require(game.gameOver && game.body.size == game.width * game.height && game.food == null)
        else {
            val food = requireNotNull(game.food)
            require(food.x in 0 until game.width && food.y in 0 until game.height && food !in game.body)
            val head = game.body.first()
            if (game.gameOver) require(GridCell((head.x + game.direction.dx + game.width) % game.width,
                (head.y + game.direction.dy + game.height) % game.height) in game.body.dropLast(1))
            else require(GridCell((head.x - game.direction.dx + game.width) % game.width,
                (head.y - game.direction.dy + game.height) % game.height) == game.body[1])
        }
    }
    private fun csv(value: String, range: IntRange, maximum: Int): List<Int> {
        require(value.length <= maximum * 5)
        val parts = value.split(','); require(parts.size <= maximum)
        return parts.map { token -> require(boardNumbers.matches(token)); token.toInt().also { require(it in range) } }
    }
    private fun bool(json: JSONObject, key: String): Boolean = (json.get(key) as? Boolean) ?: error("Bad boolean")
    private fun int(json: JSONObject, key: String, range: IntRange): Int = number(json, key, range.first.toLong()..range.last.toLong()).toInt()
    private fun number(json: JSONObject, key: String, range: LongRange): Long {
        val text = json.get(key).toString(); require(numbers.matches(text))
        return text.toLong().also { require(it in range) }
    }
}
