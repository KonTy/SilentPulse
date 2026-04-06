package com.silentpulse.messenger.feature.drivemode

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import timber.log.Timber
import java.util.Locale

class AndroidTtsEngine(context: Context) : TtsEngine {
    
    private var tts: TextToSpeech? = null
    private val completionCallbacks = mutableMapOf<String, () -> Unit>()
    private var utteranceId = 0
    
    override var isReady: Boolean = false
        private set
    
    init {
        // PRIVACY: Android TextToSpeech is completely offline - no network calls
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    // IMPORTANT: Set language to device default for offline operation
                    val result = engine.setLanguage(Locale.getDefault())
                    
                    if (result == TextToSpeech.LANG_MISSING_DATA || 
                        result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Timber.w("TTS language not supported: ${Locale.getDefault()}")
                        // Still mark as ready - TTS will use fallback language
                        isReady = true
                    } else {
                        isReady = true
                        Timber.d("AndroidTtsEngine initialized successfully")
                    }
                    
                    // Set up utterance progress listener for callbacks
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            // Speech started
                        }
                        
                        override fun onDone(utteranceId: String?) {
                            utteranceId?.let { id ->
                                completionCallbacks.remove(id)?.invoke()
                            }
                        }
                        
                        override fun onError(utteranceId: String?) {
                            Timber.e("TTS error for utterance: $utteranceId")
                            utteranceId?.let { id ->
                                completionCallbacks.remove(id)?.invoke()
                            }
                        }
                    })
                }
            } else {
                Timber.e("AndroidTtsEngine initialization failed")
                isReady = false
            }
        }
    }
    
    /**
     * Change the TTS output language. Safe to call at any time; takes effect on the next speak().
     */
    fun setLocale(locale: java.util.Locale) {
        tts?.let { engine ->
            val result = engine.setLanguage(locale)
            Timber.d("AndroidTtsEngine: setLocale $locale -> result $result")
        }
    }

    override fun speak(text: String, onDone: () -> Unit) {
        if (!isReady) {
            Timber.w("TTS not ready, skipping speech")
            onDone()
            return
        }
        
        tts?.let { engine ->
            val id = "utterance_${utteranceId++}"
            completionCallbacks[id] = onDone
            
            // IMPORTANT: Use QUEUE_ADD to queue speech segments
            // This is completely offline - no network involved
            val result = engine.speak(text, TextToSpeech.QUEUE_ADD, null, id)
            
            if (result == TextToSpeech.ERROR) {
                Timber.e("TTS speak failed")
                completionCallbacks.remove(id)
                onDone()
            }
        } ?: run {
            Timber.w("TTS engine is null")
            onDone()
        }
    }
    
    override fun stop() {
        tts?.stop()
        completionCallbacks.clear()
    }
    
    override fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
