package com.jiligulu.app.ui.littleworld

import android.content.Context
import android.os.Looper
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

/** Pinned APK resources only. No runtime engine/model download or execution from writable storage. */
internal object PikafishAssets {
    const val ENGINE_FILE = "libpikafish.so"
    const val MODEL_ASSET = "pikafish/pikafish.nnue"
    // Model member from the verified official Pikafish 2026-09-06 release archive.
    const val MODEL_SHA256 = "7d13d73569a9b571ba0eb20cf1596247bc2a42738967e61afef6482b231e900e"
}

object PikafishEngine {
    private val models = PikafishModelStore()

    /** Worker-only, bounded native assistance. Null means unavailable, failed, or cancelled. */
    fun chooseMove(context: Context, state: XiangqiState, timeBudgetMillis: Long = 1_500,
        shouldCancel: () -> Boolean = { false }): XiangqiMove? {
        fun cancelled() = shouldCancel() || Thread.currentThread().isInterrupted
        if (Looper.myLooper() == Looper.getMainLooper() || cancelled() ||
            state.outcome != XiangqiOutcome.PLAYING) return null
        val app = context.applicationContext
        val executable = File(app.applicationInfo.nativeLibraryDir, PikafishAssets.ENGINE_FILE)
        if (!executable.isFile || !executable.canExecute()) return null
        var transport: PikafishProcessTransport? = null
        var nativeProcess: Process? = null
        try {
            val model = models.prepare(File(app.noBackupFilesDir, "pikafish"), PikafishAssets.MODEL_SHA256,
                openAsset = { app.assets.open(PikafishAssets.MODEL_ASSET) }, shouldCancel = ::cancelled) ?: return null
            if (cancelled()) return null
            // CWD lets the engine find its default net during construction, before UCI options.
            // The executable itself remains in the installer's read-only native library directory.
            val process = ProcessBuilder(executable.absolutePath).directory(model.parentFile)
                .redirectErrorStream(true).start()
            nativeProcess = process
            transport = PikafishProcessTransport(process)
            if (cancelled()) return null
            return PikafishUciSession(transport).chooseMove(state, model.absolutePath,
                timeBudgetMillis, ::cancelled).takeUnless { cancelled() }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: Exception) {
            // Missing ABI, rejected ELF, unavailable net, protocol failure: caller may use Kotlin.
            return null
        } finally {
            transport?.close()
            if (transport == null) nativeProcess?.destroyForcibly()
        }
    }
}

/** Hash the existing file once per process; subsequent calls retain it instead of blindly copying. */
internal class PikafishModelStore {
    private data class Verified(val path: String, val size: Long, val modified: Long)
    private val lock = ReentrantLock()
    private var verified: Verified? = null

    fun prepare(root: File, expectedSha256: String, openAsset: () -> InputStream,
        shouldCancel: () -> Boolean): File? {
        val digestName = expectedSha256.lowercase()
        if (!digestName.matches(Regex("[0-9a-f]{64}"))) return null
        fun cancelled() = shouldCancel() || Thread.currentThread().isInterrupted
        while (!cancelled()) {
            if (lock.tryLock(25, TimeUnit.MILLISECONDS)) break
        }
        if (!lock.isHeldByCurrentThread) return null
        var temporary: File? = null
        try {
            if (cancelled()) return null
            val directory = File(root, digestName)
            if (!directory.isDirectory && !directory.mkdirs()) return null
            val model = File(directory, "pikafish.nnue")
            val known = verified
            if (model.isFile && known != null && known.path == model.absolutePath &&
                known.size == model.length() && known.modified == model.lastModified()) return model
            if (model.isFile && model.inputStream().use { hash(it, null, ::cancelled) } == digestName) {
                verified = Verified(model.absolutePath, model.length(), model.lastModified())
                return model.takeUnless { cancelled() }
            }
            if (cancelled()) return null
            val partial = File.createTempFile("network-", ".tmp", directory)
            temporary = partial
            val copiedHash = openAsset().use { input ->
                FileOutputStream(partial).use { output ->
                    val result = hash(input, output, ::cancelled)
                    if (result != null) output.fd.sync()
                    result
                }
            }
            if (copiedHash != digestName || cancelled()) return null
            Files.move(partial.toPath(), model.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            temporary = null
            verified = Verified(model.absolutePath, model.length(), model.lastModified())
            return model.takeUnless { cancelled() }
        } finally {
            temporary?.delete()
            lock.unlock()
        }
    }

    private fun hash(input: InputStream, copyTo: FileOutputStream?, shouldCancel: () -> Boolean): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (!shouldCancel()) {
            val count = input.read(buffer)
            if (count < 0) return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                .takeUnless { shouldCancel() }
            digest.update(buffer, 0, count)
            copyTo?.write(buffer, 0, count)
        }
        return null
    }
}

/** One daemon drains stdout/stderr; the search worker only performs timed, cancellable polls. */
private class PikafishProcessTransport(private val process: Process) : PikafishUciTransport {
    private val closed = AtomicBoolean(false)
    private val events = LinkedBlockingQueue<PikafishUciEvent>(128)
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val reader = Thread({
        try {
            InputStreamReader(process.inputStream, Charsets.UTF_8).use { input ->
                val line = StringBuilder()
                while (!closed.get()) {
                    val value = input.read()
                    if (value < 0) {
                        if (line.isNotEmpty()) emit(PikafishUciEvent.Line(line.toString()))
                        emit(PikafishUciEvent.Closed)
                        break
                    }
                    if (value == '\n'.code) {
                        emit(PikafishUciEvent.Line(line.toString()))
                        line.setLength(0)
                    } else if (value != '\r'.code) {
                        if (line.length >= 16_384) throw IOException("Engine output line exceeds its bound")
                        line.append(value.toChar())
                    }
                }
            }
        } catch (_: Exception) {
            if (!closed.get()) runCatching { emit(PikafishUciEvent.Failed) }
        }
    }, "pikafish-stdout").apply { isDaemon = true; start() }

    private fun emit(event: PikafishUciEvent) {
        while (!closed.get() && !events.offer(event, 25, TimeUnit.MILLISECONDS)) { /* bounded backpressure */ }
    }

    override fun writeLine(command: String) {
        if (closed.get()) throw IOException("Engine session is closed")
        writer.write(command); writer.newLine(); writer.flush()
    }

    override fun poll(waitMillis: Long): PikafishUciEvent? = events.poll(waitMillis, TimeUnit.MILLISECONDS)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { writer.write("stop\nquit\n"); writer.flush() }
        // Never wait indefinitely for bestmove/EOF from a cancelled or crashed native process.
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
        }
    }
}
