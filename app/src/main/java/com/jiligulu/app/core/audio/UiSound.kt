package com.jiligulu.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import com.jiligulu.app.R
import java.util.concurrent.ConcurrentHashMap

/** Short original UI/wood sounds, no audio focus or background playback. */
object UiSound {
    private var engine: Engine? = null
    @Synchronized fun warmup(context: Context) { if(engine == null) engine = Engine(context.applicationContext) }
    fun enabled(context: Context) = context.applicationContext.getSharedPreferences("gulu_ui_feedback", Context.MODE_PRIVATE)
        .getBoolean("enabled", true)
    fun setEnabled(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences("gulu_ui_feedback", Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
    }
    fun tap(context: Context) = play(context, R.raw.ui_tap, .30f)
    fun select(context: Context) = play(context, R.raw.ui_select, .38f)
    fun drop(context: Context) = play(context, R.raw.ui_drop, .46f)
    private fun play(context: Context, resource: Int, volume: Float) {
        if (!enabled(context)) return
        warmup(context)
        engine?.play(resource, volume)
    }
    private class Engine(context: Context) {
        private val audio = context.getSystemService(AudioManager::class.java)
        private val ready = ConcurrentHashMap<Int, Boolean>()
        private val ids = HashMap<Int, Int>()
        private var lastTap = 0L
        private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        init {
            pool.setOnLoadCompleteListener { _, id, status -> if(status == 0) ready[id] = true }
            listOf(R.raw.ui_tap, R.raw.ui_select, R.raw.ui_drop).forEach { ids[it] = pool.load(context, it, 1) }
        }
        @Synchronized fun play(resource: Int, volume: Float) {
            val manager = audio ?: return
            if(manager.ringerMode != AudioManager.RINGER_MODE_NORMAL || manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) return
            val id = ids[resource] ?: return
            if(ready[id] != true) return
            if(resource == R.raw.ui_tap) {
                val now = SystemClock.elapsedRealtime()
                if(now-lastTap < 35) return
                lastTap = now
            }
            pool.play(id, volume, volume, 1, 0, 1f)
        }
    }
}
