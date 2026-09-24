package com.jiligulu.app.ui.voice

import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Only the verified model archive is unpacked, into an app-owned staging directory. */
internal object OfflineModelFiles {
    const val ROOT = "vosk-model-small-cn-0.22"
    const val SHA256 = "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba"
    private val required = listOf("am/final.mdl", "conf/model.conf", "graph/Gr.fst", "graph/HCLr.fst")

    fun installed(directory: File): Boolean = File(directory, ".verified").let {
        it.isFile && it.readText() == SHA256 && required.all { name -> File(directory, "$ROOT/$name").isFile }
    }

    fun install(parent: File, archive: () -> InputStream): File {
        val target = File(parent, "vosk-cn-0.22")
        if (installed(target)) return File(target, ROOT)
        parent.mkdirs()
        val staging = File(parent, "vosk-cn-0.22-${java.util.UUID.randomUUID()}.partial")
        check(staging.mkdir())
        try {
            // Verify before extraction; model files are not trusted merely because a ZIP opened.
            val digest = MessageDigest.getInstance("SHA-256")
            archive().use { input -> DigestInputStream(input, digest).use { stream ->
                val buffer = ByteArray(32 * 1024)
                while (stream.read(buffer) >= 0) { /* Hash without holding the archive in memory. */ }
            } }
            require(digest.digest().joinToString("") { "%02x".format(it) } == SHA256) { "离线模型校验失败" }
            archive().use { extract(it, staging) }
            require(required.all { File(staging, "$ROOT/$it").isFile }) { "离线模型文件不完整" }
            File(staging, ".verified").writeText(SHA256)
            if (target.exists()) check(target.deleteRecursively())
            check(staging.renameTo(target))
            return File(target, ROOT)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    internal fun extract(input: InputStream, output: File) {
        val base = output.canonicalPath + File.separator
        var total = 0L
        var count = 0
        ZipInputStream(input).use { zip ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 100)
                require(entry.name.startsWith("$ROOT/") && !entry.name.contains('\\'))
                val file = File(output, entry.name)
                require(file.canonicalPath.startsWith(base)) { "Invalid model path" }
                if (entry.isDirectory) file.mkdirs() else {
                    file.parentFile!!.mkdirs()
                    file.outputStream().use { sink ->
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= 96L * 1024 * 1024) { "Model is larger than expected" }
                            sink.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
