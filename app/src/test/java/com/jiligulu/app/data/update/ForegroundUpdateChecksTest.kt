package com.jiligulu.app.data.update

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ForegroundUpdateChecksTest {
    @Test fun coldEntryAndWarmReturnCheckAgainButResumeInsideTheAppDoesNot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = object : LifecycleOwner {
                val registry = LifecycleRegistry.createUnsafe(this)
                override val lifecycle: Lifecycle get() = registry
            }
            var requests = 0
            val job = launch { owner.lifecycle.checkUpdatesOnForeground { requests++ } }
            owner.registry.currentState = Lifecycle.State.CREATED
            runCurrent()
            assertEquals(0, requests)
            owner.registry.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(1, requests)
            owner.registry.currentState = Lifecycle.State.RESUMED
            runCurrent()
            assertEquals(1, requests)
            owner.registry.currentState = Lifecycle.State.CREATED
            runCurrent()
            owner.registry.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(2, requests)
            owner.registry.currentState = Lifecycle.State.DESTROYED
            runCurrent()
            job.join()
        } finally { Dispatchers.resetMain() }
    }
}
