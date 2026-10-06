package com.jiligulu.app.ui.calculator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalculatorDeleteLifetimeTest {
    @Test fun canceledPressCannotKeepDeletingOrClearNewInput() = runTest {
        var expression = "12345678901234567890"
        val job = launch { repeatCalculatorDelete(200,
            { expression = expression.dropLast(1) }, { expression = "" }) }
        advanceTimeBy(350); runCurrent()
        assertTrue(expression.isNotEmpty() && expression.length < 20)
        job.cancelAndJoin()
        expression += "7"
        val afterRelease = expression
        advanceTimeBy(1000); runCurrent()
        assertEquals(afterRelease, expression)
    }

    @Test fun clearDeadlineIsMeasuredFromDownWithoutAddingTheSystemLongPressDelay() = runTest {
        var expression = "12345678901234567890"
        val job = launch { repeatCalculatorDelete(200,
            { expression = expression.dropLast(1) }, { expression = "" }) }
        advanceTimeBy(449); runCurrent()
        assertTrue(expression.isNotEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals("", expression)
        job.cancelAndJoin()
        expression = "123"
        advanceTimeBy(1000); runCurrent()
        assertEquals("123", expression)
    }
}
