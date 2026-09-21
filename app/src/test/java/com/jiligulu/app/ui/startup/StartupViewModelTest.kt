package com.jiligulu.app.ui.startup

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupViewModelTest {
    @After fun restoreMain() { Dispatchers.resetMain() }

    @Test fun `fast data loads behind a minimum entry animation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = StartupViewModel({ "名字" }, { testScheduler.currentTime })
        try {
            vm.enter()
            runCurrent()
            assertTrue(vm.state.value.prepared)
            assertFalse(vm.state.value.ready)
            advanceTimeBy(649)
            runCurrent()
            assertFalse(vm.state.value.ready)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(vm.state.value.ready)
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun `slow loading does not add another full animation delay`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = StartupViewModel({ delay(900); "名字" }, { testScheduler.currentTime })
        try {
            vm.enter()
            advanceTimeBy(850)
            runCurrent()
            assertFalse(vm.state.value.ready)
            advanceTimeBy(50)
            runCurrent()
            assertTrue(vm.state.value.ready)
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }

    @Test fun `stale activity stop cannot cancel the next entry and failed reads remain retryable`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var fail = true
        val vm = StartupViewModel({ if (fail) throw java.io.IOException(); "名字" }, { testScheduler.currentTime })
        try {
            val old = vm.enter()
            runCurrent()
            assertNotNull(vm.state.value.error)
            assertFalse(vm.state.value.ready)
            fail = false
            vm.enter()
            vm.pause(old)
            advanceUntilIdle()
            assertTrue(vm.state.value.ready)
            assertNull(vm.state.value.error)
        } finally { vm.viewModelScope.cancel(); runCurrent() }
    }
}
