package com.jiligulu.app.ui.littleworld

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

data class RapfiNativeDiagnostics(
    val status: RapfiStatus,
    val elapsedMillis: Long,
    val resourceMillis: Long,
    val setupMillis: Long,
    val searchMillis: Long,
    val requestedBudgetMillis: Long,
    val version: String?,
    val abi: String?,
    val move: GridCell?,
)

internal data class RapfiBundledFile(val name: String, val sha256: String)

internal object RapfiAssets {
    const val ENGINE_FILE = "librapfi.so"
    const val BUNDLE_ID = "d89ef3141daf54df9a039c421fcc20a227823e7c48451ae0b8b498a903ffcf42"
    val files = listOf(
        RapfiBundledFile("config.toml", "7ef7a649eed64df326db723c5e433558c759adb4d8c6df11ba8ed20425090c6f"),
        RapfiBundledFile("mix9svqfreestyle_bsmix.bin.lz4", "6bc0d1b0ff8e1d857f7f412923cd458f38f1a435087c91ae71fec3676c23ef62"),
        RapfiBundledFile("model220723.bin", "fc4abeb0455c19fd9657d90f3c34b2f49bb7355e4ef2d33de56e7be0ffa05d5b"),
    )
}

/** Offline Android PIE from nativeLibraryDir. No executable is copied into writable storage. */
object RapfiEngine {
    private val bundles = RapfiBundleStore()
    private val requestLock = ReentrantLock()
    private fun nowMillis() = System.nanoTime() / 1_000_000L

    suspend fun chooseMove(context: Context, state: GomokuState, timeBudgetMillis: Long = 5_000,
        shouldCancel: () -> Boolean = { false }): GridCell? =
        chooseMove(context, state, onDiagnostics = null, timeBudgetMillis = timeBudgetMillis, shouldCancel = shouldCancel)

    /** QA explicitly supplies the listener by name; the ordinary API produces no logs or UI. */
    suspend fun chooseMove(context: Context, state: GomokuState,
        onDiagnostics: ((RapfiNativeDiagnostics) -> Unit)?, timeBudgetMillis: Long = 5_000,
        shouldCancel: () -> Boolean = { false }): GridCell? {
        val started = nowMillis()
        val budget = timeBudgetMillis.coerceIn(100, 10_000)
        val deadline = started + budget
        return withContext(Dispatchers.IO) {
            val jobContext = currentCoroutineContext()
            val workerThread = Thread.currentThread()
            val cancelledObserved = AtomicBoolean(false)
            fun cancelled(): Boolean {
                if (shouldCancel() || !jobContext.isActive || workerThread.isInterrupted) cancelledObserved.set(true)
                return cancelledObserved.get()
            }
            fun stopped() = cancelled() || nowMillis() >= deadline
            var status = RapfiStatus.UNAVAILABLE
            var move: GridCell? = null
            var resources = 0L
            var session: RapfiSession? = null
            var transport: RapfiProcessTransport? = null
            var process: Process? = null
            var locked = false
            val app = context.applicationContext
            val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            try {
                if (!RapfiProtocol.usable(state)) status = RapfiStatus.INVALID_POSITION
                else if (!stopped()) {
                    val executable = File(app.applicationInfo.nativeLibraryDir, RapfiAssets.ENGINE_FILE)
                    if (executable.isFile && executable.canExecute()) {
                        while (!stopped() && !locked) locked = requestLock.tryLock(25, TimeUnit.MILLISECONDS)
                        if (locked && !stopped()) {
                            val resourceStarted = nowMillis()
                            val directory = bundles.prepare(File(app.noBackupFilesDir, "rapfi"), RapfiAssets.BUNDLE_ID,
                                RapfiAssets.files, openAsset = { name -> app.assets.open("rapfi/$name") }, shouldStop = ::stopped)
                            resources = nowMillis() - resourceStarted
                            if (directory == null) status = RapfiStatus.MODEL_REJECTED
                            else if (!stopped()) {
                                val native = ProcessBuilder(executable.absolutePath).directory(directory)
                                    .redirectErrorStream(true).start()
                                process = native
                                val connected = RapfiProcessTransport(native, ::stopped)
                                transport = connected
                                val request = RapfiSession(connected, absoluteDeadlineMillis = deadline)
                                session = request
                                move = request.chooseMove(state, deadline - nowMillis(), ::cancelled)
                                status = request.status
                            }
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                status = RapfiStatus.CANCELLED
            } catch (_: Exception) {
                status = RapfiStatus.PROTOCOL_FAILED
            } finally {
                transport?.close()
                if (transport == null) process?.destroyForcibly()
                if (locked) requestLock.unlock()
            }
            if (cancelled()) { move = null; status = RapfiStatus.CANCELLED }
            else if (move == null && nowMillis() >= deadline) status = RapfiStatus.TIMED_OUT
            onDiagnostics?.let { listener ->
                val diagnostics = RapfiNativeDiagnostics(status, nowMillis() - started, resources,
                    session?.setupMillis ?: 0, session?.searchMillis ?: 0, budget, session?.version, abi, move)
                runCatching { listener(diagnostics) }
            }
            move
        }
    }
}

/** All three verified data files are complete before a native process can see the directory. */
internal class RapfiBundleStore {
    private data class Verified(val path: String, val size: Long, val modified: Long)
    private val lock = ReentrantLock()
    private val verified = HashMap<String, Verified>()

    fun prepare(root: File, bundleId: String, files: List<RapfiBundledFile>, openAsset: (String) -> InputStream,
        shouldStop: () -> Boolean): File? {
        if (!bundleId.matches(Regex("[0-9a-f]{64}")) || files.isEmpty() || files.any {
                !it.name.matches(Regex("[a-zA-Z0-9._-]+")) || it.name == "." || it.name == ".." ||
                    !it.sha256.matches(Regex("[0-9a-f]{64}"))
            } || files.map { it.name }.toSet().size != files.size) return null
        fun stopped() = shouldStop() || Thread.currentThread().isInterrupted
        while (!stopped()) if (lock.tryLock(25, TimeUnit.MILLISECONDS)) break
        if (!lock.isHeldByCurrentThread) return null
        var temporary: File? = null
        try {
            if (stopped()) return null
            val directory = File(root, bundleId)
            if (!directory.isDirectory && !directory.mkdirs()) return null
            for (spec in files) {
                if (stopped()) return null
                val file = File(directory, spec.name)
                val known = verified[file.absolutePath]
                if (file.isFile && known != null && known.size == file.length() && known.modified == file.lastModified()) continue
                if (file.isFile && file.inputStream().use { hash(it, null, ::stopped) } == spec.sha256) {
                    verified[file.absolutePath] = Verified(file.absolutePath, file.length(), file.lastModified())
                    continue
                }
                val partial = File.createTempFile("resource-", ".tmp", directory)
                temporary = partial
                val actual = openAsset(spec.name).use { input -> FileOutputStream(partial).use { output ->
                    val result = hash(input, output, ::stopped)
                    if (result != null) output.fd.sync()
                    result
                } }
                if (actual != spec.sha256 || stopped()) return null
                Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                temporary = null
                verified[file.absolutePath] = Verified(file.absolutePath, file.length(), file.lastModified())
            }
            return directory.takeUnless { stopped() }
        } finally {
            temporary?.delete()
            lock.unlock()
        }
    }

    private fun hash(input: InputStream, output: FileOutputStream?, shouldStop: () -> Boolean): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (!shouldStop()) {
            val count = input.read(buffer)
            if (count < 0) return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                .takeUnless { shouldStop() }
            digest.update(buffer, 0, count)
            output?.write(buffer, 0, count)
        }
        return null
    }
}

private class RapfiProcessTransport(private val process: Process, private val shouldStop: () -> Boolean) : RapfiTransport {
    private val closed = AtomicBoolean(false)
    private val events = LinkedBlockingQueue<RapfiEvent>(128)
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val reader = Thread({
        try {
            InputStreamReader(process.inputStream, Charsets.UTF_8).use { input ->
                val line = StringBuilder()
                while (!closed.get()) {
                    val value = input.read()
                    if (value < 0) {
                        if (line.isNotEmpty()) emit(RapfiEvent.Line(line.toString()))
                        emit(RapfiEvent.Closed)
                        break
                    }
                    if (value == '\n'.code) { emit(RapfiEvent.Line(line.toString())); line.setLength(0) }
                    else if (value != '\r'.code) {
                        if (line.length >= 16_384) throw IOException("Native output exceeds its line bound")
                        line.append(value.toChar())
                    }
                }
            }
        } catch (_: Exception) {
            if (!closed.get()) runCatching { emit(RapfiEvent.Failed) }
        }
    }, "rapfi-stdout").apply { isDaemon = true; start() }

    // A blocked stdin write cannot prevent deadline/cancellation from killing its child.
    // Give the worker a short chance to send STOP/END, then close the pipe by terminating.
    private val watchdog = Thread({
        try {
            while (!closed.get()) {
                if (shouldStop()) {
                    if (!process.waitFor(100, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                    break
                }
                Thread.sleep(25)
            }
        } catch (_: InterruptedException) { /* Close interrupts this daemon. */ }
        catch (_: Exception) { if (!closed.get()) process.destroyForcibly() }
    }, "rapfi-deadline").apply { isDaemon = true; start() }

    private fun emit(event: RapfiEvent) {
        while (!closed.get() && !events.offer(event, 25, TimeUnit.MILLISECONDS)) { /* bounded backpressure */ }
    }
    override fun writeLine(command: String) {
        if (closed.get()) throw IOException("Native session is closed")
        writer.write(command); writer.newLine(); writer.flush()
    }
    override fun poll(waitMillis: Long): RapfiEvent? = events.poll(waitMillis, TimeUnit.MILLISECONDS)
    override fun stopAndClose() = close()
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // The closing worker never synchronously waits for a full stdin pipe.
        val stopper = Thread({ runCatching { writer.write("STOP\nEND\n"); writer.flush() } }, "rapfi-stop")
            .apply { isDaemon = true; start() }
        try {
            if (!process.waitFor(75, TimeUnit.MILLISECONDS)) process.destroy()
            if (!process.waitFor(75, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            reader.interrupt()
            stopper.interrupt()
            watchdog.interrupt()
        }
    }
}
