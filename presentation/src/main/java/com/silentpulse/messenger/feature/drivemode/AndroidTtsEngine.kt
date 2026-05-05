package com.silentpulse.messenger.feature.drivemode

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import timber.log.Timber
import java.util.Locale

class AndroidTtsEngine(
    private val context: Context,
    /** Optional callback fired on the TTS init thread when the engine is ready. */
    private val onInitialized: ((Boolean) -> Unit)? = null
) : TtsEngine {
    
    private var tts: TextToSpeech? = null
    private val completionCallbacks = mutableMapOf<String, () -> Unit>()
    private var utteranceId = 0
    
    override var isReady: Boolean = false
        private set
    
    init {
        // PRIVACY: Android TextToSpeech is completely offline - no network calls.
        // Samsung TTS blocks non-whitelisted packages, so we explicitly request
        // Google TTS first. onTtsInit() falls back to the system default if Google
        // TTS is not installed.
        val googleTtsInstalled = try {
            context.packageManager.getPackageInfo("com.google.android.tts", 0)
            true
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }

        val preferredEngine = if (googleTtsInstalled) "com.google.android.tts" else null
        Timber.d("AndroidTtsEngine: starting TTS (preferredEngine=$preferredEngine)")
        tts = if (preferredEngine != null)
            TextToSpeech(context, { onTtsInit(it) }, preferredEngine)
        else
            TextToSpeech(context) { onTtsInit(it) }
    }

    private fun onTtsInit(status: Int) {
        Timber.d("AndroidTtsEngine: onInit status=$status engine=${tts?.defaultEngine}")
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                val result = engine.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Timber.w("TTS language not supported: ${Locale.getDefault()}")
                }
                isReady = true
                Timber.d("AndroidTtsEngine initialized successfully (engine=${engine.defaultEngine})")
                onInitialized?.invoke(true)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Timber.d("TTS start id=$utteranceId pending=${completionCallbacks.size}")
                    }
                    override fun onDone(utteranceId: String?) {
                        Timber.d("TTS done id=$utteranceId")
                        utteranceId?.let { id -> completionCallbacks.remove(id)?.invoke() }
                    }
                    override fun onError(utteranceId: String?) {
                        Timber.e("TTS error id=$utteranceId")
                        utteranceId?.let { id -> completionCallbacks.remove(id)?.invoke() }
                    }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        Timber.w("TTS STOPPED id=$utteranceId interrupted=$interrupted remaining_callbacks=${completionCallbacks.size}")
                    }
                    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                        Timber.v("TTS word id=$utteranceId pos=$start")
                    }
                })
            }
        } else {
            Timber.e("AndroidTtsEngine: TTS engine failed to initialize (engine=${tts?.defaultEngine})")
            onInitialized?.invoke(false)
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
