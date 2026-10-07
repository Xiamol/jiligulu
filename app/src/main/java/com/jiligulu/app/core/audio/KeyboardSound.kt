package com.jiligulu.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import com.jiligulu.app.R
import java.util.concurrent.CopyOnWriteArrayList

/** The caller controls typing feedback; media volume also silences these bundled taps. */
class KeyboardSound(context: Context) : AutoCloseable {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val loaded = CopyOnWriteArrayList<Int>()
    private val pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    private var index = 0
    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded.add(id) }
        listOf(R.raw.key_tap_1, R.raw.key_tap_2, R.raw.key_tap_3).forEach { pool.load(context, it, 1) }
    }
    fun tap() {
        if (loaded.isEmpty() || (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0) == 0) return
        pool.play(loaded[index++ % loaded.size], 0.5f, 0.5f, 1, 0, 1f)
    }
    override fun close() { pool.release(); loaded.clear() }
}
