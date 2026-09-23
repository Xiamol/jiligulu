package com.jiligulu.app.ui.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechInputControllerTest {
    private class Engine : SpeechInputEngine {
        lateinit var listener: SpeechInputEngine.Listener
        var stops = 0; var destroys = 0; var cancels = 0
        override fun start(listener: SpeechInputEngine.Listener) { this.listener = listener }
        override fun stop() { stops++ }
        override fun cancel() { cancels++ }
        override fun destroy() { destroys++ }
    }

    @Test fun transcriptIsDeliveredOnlyAfterReleaseAndOnlyOnce() {
        val engine = Engine()
        val control = SpeechInputController { engine }
        control.start(); engine.listener.ready(); engine.listener.partial("水")
        engine.listener.result("水，3")
        assertNull(control.state.value.result)
        assertEquals(VoicePhase.CAPTURED, control.state.value.phase)
        control.stop()
        val result = control.state.value.result!!
        assertEquals("水，3", result.text)
        assertEquals(1, engine.destroys)
        engine.listener.result("不该覆盖")
        assertEquals(result, control.state.value.result)
        control.consumeResult(result.id)
        assertNull(control.state.value.result)
    }

    @Test fun quickReleaseWaitsForReadyBeforeStoppingRecognition() {
        val engine = Engine()
        val control = SpeechInputController { engine }
        control.start(); control.stop()
        assertEquals(0, engine.stops)
        engine.listener.ready()
        assertEquals(1, engine.stops)
        engine.listener.result("昨天早餐9元")
        assertEquals("昨天早餐9元", control.state.value.result!!.text)
    }

    @Test fun cancellationDiscardsLateResultsAndDoesNotPoisonTheNextRecording() {
        val old = Engine(); val fresh = Engine()
        var calls = 0
        val control = SpeechInputController { if (calls++ == 0) old else fresh }
        control.start(); control.cancel()
        control.start(); fresh.listener.ready()
        old.listener.result("旧录音")
        assertNull(control.state.value.result)
        control.stop(); fresh.listener.result("新的文字")
        assertEquals("新的文字", control.state.value.result!!.text)
        assertEquals(1, old.cancels)
    }

    @Test fun missingServiceAndTimeoutFailWithoutInventingText() {
        val missing = SpeechInputController { error("not installed") }
        missing.start()
        assertFalse(missing.state.value.busy)
        assertNotNull(missing.state.value.error)
        val engine = Engine(); val control = SpeechInputController { engine }
        control.start(); control.stop()
        control.timeout(control.state.value.session)
        engine.listener.result("迟到的结果")
        assertNull(control.state.value.result)
        assertFalse(control.state.value.busy)
        assertEquals(1, engine.destroys)
    }
}
