package com.jiligulu.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import com.jiligulu.app.R
import java.util.concurrent.ConcurrentHashMap

/** Sound follows the meaning and material of an action, never the label of a Button. */
enum class UiCue(val raw: Int, val volume: Float, val minimumGap: Long = 65) {
    TOUCH(R.raw.ui_tap, .18f),
    SELECT(R.raw.ui_select, .17f),
    NAVIGATE(R.raw.ui_navigate, .19f, 120),
    TOGGLE(R.raw.ui_toggle, .20f),
    PAPER(R.raw.ui_paper, .28f, 130),
    CONFIRM(R.raw.ui_confirm, .25f, 180),
    REMOVE(R.raw.ui_remove, .18f, 120),
    CALCULATOR(R.raw.ui_calculator, .20f, 28),
    PIECE_SELECT(R.raw.ui_piece_select, .28f),
    WOOD_MOVE(R.raw.ui_wood_move, .38f, 85),
    STONE_MOVE(R.raw.ui_stone_move, .32f, 85),
    CAPTURE(R.raw.ui_capture, .40f, 100),
    CHECK(R.raw.ui_check, .31f, 400),
    WIN(R.raw.ui_win, .29f, 800),
    LOSE(R.raw.ui_lose, .25f, 800),
    DRAW(R.raw.ui_draw, .25f, 800),
    WHEEL_TICK(R.raw.ui_wheel_tick, .23f, 75),
    LAMP(R.raw.ui_lamp, .25f, 150),
    PET(R.raw.ui_pet, .23f, 180)
}

/** Original short PCM feedback; one cached pool, no music loop or audio-focus stealing. */
object UiSound {
    private var engine: Engine? = null
    @Synchronized fun warmup(context: Context) { if (engine == null) engine = Engine(context.applicationContext) }
    fun enabled(context: Context) = context.applicationContext.getSharedPreferences("gulu_ui_feedback", Context.MODE_PRIVATE)
        .getBoolean("enabled", true)
    fun setEnabled(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences("gulu_ui_feedback", Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
        if (!value) engine?.silence()
    }
    fun tap(context: Context) = play(context, UiCue.TOUCH)
    fun select(context: Context) = play(context, UiCue.SELECT)
    fun navigate(context: Context) = play(context, UiCue.NAVIGATE)
    fun toggle(context: Context) = play(context, UiCue.TOGGLE)
    fun paper(context: Context) = play(context, UiCue.PAPER)
    fun confirm(context: Context) = play(context, UiCue.CONFIRM)
    fun calculator(context: Context) = play(context, UiCue.CALCULATOR)
    fun pieceSelect(context: Context) = play(context, UiCue.PIECE_SELECT)
    fun woodMove(context: Context) = play(context, UiCue.WOOD_MOVE)
    fun stoneMove(context: Context) = play(context, UiCue.STONE_MOVE)
    fun drop(context: Context) = stoneMove(context)
    fun capture(context: Context) = play(context, UiCue.CAPTURE)
    fun check(context: Context) = play(context, UiCue.CHECK)
    fun win(context: Context) = play(context, UiCue.WIN)
    fun lose(context: Context) = play(context, UiCue.LOSE)
    fun draw(context: Context) = play(context, UiCue.DRAW)
    fun wheelTick(context: Context) = play(context, UiCue.WHEEL_TICK)
    fun lamp(context: Context) = play(context, UiCue.LAMP)
    fun pet(context: Context) = play(context, UiCue.PET)
    fun play(context: Context, cue: UiCue) {
        if (!enabled(context)) return
        warmup(context)
        engine?.play(cue)
    }
    private class Engine(context: Context) {
        private val audio = context.getSystemService(AudioManager::class.java)
        private val ready = ConcurrentHashMap<Int, Boolean>()
        private val ids = UiCue.entries.associateWith { 0 }.toMutableMap()
        private val last = LongArray(UiCue.entries.size)
        private val streams = IntArray(3)
        private var nextStream = 0
        private var touchStream = 0
        private var touchAt = 0L
        private var semanticAt = 0L
        private val pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
            // Custom application feedback follows media volume, like the chess sounds.
            // ASSISTANCE_SONIFICATION would route to system volume while our guard checked music.
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        init {
            pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready[id] = true }
            UiCue.entries.forEach { ids[it] = pool.load(context, it.raw, 1) }
        }
        @Synchronized fun silence() { streams.forEach(pool::stop); touchStream=0 }
        @Synchronized fun play(cue: UiCue) {
            val manager = audio ?: return
            if (manager.ringerMode != AudioManager.RINGER_MODE_NORMAL || manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) return
            val id = ids[cue] ?: return
            if (ready[id] != true) return
            val now = SystemClock.elapsedRealtime()
            if (now - last[cue.ordinal] < cue.minimumGap) return
            // Defensive only: callers choose one semantic cue. An outer generic wrapper
            // must not add a knock to the paper/stone sound.
            if (cue == UiCue.TOUCH) {
                if (now - semanticAt < 80) return
                touchAt=now
            } else {
                semanticAt=now
                if (now - touchAt < 80) pool.stop(touchStream)
            }
            if (cue == UiCue.WIN || cue == UiCue.LOSE || cue == UiCue.DRAW) silence()
            last[cue.ordinal] = now
            val stream = pool.play(id, cue.volume, cue.volume, 1, 0, 1f)
            streams[nextStream] = stream; nextStream = (nextStream+1)%streams.size
            if (cue == UiCue.TOUCH) touchStream=stream
        }
    }
}
