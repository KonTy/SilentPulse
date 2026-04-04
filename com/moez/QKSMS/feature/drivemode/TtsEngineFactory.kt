package com.moez.QKSMS.feature.drivemode

import android.content.Context
import com.moez.QKSMS.util.Preferences
import timber.log.Timber
import javax.inject.Inject

class TtsEngineFactory @Inject constructor(
    private val context: Context,
    private val prefs: Preferences
) {
    
    companion object {
        const val ENGINE_ANDROID = "android"
        const val ENGINE_PIPER = "piper"
    }
    
    /**
     * Creates the appropriate TTS engine based on user preferences
     * All engines are completely offline - no network calls
     */
    fun createEngine(): TtsEngine {
        val engineType = prefs.driveModeTtsEngine.get()
        
        return when (engineType) {
            ENGINE_PIPER -> {
                Timber.d("Creating Piper TTS engine (with Android fallback)")
                PiperTtsEngine(context)
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
