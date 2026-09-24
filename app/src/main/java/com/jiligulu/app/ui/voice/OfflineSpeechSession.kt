package com.jiligulu.app.ui.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class OfflineModelPhase { EMPTY, LOADING, READY, FAILED }
data class OfflineModelState(val phase: OfflineModelPhase = OfflineModelPhase.EMPTY, val error: String? = null)

/** Native model, decoder and microphone are owned by one worker; none are closed mid-decode. */
class OfflineSpeechSession(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "GuluOfflineSpeech") }
    private val main = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(OfflineModelState())
    val state = mutable.asStateFlow()
    private var model: Model? = null // Accessed only by worker.
    @Volatile private var desired = false
    @Volatile private var closed = false
    @Volatile private var epoch = 0L
    private val active = AtomicReference<Capture?>(null)

    @Synchronized fun prepare() {
        if (closed || desired) return
        desired = true
        val token = ++epoch
        mutable.value = OfflineModelState(OfflineModelPhase.LOADING)
        worker.execute {
            if (closed || !desired || token != epoch) return@execute
            val started = SystemClock.elapsedRealtime()
            try {
                val path = synchronized(installLock) {
                    OfflineModelFiles.install(File(context.filesDir, "offline-voice")) {
                        context.assets.open("offline_voice/model.zip")
                    }
                }
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val loaded = Model(path.absolutePath)
                if (closed || !desired || token != epoch) loaded.close() else {
                    model = loaded
                    mutable.value = OfflineModelState(OfflineModelPhase.READY)
                    Log.i("GuluOfflineSpeech", "model ready in ${SystemClock.elapsedRealtime() - started}ms")
                }
            } catch (failure: Exception) {
                Log.w("GuluOfflineSpeech", "model preparation failed: ${failure.javaClass.simpleName}")
                preparationFailed(token)
            } catch (failure: LinkageError) {
                Log.w("GuluOfflineSpeech", "native engine failed: ${failure.javaClass.simpleName}")
                preparationFailed(token)
            }
        }
    }

    @Synchronized private fun preparationFailed(token: Long) {
        if (!closed && token == epoch) {
            desired = false
            mutable.value = OfflineModelState(OfflineModelPhase.FAILED, "离线模型没能准备好，请重试；若仍失败请重新安装完整试用包。")
        }
    }

    @Synchronized fun unload() {
        if (closed) return
        desired = false; epoch++
        active.get()?.cancelled?.set(true)
        mutable.value = OfflineModelState()
        worker.execute { model?.close(); model = null; Log.i("GuluOfflineSpeech", "model released") }
    }

    @Synchronized fun close() {
        if (closed) return
        unload()
        closed = true
        worker.shutdown() // Queued cleanup runs after recording stops; never interrupt native inference.
    }

    fun engine(): SpeechInputEngine = object : SpeechInputEngine {
        private val capture = Capture()
        override fun start(listener: SpeechInputEngine.Listener) {
            check(!closed && mutable.value.phase == OfflineModelPhase.READY) { "Model not ready" }
            check(active.compareAndSet(null, capture)) { "A recording is still closing" }
            worker.execute { recognize(capture, listener) }
        }
        override fun stop() { capture.stop.set(true) }
        override fun cancel() { capture.cancelled.set(true) }
        override fun destroy() { capture.cancelled.set(true) }
    }

    private fun recognize(capture: Capture, listener: SpeechInputEngine.Listener) {
        var recorder: AudioRecord? = null
        var decoder: Recognizer? = null
        val pieces = mutableListOf<String>()
        var finalText: String? = null
        var error: String? = null
        try {
            if (capture.cancelled.get() || !desired) return
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Microphone permission required")
            }
            decoder = Recognizer(checkNotNull(model), SAMPLE_RATE.toFloat())
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 8192))
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            post(capture) { listener.ready() }
            val pcm = ShortArray(1024)
            var lastPartial = ""
            val started = SystemClock.elapsedRealtime()
            while (!capture.cancelled.get() && !capture.stop.get() && SystemClock.elapsedRealtime() - started < 31_000) {
                val count = recorder.read(pcm, 0, pcm.size)
                check(count >= 0) { "AudioRecord read failed" }
                if (count == 0) continue
                if (decoder.acceptWaveForm(pcm, count)) {
                    speechText(decoder.result, "text").takeIf { it.isNotBlank() }?.let { pieces += it }
                }
                val partial = (pieces + speechText(decoder.partialResult, "partial")).filter { it.isNotBlank() }.joinToString("，")
                if (partial != lastPartial) {
                    lastPartial = partial
                    post(capture) { listener.partial(partial) }
                }
            }
            if (!capture.cancelled.get()) {
                speechText(decoder.finalResult, "text").takeIf { it.isNotBlank() }?.let { pieces += it }
                finalText = pieces.joinToString("，")
            }
        } catch (_: SecurityException) { error = "需要麦克风权限才能录音。"
        } catch (_: Exception) { error = "离线识别这次没完成，请再试一次。"
        } finally {
            runCatching { recorder?.stop() }
            recorder?.release()
            decoder?.close()
            active.compareAndSet(capture, null)
        }
        if (!capture.cancelled.get()) {
            val failure = error
            val text = finalText.orEmpty()
            if (failure != null) post(capture) { listener.error(failure) }
            else post(capture) { listener.result(text) }
        }
    }

    private fun post(capture: Capture, callback: () -> Unit) {
        main.post { if (!closed && !capture.cancelled.get()) callback() }
    }

    private class Capture {
        val stop = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private val installLock = Any()
        internal fun speechText(json: String, field: String): String =
            Json.parseToJsonElement(json).jsonObject[field]?.jsonPrimitive?.contentOrNull.orEmpty()
                .replace(Regex("(?<=[\\p{IsHan}0-9])\\s+(?=[\\p{IsHan}0-9])"), "").trim()
    }
}
