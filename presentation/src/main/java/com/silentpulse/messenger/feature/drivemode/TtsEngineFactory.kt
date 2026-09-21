package com.silentpulse.messenger.feature.drivemode

import android.content.Context
import android.speech.tts.TextToSpeech
import com.silentpulse.messenger.util.Preferences
import com.silentpulse.messenger.feature.drivemode.SpeechDiagnostics as Timber
import javax.inject.Inject

/**
 * Creates the appropriate offline TTS engine based on the user's preference.
 * All engines are fully on-device — no audio data leaves the hardware.
 */
class TtsEngineFactory @Inject constructor(
    private val context: Context,
    private val prefs: Preferences
) {
    companion object {
        const val ENGINE_ANDROID = "android"
        const val ENGINE_KOKORO  = "kokoro"

        internal fun select(
            preference: String,
            android: () -> TtsEngine,
            kokoro: () -> TtsEngine
        ): TtsEngine = if (preference == ENGINE_KOKORO) kokoro() else android()
    }

    fun createEngine(onInitialized: ((Boolean) -> Unit)? = null): TtsEngine =
        createEngine(onInitialized, null)

    internal fun createEngine(
        onInitialized: ((Boolean) -> Unit)?,
        createPlatformEngine: ((TextToSpeech.OnInitListener) -> TextToSpeech)?
    ): TtsEngine {
        val engineType = prefs.driveModeTtsEngine.get()

        return select(engineType,
            kokoro = {
                Timber.w("Selected Kokoro unavailable: native text logging cannot be disabled")
                KokoroTtsEngine().also { onInitialized?.invoke(it.isReady) }
            },
            android = {
                Timber.d("Creating Android TTS engine")
                if (createPlatformEngine == null) AndroidTtsEngine(context, onInitialized)
                else AndroidTtsEngine(context, onInitialized, createPlatformEngine)
            }
        )
    }
}
