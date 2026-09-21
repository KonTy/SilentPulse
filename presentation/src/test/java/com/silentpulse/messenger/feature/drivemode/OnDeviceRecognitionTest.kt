package com.silentpulse.messenger.feature.drivemode

import android.speech.SpeechRecognizer
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import java.util.Locale

class OnDeviceRecognitionTest {
    @Test fun `old Android never checks or creates a recognizer`() {
        val result = OnDeviceRecognition.create(30, { error("API must not be called") }, { error("must not create") })
        assertEquals(OnDeviceRecognition.Creation.Unavailable("on_device_requires_android_12"), result)
    }

    @Test fun `missing on-device service never creates a recognizer`() {
        val result = OnDeviceRecognition.create(31, { false }, { error("must not create") })
        assertEquals(OnDeviceRecognition.Creation.Unavailable("on_device_unavailable"), result)
    }

    @Test fun `only on-device creator is used`() {
        val recognizer = mock(SpeechRecognizer::class.java)
        assertEquals(OnDeviceRecognition.Creation.Ready(recognizer),
            OnDeviceRecognition.create(31, { true }, { recognizer }))
    }

    @Test fun `creation failure returns unavailable without fallback`() {
        assertEquals(OnDeviceRecognition.Creation.Unavailable("on_device_unavailable"),
            OnDeviceRecognition.create(33, { true }, { throw UnsupportedOperationException() }))
        assertEquals(OnDeviceRecognition.Creation.Unavailable("on_device_init_failed"),
            OnDeviceRecognition.create(33, { true }, { throw IllegalStateException() }))
        assertEquals(OnDeviceRecognition.Creation.Unavailable("permission_denied"),
            OnDeviceRecognition.create(33, { throw SecurityException() }, { error("must not create") }))
    }

    @Test fun `installed language requires matching requested locale`() {
        assertTrue(OnDeviceRecognition.isLanguageInstalled(listOf("en-US"), Locale.US))
        assertTrue(OnDeviceRecognition.isLanguageInstalled(listOf("en_US"), Locale.US))
        assertFalse(OnDeviceRecognition.isLanguageInstalled(emptyList(), Locale.US))
        assertFalse(OnDeviceRecognition.isLanguageInstalled(listOf("en-GB", "fr-FR"), Locale.US))
    }

    @Test fun `language and service errors are explicit unavailability`() {
        assertEquals("on_device_language_unavailable", OnDeviceRecognition.errorCode(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
        assertEquals("on_device_language_unsupported", OnDeviceRecognition.errorCode(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED))
        assertEquals("on_device_service_error", OnDeviceRecognition.errorCode(SpeechRecognizer.ERROR_NETWORK))
    }
}
