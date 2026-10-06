package com.jiligulu.app.ui.memories

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** Deletion is limited to our copied JPGs/generated PNGs and never crosses an active commit claim. */
internal class OwnedMediaStore(root: File) {
    private val directory = root.canonicalFile
    private val lock = Any()
    private val claims = hashMapOf<String, Int>()
    private var claimEpoch = 0L

    fun claim(path: String): AutoCloseable? = synchronized(lock) {
        val file = owned(path)?.takeIf { it.isFile } ?: return@synchronized null
        val key = file.path
        claims[key] = (claims[key] ?: 0) + 1
        claimEpoch++
        val closed = AtomicBoolean(false)
        AutoCloseable {
            if (closed.compareAndSet(false, true)) synchronized(lock) {
                val left = (claims[key] ?: 1) - 1
                if (left == 0) claims.remove(key) else claims[key] = left
                claimEpoch++
            }
        }
    }

    suspend fun release(paths: Collection<String>, referenceSnapshot: suspend () -> Set<String>) {
        val (epoch, candidates) = synchronized(lock) {
            claimEpoch to paths.distinct().mapNotNull(::owned).filter { it.isFile && (claims[it.path] ?: 0) == 0 }
        }
        if (candidates.isEmpty()) return
        val references = try { referenceSnapshot() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return } // A failed lookup never authorizes deletion.
        for (file in candidates) {
            synchronized(lock) {
                // Even a claim that began AND finished during lookup can have committed a new reference.
                // A changed epoch invalidates that old snapshot; conservatively leave the file intact.
                if (claimEpoch == epoch && (claims[file.path] ?: 0) == 0 && file.path !in references)
                    runCatching { file.delete() }
            }
        }
    }

    private fun owned(path: String): File? = runCatching {
        val file = File(path).canonicalFile
        val parent = file.parentFile
        val valid = parent == File(directory, "photos").canonicalFile && file.extension.equals("jpg", true) ||
            parent == File(directory, "posters").canonicalFile && file.extension.equals("png", true)
        file.takeIf { valid }
    }.getOrNull()
}
