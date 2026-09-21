package com.silentpulse.messenger.feature.drivemode

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer
import java.util.Locale

internal object OnDeviceRecognition {
    sealed class Creation {
        data class Ready(val recognizer: SpeechRecognizer) : Creation()
        data class Unavailable(val code: String) : Creation()
    }

    fun create(context: Context): Creation = create(
        Build.VERSION.SDK_INT,
        { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) },
        { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
    )

    // Lambdas keep pre-31 devices from resolving/calling the API and allow JVM policy tests.
    internal fun create(
        sdkInt: Int,
        available: () -> Boolean,
        createOnDevice: () -> SpeechRecognizer
    ): Creation {
        if (sdkInt < 31) return Creation.Unavailable("on_device_requires_android_12")
        return try {
            if (!available()) Creation.Unavailable("on_device_unavailable")
            else Creation.Ready(createOnDevice())
        } catch (_: UnsupportedOperationException) {
            Creation.Unavailable("on_device_unavailable")
        } catch (_: SecurityException) {
            Creation.Unavailable("permission_denied")
        } catch (_: IllegalStateException) {
            Creation.Unavailable("on_device_init_failed")
        }
    }

    fun isLanguageInstalled(installed: List<String>, locale: Locale): Boolean =
        installed.any { Locale.forLanguageTag(it.replace('_', '-')) == locale }

    fun errorCode(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech_timeout"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission_denied"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "on_device_language_unsupported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "on_device_language_unavailable"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "on_device_support_unavailable"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer_busy"
        else -> "on_device_service_error"
    }
}
