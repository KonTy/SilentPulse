package com.silentpulse.messenger.extensions

import com.silentpulse.messenger.mms.ContentType
import com.silentpulse.messenger.model.MmsPart
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MmsContentTypeTest {
    @Test
    fun `image parts can be checked for video while rendering media previews`() {
        for (mimeType in listOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/heic")) {
            val part = MmsPart().apply { type = mimeType }
            assertTrue(mimeType, part.isImage())
            assertFalse(mimeType, part.isVideo())
        }
    }

    @Test
    fun `video text smil and contact parts retain their classifications`() {
        for (mimeType in listOf("video/mp4", "video/3gpp", "video/3gpp2")) {
            val part = MmsPart().apply { type = mimeType }
            assertTrue(mimeType, part.isVideo())
            assertFalse(mimeType, part.isImage())
        }
        assertTrue(MmsPart().apply { type = "text/plain" }.isText())
        assertFalse(MmsPart().apply { type = "text/html" }.isText())
        assertTrue(MmsPart().apply { type = "application/smil" }.isSmil())
        assertTrue(MmsPart().apply { type = "text/x-vCard" }.isVCard())
        for (mimeType in listOf("", "application/octet-stream", "text/plain", "audio/amr")) {
            val part = MmsPart().apply { type = mimeType }
            assertFalse(mimeType, part.isImage())
            assertFalse(mimeType, part.isVideo())
        }
    }

    @Test
    fun `null provider MIME types remain safe`() {
        assertFalse(ContentType.isImageType(null))
        assertFalse(ContentType.isVideoType(null))
        assertFalse(ContentType.isAudioType(null))
        assertFalse(ContentType.isTextType(null))
        assertFalse(ContentType.isSupportedType(null))
    }

    @Test
    fun `MMS supported types remain distinct from media classification`() {
        assertTrue(ContentType.isSupportedImageType("image/jpeg"))
        assertTrue(ContentType.isSupportedImageType("image/gif"))
        assertTrue(ContentType.isSupportedVideoType("video/mp4"))
        assertTrue(ContentType.isSupportedAudioType("audio/amr"))
        assertTrue(ContentType.isImageType("image/heic"))
        assertFalse(ContentType.isSupportedImageType("image/heic"))
        assertTrue(ContentType.isDrmType("application/vnd.oma.drm.content"))
        assertTrue(ContentType.isUnspecified("image/*"))
    }

    @Test
    fun `supported type lists cannot be mutated through returned copies`() {
        ContentType.getImageTypes().clear()
        ContentType.getAudioTypes().clear()
        ContentType.getVideoTypes().clear()
        ContentType.getSupportedTypes().clear()
        assertTrue(ContentType.isSupportedImageType("image/jpeg"))
        assertTrue(ContentType.isSupportedAudioType("audio/amr"))
        assertTrue(ContentType.isSupportedVideoType("video/mp4"))
    }

    @Test
    fun `bundled MIME classifier cannot be shadowed by the Android framework class`() {
        assertEquals("com.silentpulse.messenger.mms.ContentType", ContentType::class.java.name)
        val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
            .first { File(it, "settings.gradle").isFile }
        for (module in listOf("android-smsmms", "data", "domain", "presentation")) {
            File(root, "$module/src/main").walkTopDown()
                .filter { it.isFile && it.extension in setOf("java", "kt") }
                .forEach { source ->
                    assertFalse(source.relativeTo(root).path,
                        source.readText().contains("com.google.android.mms.ContentType"))
                }
        }
        assertFalse(File(root,
            "android-smsmms/src/main/java/com/google/android/mms/ContentType.java").exists())
    }
}
