package com.silentpulse.messenger.feature.drivemode

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.silentpulse.messenger.feature.drivemode.SpeechDiagnostics as Timber
import java.util.Locale

class AndroidTtsEngine internal constructor(
    private val mainHandler: Handler,
    private val onInitialized: ((Boolean) -> Unit)?,
    private val reportFailure: (String) -> Unit,
    createEngine: (TextToSpeech.OnInitListener) -> TextToSpeech
) : TtsEngine {
    constructor(context: Context, onInitialized: ((Boolean) -> Unit)? = null) : this(
        Handler(Looper.getMainLooper()), onInitialized, { SpeechFailure.show(context, it) },
        { listener -> createPlatformEngine(context, listener) }
    )

    internal constructor(
        context: Context,
        onInitialized: ((Boolean) -> Unit)?,
        createEngine: (TextToSpeech.OnInitListener) -> TextToSpeech
    ) : this(Handler(Looper.getMainLooper()), onInitialized, { SpeechFailure.show(context, it) }, createEngine)

    companion object {
        internal fun preferredEnginePackage(context: Context): String? {
            val googleInstalled = try {
                context.packageManager.getPackageInfo("com.google.android.tts", 0)
                true
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                false
            }
            return if (googleInstalled) "com.google.android.tts" else null
        }

        private fun createPlatformEngine(context: Context, listener: TextToSpeech.OnInitListener): TextToSpeech {
            val preferred = preferredEnginePackage(context)
            return if (preferred != null) TextToSpeech(context, listener, preferred)
            else TextToSpeech(context, listener)
        }
    }
    private var tts: TextToSpeech? = null
    private data class Completion(val done: () -> Unit, val error: (String) -> Unit)
    private val completionCallbacks = mutableMapOf<String, Completion>()
    private var utteranceId = 0
    private var initialized = false
    private var closed = false
    private var generation = 0
    private var requestedLocale = Locale.getDefault()
    private val initTimeout = Runnable {
        if (!closed && !initialized) {
            shutdown()
            failureReason = "tts_init_failed"
            onInitialized?.invoke(false)
            reportFailure("tts_init_failed")
        }
    }

    override var isReady = false
        private set
    override var failureReason: String? = "tts_not_ready"
        private set

    init {
        val listener = TextToSpeech.OnInitListener { status ->
            // The callback can precede assignment of tts; serialize all state on the main thread.
            mainHandler.post { onTtsInit(status) }
        }
        tts = createEngine(listener)
        mainHandler.postDelayed(initTimeout, 10_000L)
    }

    private fun onTtsInit(status: Int) {
        if (closed) return
        mainHandler.removeCallbacks(initTimeout)
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            failureReason = "tts_init_failed"
            onInitialized?.invoke(false)
            reportFailure("tts_init_failed")
            return
        }
        initialized = true
        val listenerStatus = engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                mainHandler.post { completionCallbacks.remove(utteranceId)?.done?.invoke() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = reportUtteranceError(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = reportUtteranceError(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = reportUtteranceError(utteranceId)
        })
        if (listenerStatus != TextToSpeech.SUCCESS) {
            initialized = false
            failureReason = "tts_init_failed"
            onInitialized?.invoke(false)
            reportFailure("tts_init_failed")
            return
        }
        setLocale(requestedLocale)
        onInitialized?.invoke(isReady)
        failureReason?.let(reportFailure)
    }

    private fun reportUtteranceError(id: String?) {
        mainHandler.post {
            val callback = completionCallbacks.remove(id) ?: return@post
            failureReason = "tts_synthesis_failed"
            isReady = false
            Timber.w("AndroidTTS: synthesis failed")
            reportFailure("tts_synthesis_failed")
            callback.error("tts_synthesis_failed")
        }
    }

    override fun setLocale(locale: Locale): Boolean {
        requestedLocale = locale
        // Invalidate first so a failed locale switch cannot reuse the previous voice.
        isReady = false
        val engine = tts
        failureReason = if (!initialized || engine == null) "tts_not_ready"
        else OfflineTts.prepare(engine, locale)
        isReady = failureReason == null
        return isReady
    }

    override fun speak(text: String, onError: (String) -> Unit, onDone: () -> Unit) {
        val locale = requestedLocale
        val token = generation
        mainHandler.post {
            if (token != generation) return@post
            val engine = tts
            if (!initialized || engine == null || closed) {
                val code = failureReason ?: "tts_not_ready"
                reportFailure(code)
                onError(code)
                return@post
            }
            val id = "utterance_${utteranceId++}"
            completionCallbacks[id] = Completion(onDone, onError)
            val failure = OfflineTts.speak(engine, locale, text, TextToSpeech.QUEUE_ADD, null, id)
            failureReason = failure
            isReady = failure == null
            if (failure != null) {
                Timber.w("AndroidTTS: speech blocked (%s)", failure)
                reportFailure(failure)
                completionCallbacks.remove(id)?.error?.invoke(failure)
            }
        }
    }

    override fun stop() {
        // Explicit interruption discards callbacks; VoiceInteractor owns recovery.
        generation++
        completionCallbacks.clear()
        tts?.stop()
    }

    override fun shutdown() {
        closed = true
        mainHandler.removeCallbacks(initTimeout)
        stop()
        tts?.shutdown()
        tts = null
        initialized = false
        isReady = false
        failureReason = "tts_not_ready"
    }
}
