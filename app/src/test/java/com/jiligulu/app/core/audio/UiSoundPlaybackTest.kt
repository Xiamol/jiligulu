package com.jiligulu.app.core.audio

import android.app.Application
import android.media.AudioManager
import android.media.SoundPool
import com.jiligulu.app.R
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSoundPool
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UiSoundPlaybackTest {
    private lateinit var audio: AudioManager
    private lateinit var pool: SoundPool
    private lateinit var sounds: ShadowSoundPool
    private var enabledBefore = true
    @Before fun prepare() {
        val app = RuntimeEnvironment.getApplication()
        enabledBefore = UiSound.enabled(app)
        ReflectionHelpers.setStaticField(UiSound::class.java, "engine", null)
        UiSound.setEnabled(app, true)
        audio = app.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        UiSound.warmup(app)
        val engine = ReflectionHelpers.getStaticField<Any>(UiSound::class.java, "engine")
        pool = ReflectionHelpers.getField(engine, "pool")
        sounds = Shadow.extract(pool)
        UiCue.entries.forEach { cue ->
            sounds.notifyResourceLoaded(cue.raw, true)
            cue.extraRaw.forEach { sounds.notifyResourceLoaded(it, true) }
        }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
    }
    @After fun close() {
        UiSound.setEnabled(RuntimeEnvironment.getApplication(), enabledBefore)
        pool.release()
        ReflectionHelpers.setStaticField(UiSound::class.java, "engine", null)
    }
    @Test fun stoneAndEnvelopePlayOnMediaWhileThePhoneRingerIsSilentOrVibrating() {
        val app = RuntimeEnvironment.getApplication()
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        UiSound.stoneMove(app)
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        UiSound.envelope(app)
        assertTrue(sounds.wasResourcePlayed(R.raw.ui_stone_move))
        assertTrue(sounds.wasResourcePlayed(R.raw.ui_envelope))
    }
    @Test fun mediaMuteAndTheAppSwitchSuppressWithoutThrottlingTheNextAllowedMove() {
        val app = RuntimeEnvironment.getApplication()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        UiSound.stoneMove(app)
        assertFalse(sounds.wasResourcePlayed(R.raw.ui_stone_move))
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        UiSound.setEnabled(app, false)
        UiSound.stoneMove(app)
        assertFalse(sounds.wasResourcePlayed(R.raw.ui_stone_move))
        UiSound.setEnabled(app, true)
        UiSound.stoneMove(app)
        assertEquals(1, sounds.getResourcePlaybacks(R.raw.ui_stone_move).size)
    }
    @Test fun sixQuickCalculatorPressesAllPlayAndRotateTheThreeLoadedSamples() {
        val app = RuntimeEnvironment.getApplication()
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        repeat(6) { UiSound.calculator(app); ShadowSystemClock.advanceBy(Duration.ofMillis(10)) }
        listOf(R.raw.ui_calculator, R.raw.ui_calculator_2, R.raw.ui_calculator_3).forEach { id ->
            assertEquals(2, sounds.getResourcePlaybacks(id).size)
        }
    }
    @Test fun rapidAcceptedStonePlacementsAreNotLostToAGenericDebounce() {
        val app = RuntimeEnvironment.getApplication()
        repeat(4) { UiSound.stoneMove(app); ShadowSystemClock.advanceBy(Duration.ofMillis(40)) }
        assertEquals(4, sounds.getResourcePlaybacks(R.raw.ui_stone_move).size)
    }
}
