package com.silentpulse.messenger.feature.drivemode

import android.content.Context
import com.silentpulse.messenger.util.Preferences
import timber.log.Timber
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
    }

    fun createEngine(): TtsEngine {
        val engineType = prefs.driveModeTtsEngine.get()

        return when (engineType) {
            ENGINE_KOKORO -> {
                Timber.d("Creating Kokoro TTS engine (Sherpa-ONNX)")
                KokoroTtsEngine(context, prefs)
            }
            ENGINE_ANDROID -> {
                Timber.d("Creating Android TTS engine")
                AndroidTtsEngine(context)
            }
            else -> {
                Timber.w("Unknown TTS engine: $engineType, defaulting to Android TTS")
                AndroidTtsEngine(context)
            }
        }
    }
}
