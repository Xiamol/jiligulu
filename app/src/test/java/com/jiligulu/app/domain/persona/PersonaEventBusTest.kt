package com.jiligulu.app.domain.persona

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaEventBusTest {
    @After
    fun resetVisibility() {
        PersonaEventBus.updateHostVisibility(false)
    }

    @Test
    fun `visible flag alone does not consume a reminder without a subscriber`() {
        PersonaEventBus.updateHostVisibility(true)

        assertFalse(PersonaEventBus.emit(PersonaEventBus.Event.WaterTick))
    }

    @Test
    fun `a retained but hidden subscriber does not consume reminders`() = runBlocking {
        val received = mutableListOf<PersonaEventBus.Event>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            PersonaEventBus.events.collect { received += it }
        }
        try {
            PersonaEventBus.updateHostVisibility(false)
            assertFalse(PersonaEventBus.emit(PersonaEventBus.Event.WaterTick))

            PersonaEventBus.updateHostVisibility(true)
            assertTrue(PersonaEventBus.emit(PersonaEventBus.Event.WaterTick))
            yield()
            assertEquals(listOf(PersonaEventBus.Event.WaterTick), received)
        } finally {
            collector.cancelAndJoin()
        }
    }
}
