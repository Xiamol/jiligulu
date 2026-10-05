package com.jiligulu.app.ui.littleworld

import org.junit.Assert.*
import org.junit.Test

class HiddenGameHelpTapSequenceTest {
    @Test fun exactlySevenEligibleTapsTriggerOnce() {
        val taps = HiddenGameHelpTapSequence()
        repeat(6) { assertFalse(taps.tap(it * 100L, true)) }
        assertTrue(taps.tap(600L, true))
        assertFalse(taps.tap(700L, true))
    }
    @Test fun PausedOrOtherTurnCannotKeepAPartialSequence() {
        val taps = HiddenGameHelpTapSequence()
        repeat(6) { taps.tap(it * 100L, true) }
        assertFalse(taps.tap(600L, false))
        assertFalse(taps.tap(700L, true))
    }
    @Test fun SlowSequencesAndExplicitResetStartOver() {
        val taps = HiddenGameHelpTapSequence()
        repeat(6) { taps.tap(it * 100L, true) }
        assertFalse(taps.tap(5000L, true))
        taps.reset()
        repeat(6) { assertFalse(taps.tap(5100L + it * 100L, true)) }
        assertTrue(taps.tap(5800L, true))
    }
}
