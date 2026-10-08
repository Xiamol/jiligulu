package com.jiligulu.app.ui.littleworld

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChessAvatarPhotoTest {
    private fun image(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(153, 127, 188))
        return try { ByteArrayOutputStream().use { output ->
            check(bitmap.compress(format, 85, output)); output.toByteArray()
        } } finally { bitmap.recycle() }
    }
    private fun encoded(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG) =
        Base64.getEncoder().encodeToString(image(width, height, format))

    @Test fun onlySmallDecodedSquareJpegPixelsCanBecomeAnAvatar() {
        val photo = encoded(112, 112)
        assertEquals(photo, ChessAvatarPhoto.normalizedJpeg(photo))
        assertTrue(Base64.getDecoder().decode(photo).size <= ChessAvatarPhoto.MAX_JPEG_BYTES)
        val decoded = ChessAvatarPhoto.decodePreview(photo)!!
        try { assertEquals(112, decoded.width); assertEquals(112, decoded.height) } finally { decoded.recycle() }
        for (bad in listOf("https://example.invalid/picture.jpg", "file:///private/photo.jpg", "content://gallery/1",
            "not-base64", "a".repeat(ChessAvatarPhoto.MAX_BASE64_LENGTH + 1), encoded(113, 113),
            encoded(112, 80), encoded(112, 112, Bitmap.CompressFormat.PNG), Base64.getEncoder().encodeToString(byteArrayOf(-1, -40, 0, 0)))) {
            assertEquals("", ChessAvatarPhoto.normalizedJpeg(bad))
            assertNull(ChessAvatarPhoto.decodePreview(bad))
        }
    }

    @Test fun aPickedPhotoIsSampledCroppedAndStoredAsABoundedIndependentJpeg() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://jiligulu-avatar-fixture/photo")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(image(256, 512, Bitmap.CompressFormat.PNG)))
        val photo = ChessAvatarPhoto.importPhoto(context, uri)
        assertTrue(photo.length <= ChessAvatarPhoto.MAX_BASE64_LENGTH)
        assertTrue(Base64.getDecoder().decode(photo).size <= ChessAvatarPhoto.MAX_JPEG_BYTES)
        val profile = ChessPlayerProfile("棋友", "leaf", photo).normalized()
        assertEquals("leaf", profile.avatarId)
        assertEquals(photo, profile.avatarJpeg)
        assertFalse(profile.toString().contains(photo))
        val preview = ChessAvatarPhoto.decodePreview(profile.avatarJpeg)!!
        try { assertEquals(112, preview.width); assertEquals(112, preview.height) } finally { preview.recycle() }
    }

    @Test fun invalidOrUnapprovedUriDoesNotReplaceAnExistingProfile() = runBlocking {
        val profile = ChessPlayerProfile("棋友", "moon", encoded(112, 112)).normalized()
        val context = RuntimeEnvironment.getApplication()
        assertTrue(runCatching { ChessAvatarPhoto.importPhoto(context, Uri.parse("file:///private/photo.jpg")) }.isFailure)
        val broken = Uri.parse("content://jiligulu-avatar-fixture/broken")
        shadowOf(context.contentResolver).registerInputStream(broken, ByteArrayInputStream("bad image".toByteArray()))
        assertTrue(runCatching { ChessAvatarPhoto.importPhoto(context, broken) }.isFailure)
        assertTrue(ChessAvatarPhoto.importDirectory(context).listFiles().orEmpty().none { it.name.startsWith("avatar-") })
        assertEquals("moon", profile.avatarId)
        assertTrue(profile.avatarJpeg.isNotEmpty())
        assertEquals("aru", ChessPlayerProfile(avatarId = "unknown", avatarJpeg = "content://anything").normalized().avatarId)
        assertEquals("", ChessPlayerProfile(avatarJpeg = "content://anything").normalized().avatarJpeg)
    }

    @Test fun cancellingDuringTheBoundedCopyDeletesOnlyItsOwnTemporaryFile() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val directory = ChessAvatarPhoto.importDirectory(context).apply { mkdirs() }
        val sentinel = java.io.File(directory, "keep-this-existing-file").apply { writeText("untouched") }
        val originalNames = directory.listFiles().orEmpty().map { it.name }.toSet()
        val uri = Uri.parse("content://jiligulu-avatar-fixture/cancelled")
        var reads = 0
        val task = async(start = CoroutineStart.LAZY) { ChessAvatarPhoto.importPhoto(context, uri) }
        shadowOf(context.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(ByteArray(150_000)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                if (++reads == 1) task.cancel()
                return count
            }
        })
        try {
            task.start()
            assertTrue(runCatching { task.await() }.isFailure)
            task.join()
            assertTrue(task.isCancelled)
            assertEquals(1, reads)
            assertEquals(originalNames, directory.listFiles().orEmpty().map { it.name }.toSet())
            assertEquals("untouched", sentinel.readText())
        } finally { sentinel.delete() }
    }
}
