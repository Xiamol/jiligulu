package com.jiligulu.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import com.jiligulu.app.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Sound follows the meaning and material of an action, never the label of a Button.
 *
 * [raw] 是这一类的主样本；[extraRaw] 是同一类的额外样本——需求⑤要求「常用类别准备 3–4 个样本，
 * 避免连续重复同一个」，播放时在可用样本间轮换（见 [UiSound.Engine.play]）。
 */
enum class UiCue(val raw: Int, val volume: Float, val minimumGap: Long = 65, val extraRaw: IntArray = intArrayOf()) {
    TOUCH(R.raw.ui_tap, .18f),
    SELECT(R.raw.ui_select, .17f),
    NAVIGATE(R.raw.ui_navigate, .19f, 120),
    TOGGLE(R.raw.ui_toggle, .20f),
    PAPER(R.raw.ui_paper, .28f, 130, intArrayOf(R.raw.ui_paper_2, R.raw.ui_paper_3)),
    CONFIRM(R.raw.ui_confirm, .25f, 180),
    REMOVE(R.raw.ui_remove, .18f, 120),
    CALCULATOR(R.raw.ui_calculator, .20f, 28, intArrayOf(R.raw.ui_calculator_2, R.raw.ui_calculator_3)),
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
    PET(R.raw.ui_pet, .23f, 180),

    // ---------- 2026-10-06 细分：把笼统的「纸张 / 导航」拆成能听出材质的几类 ----------
    /** 拆信封：长而闷的一张厚纸，和翻书页的脆响明确区分。 */
    ENVELOPE(R.raw.ui_envelope, .26f, 150),
    /** 翻页：短促的一扫 + 极轻的落页（日历翻月、相册翻页、翻周）。 */
    PAGE_TURN(R.raw.ui_page_turn, .22f, 110),
    /** 信笺：最轻最短的一张纸（便签铺开 / 收起、明信片抽出）。 */
    LETTER(R.raw.ui_letter, .21f, 90),
    /** 悔棋 / 撤销：「收回来」的两下低沉短音，不与落子混。 */
    UNDO(R.raw.ui_undo, .26f, 140),
    /** 提示 / 求助：上行两音，比 CONFIRM 轻，像「提醒你一下」而不是「完成了」。 */
    HINT(R.raw.ui_hint, .22f, 200),
    /** 匹配成功 / 对手来了：上行三音，比 CONFIRM 更亮更完整。 */
    MATCH(R.raw.ui_match, .27f, 300)
}

/**
 * 音高微扰总幅度（±3%，即 1f ± .03 的播放速率）。
 *
 * 需求⑤要求「音高微调约 ±3%」：范围刻意做得很小，小到听不出跑调，
 * 但足以让连按、连响的同一个声音不像同一份采样在机械重播。
 */
private const val PITCH_VARIATION = .06f

/** 音量微扰总幅度（±4%），同样是"刚好听得出不呆板"的量级。 */
private const val VOLUME_VARIATION = .08f

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
    fun remove(context: Context) = play(context, UiCue.REMOVE)

    // ---------- 2026-10-06 细分出来的六类 ----------
    /** 拆信封（长而闷的厚纸）。 */
    fun envelope(context: Context) = play(context, UiCue.ENVELOPE)

    /** 翻页（日历翻月、相册翻页、翻周）。 */
    fun pageTurn(context: Context) = play(context, UiCue.PAGE_TURN)

    /** 信笺铺开 / 收起（最轻的一张纸）。 */
    fun letter(context: Context) = play(context, UiCue.LETTER)

    /** 悔棋 / 撤销。 */
    fun undo(context: Context) = play(context, UiCue.UNDO)

    /** 提示 / 求助。 */
    fun hint(context: Context) = play(context, UiCue.HINT)

    /** 匹配成功 / 对手来了。 */
    fun match(context: Context) = play(context, UiCue.MATCH)

    fun play(context: Context, cue: UiCue) {
        if (!enabled(context)) return
        warmup(context)
        engine?.play(cue)
    }
    private class Engine(context: Context) {
        private val audio = context.getSystemService(AudioManager::class.java)
        private val ready = ConcurrentHashMap<Int, Boolean>()
        /** 每一类的样本列表：主样本在前，额外样本依次跟上。 */
        private val ids = UiCue.entries.associateWith { mutableListOf<Int>() }
        /** 每类的轮换游标——多变体时逐次换下一个样本，避免连续听到同一份采样。 */
        private val variantCursor = IntArray(UiCue.entries.size)
        private val last = LongArray(UiCue.entries.size)
        private val streams = IntArray(3)
        private var nextStream = 0
        private var touchStream = 0
        private var touchAt = 0L
        private var semanticAt = 0L
        /**
         * 微扰用的随机源。
         *
         * 需求⑤：常用音效准备多个样本、并做音高 ±3% / 音量 ±4% 的微调，
         * 让连续触发听起来像"同一件乐器在手上有细微差别"，而不是一串复制的采样。
         * 当前每一类只有一个音频资源（程序合成），所以**先靠微扰制造自然变化**；
         * 同类的多样本素材补上之后，这里再加"避免连续用同一个样本"的轮换。
         */
        private val jitter = kotlin.random.Random(System.nanoTime())
        private val pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
            // Custom application feedback follows media volume, like the chess sounds.
            // ASSISTANCE_SONIFICATION would route to system volume while our guard checked music.
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        init {
            pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready[id] = true }
            UiCue.entries.forEach { cue ->
                val list = ids.getValue(cue)
                list.add(pool.load(context, cue.raw, 1))
                cue.extraRaw.forEach { list.add(pool.load(context, it, 1)) }
            }
        }
        @Synchronized fun silence() { streams.forEach(pool::stop); touchStream=0 }
        @Synchronized fun play(cue: UiCue) {
            val manager = audio ?: return
            if (manager.ringerMode != AudioManager.RINGER_MODE_NORMAL || manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) return
            // 多变体轮换：逐次换下一个**已就绪**的样本，连按同一类声音不会像同一份采样在复读。
            // 只挑 ready 的，是因为 SoundPool 加载是异步的——拿没加载完的 id 播会静默失败。
            val usable = ids.getValue(cue).filter { ready[it] == true }
            if (usable.isEmpty()) return
            val slot = variantCursor[cue.ordinal].coerceIn(0, usable.size - 1)
            variantCursor[cue.ordinal] = (slot + 1) % usable.size
            val id = usable[slot]
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
            // ±3% 音高 / ±4% 音量：范围刻意做得很小，小到听不出"跑调"，
            // 但足以让连按、连响的同一声音不显得是同一份采样在重播。
            val rate = 1f + (jitter.nextFloat() - .5f) * PITCH_VARIATION
            val volume = (cue.volume * (1f + (jitter.nextFloat() - .5f) * VOLUME_VARIATION)).coerceIn(0f, 1f)
            val stream = pool.play(id, volume, volume, 1, 0, rate)
            streams[nextStream] = stream; nextStream = (nextStream+1)%streams.size
            if (cue == UiCue.TOUCH) touchStream=stream
        }
    }
}
