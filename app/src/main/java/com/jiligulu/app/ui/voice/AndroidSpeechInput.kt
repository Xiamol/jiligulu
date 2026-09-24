package com.jiligulu.app.ui.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class AndroidSpeechInput(private val context: Context) : SpeechInputEngine {
    private val recognizer: SpeechRecognizer
    private var startedAt = 0L
    private var ready = false
    private var stopRequested = false
    private val service = runCatching { Settings.Secure.getString(context.contentResolver, "voice_recognition_service") }.getOrNull().orEmpty()
    init {
        check(SpeechRecognizer.isRecognitionAvailable(context))
        recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
    }

    override fun start(listener: SpeechInputEngine.Listener) {
        startedAt = SystemClock.elapsedRealtime()
        ready = false
        stopRequested = false
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission required")
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { ready = true; listener.ready() }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                listener.partial(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onResults(results: Bundle?) {
                listener.result(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onError(error: Int) {
                Log.w("GuluSpeech", "request=basic-v1 code=$error ready=$ready stopRequested=$stopRequested elapsedMs=${SystemClock.elapsedRealtime() - startedAt} service=$service")
                listener.error(when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "麦克风权限未开启，请在系统应用设置中允许录音。"
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清，再按住说一次吧。"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "系统语音识别暂时连不上，稍后重试或切回键盘。"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "系统语音服务正忙，稍等一下再试。"
                12, 13 -> "系统暂时不能识别中文，请检查语音服务及中文语言资源。"
                SpeechRecognizer.ERROR_AUDIO -> "系统语音服务无法读取录音（错误码 3），请关闭其他正在录音的应用后重试。"
                SpeechRecognizer.ERROR_SERVER -> "系统语音服务处理失败（错误码 4），请稍后重试。"
                SpeechRecognizer.ERROR_CLIENT -> "系统语音服务未能接受这次录音（错误码 5），请重试。"
                else -> "系统语音识别未完成（错误码 $error），可重试或切回键盘。"
                })
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Use the service's device-language defaults. Some OEM recognizers reject optional extras.
        })
    }
    override fun stop() { stopRequested = true; recognizer.stopListening() }
    override fun cancel() = recognizer.cancel()
    override fun destroy() = recognizer.destroy()
}
