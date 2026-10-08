package com.jiligulu.app.ui.littleworld

/** Byte-budgeted strong references. Eviction never destroys an image still held by a visible page. */
internal class ArtworkMemoryCache<K : Any, V : Any>(
    private val maxBytes: Long,
    private val weight: (V) -> Long,
) {
    private data class Entry<V>(val value: V, val bytes: Long)
    private val lock = Any()
    private val decodeLock = Any()
    private val entries = LinkedHashMap<K, Entry<V>>(16, .75f, true)
    private var totalBytes = 0L
    private var generation = 0L
    init { require(maxBytes > 0) }
    val bytes: Long get() = synchronized(lock) { totalBytes }
    fun get(key: K): V? = synchronized(lock) { entries[key]?.value }

    fun getOrLoad(key: K, loader: () -> V): V {
        get(key)?.let { return it }
        val startedGeneration = synchronized(lock) { generation }
        // Serialize cold decodes to avoid several full atlases in flight; trim never waits here.
        return synchronized(decodeLock) {
            get(key) ?: loader().also { value ->
                val bytes = weight(value).also { require(it > 0) }
                synchronized(lock) {
                    // A decode begun before background trim must not refill that cleared cache.
                    if (startedGeneration == generation && bytes <= maxBytes) {
                        entries.put(key, Entry(value, bytes))?.let { totalBytes -= it.bytes }
                        totalBytes += bytes
                        evictTo(maxBytes)
                    }
                }
            }
        }
    }

    fun trimTo(bytes: Long) = synchronized(lock) {
        generation++
        evictTo(bytes.coerceAtLeast(0))
    }
    fun clear() = trimTo(0)
    private fun evictTo(limit: Long) {
        val iterator = entries.entries.iterator()
        while (totalBytes > limit && iterator.hasNext()) {
            totalBytes -= iterator.next().value.bytes
            iterator.remove()
        }
    }
}
