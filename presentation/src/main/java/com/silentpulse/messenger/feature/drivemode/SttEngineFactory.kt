package com.silentpulse.messenger.feature.drivemode

import android.content.Context
import android.speech.SpeechRecognizer
import com.silentpulse.messenger.util.Preferences
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Creates the appropriate offline STT engine based on user preference.
 *
 * Priority order:
 *   1. Vosk     — fully air-gapped, no Google dependency. Needs model on device.
 *   2. Android  — uses on-device SpeechRecognizer (INTERNET blocked in manifest).
 *   3. Whisper  — last resort for command recognition (slow on phone hardware).
 *
 * All engines process audio on-device only. No data leaves the hardware.
 */
class SttEngineFactory @Inject constructor(
    private val context: Context,
    private val prefs: Preferences
) {
    companion object {
        const val ENGINE_VOSK    = "vosk"
        const val ENGINE_ANDROID = "android"
        const val ENGINE_WHISPER = "whisper"
    }

    fun create(): SttEngine {
        // Read preference; default to Android SpeechRecognizer (fast + accurate)
        val stored = prefs.driveModeSttEngine.get()
        val preferred = if (stored.isBlank() || stored == ENGINE_WHISPER) {
            // Override stale "whisper" preference — Android STT is the new default.
            // Whisper is too slow (8-10s inference) for interactive voice commands.
            Timber.d("SttEngineFactory: overriding stale preference '$stored' → android")
            ENGINE_ANDROID
        } else {
            stored
        }
        Timber.d("SttEngineFactory: preferred engine=$preferred")

        return when (preferred) {
            ENGINE_VOSK -> createVosk() ?: createAndroid() ?: createWhisper()
            ENGINE_ANDROID -> createAndroid() ?: createVosk() ?: createWhisper()
            ENGINE_WHISPER -> createAndroid() ?: createWhisper() // prefer Android even if someone picks whisper
            else -> createAndroid() ?: createVosk() ?: createWhisper()
        }
    }

    private fun createVosk(): SttEngine? {
        val modelPath = prefs.driveModeVoskModelPath.get()
        if (modelPath.isBlank() || !File(modelPath).isDirectory) {
            Timber.d("SttEngineFactory: Vosk model not found at '$modelPath', skipping")
            return null
        }
        Timber.d("SttEngineFactory: creating Vosk engine (model: $modelPath)")
        return VoskSttEngine(context, modelPath)
    }

    private fun createAndroid(): SttEngine? {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Timber.d("SttEngineFactory: Android SpeechRecognizer not available, skipping")
            return null
        }
        Timber.d("SttEngineFactory: creating Android SpeechRecognizer engine (INTERNET blocked in manifest)")
        return AndroidSttEngine(context)
    }

    private fun createWhisper(): SttEngine {
        val modelPath = prefs.driveModeWhisperModelPath.get()
        val lang = prefs.driveModeWhisperLanguage.get().ifBlank { "en" }
        Timber.d("SttEngineFactory: creating Whisper engine (model: $modelPath, lang: $lang)")
        return WhisperSttEngine(context, modelPath, lang)
    }
}
