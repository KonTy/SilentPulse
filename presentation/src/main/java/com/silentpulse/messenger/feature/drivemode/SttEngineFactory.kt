package com.silentpulse.messenger.feature.drivemode

import android.content.Context
import com.silentpulse.messenger.util.Preferences
import timber.log.Timber
import javax.inject.Inject

/**
 * Creates the appropriate offline STT engine based on the user's preference.
 * All engines are fully on-device — no audio is sent to any server.
 */
class SttEngineFactory @Inject constructor(
    private val context: Context,
    private val prefs: Preferences
) {
    companion object {
        const val ENGINE_ANDROID = "android"
        const val ENGINE_WHISPER = "whisper"
    }

    fun create(): SttEngine {
        return when (val engine = prefs.driveModeSttEngine.get()) {
            ENGINE_WHISPER -> {
                val modelPath = prefs.driveModeWhisperModelPath.get()
                val lang      = prefs.driveModeWhisperLanguage.get().ifBlank { null }
                Timber.d("SttEngineFactory: creating Whisper engine (model: $modelPath, lang: $lang)")
                WhisperSttEngine(context, modelPath, lang)
            }
            ENGINE_ANDROID -> {
                Timber.d("SttEngineFactory: creating Android STT engine (PREFER_OFFLINE)")
                AndroidSttEngine(context)
            }
            else -> {
                Timber.w("SttEngineFactory: unknown engine \"$engine\", defaulting to Android STT")
                AndroidSttEngine(context)
            }
        }
    }
}
