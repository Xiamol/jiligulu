package com.jiligulu.app.ui.memories

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MemoryFileImportCancellationTest {
    @Test fun cancellationAfterTheProviderBeginsReadingDoesNotLeaveTheFinishedJpgBehind() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val fixture = Bitmap.createBitmap(80, 50, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { output ->
            assertTrue(fixture.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
        }
        fixture.recycle()
        val uri = Uri.parse("content://test.photos/cancellation-fixture")
        val closedReads = AtomicInteger()
        val parent = AtomicReference<Job>()
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            object : ByteArrayInputStream(bytes) {
                override fun close() {
                    super.close()
                    if (closedReads.incrementAndGet() == 1) parent.get().cancel()
                }
            }
        }
        val folder = File(context.filesDir, "life-memories/photos")
        val before = folder.listFiles().orEmpty().map { it.canonicalPath }.toSet()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val job = scope.launch(start = CoroutineStart.LAZY) { MemoryFiles.importPhoto(context, uri) }
            parent.set(job); job.start()
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue("The native decoder must have progressed past its bounds read", closedReads.get() >= 2)
            val after = folder.listFiles().orEmpty().map { it.canonicalPath }.toSet()
            assertEquals("Late cancellation must unlink only its new output", before, after)
        } finally { scope.cancel() }
    }
}
