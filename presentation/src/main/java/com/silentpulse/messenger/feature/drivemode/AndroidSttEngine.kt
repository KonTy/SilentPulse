package com.silentpulse.messenger.feature.drivemode

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.silentpulse.messenger.feature.drivemode.SpeechDiagnostics as Timber
import java.util.Locale

/**
 * Uses only Android's on-device recognition service (API 31+), never the generic service.
 * On API 33+ the requested model must already be installed. No model downloads are requested.
 * This is an API boundary, not a firewall for the separate speech-provider process.
 */
class AndroidSttEngine internal constructor(
    private val mainHandler: Handler,
    private val sdkInt: Int,
    private val createRecognizer: () -> OnDeviceRecognition.Creation
) : SttEngine {
    constructor(context: Context) : this(
        Handler(Looper.getMainLooper()), Build.VERSION.SDK_INT, { OnDeviceRecognition.create(context) }
    )
    private var recognizer: SpeechRecognizer? = null
    private var session = 0
    private var cycle = 0
    private var closed = false
    private var pendingTranscript: String? = null
    private var submitRunnable: Runnable? = null
    private var supportTimeout: Runnable? = null
    private var savedOnResult: ((String) -> Unit)? = null
    private var savedOnError: ((String) -> Unit)? = null
    private val locale = Locale.US

    private val recognizerIntent get() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // Defence in depth only: the on-device factory above is the privacy boundary.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60_000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 30_000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 15_000L)
    }

    override fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        mainHandler.post {
            cancelSession()
            savedOnResult = onResult
            savedOnError = onError
            if (closed) {
                fail("on_device_init_failed")
                return@post
            }
            if (recognizer == null) {
                when (val creation = createRecognizer()) {
                    is OnDeviceRecognition.Creation.Ready -> recognizer = creation.recognizer
                    is OnDeviceRecognition.Creation.Unavailable -> {
                        fail(creation.code)
                        return@post
                    }
                }
            }
            verifyModelAndStart(session)
        }
    }

    private fun verifyModelAndStart(token: Int) {
        val sr = recognizer ?: return
        if (sdkInt < 33) {
            // API 31–32 cannot preflight models. The on-device service reports language errors.
            startCycle(token)
            return
        }
        val timeout = Runnable {
            if (session == token && supportTimeout != null) fail("on_device_support_unavailable")
        }
        supportTimeout = timeout
        mainHandler.postDelayed(timeout, 5_000L)
        try {
            sr.checkRecognitionSupport(
                recognizerIntent,
                { command -> mainHandler.post(command) },
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if (session != token || supportTimeout == null) return
                        clearSupportTimeout()
                        if (OnDeviceRecognition.isLanguageInstalled(support.installedOnDeviceLanguages, locale)) {
                            startCycle(token)
                        } else {
                            fail("on_device_language_unavailable")
                        }
                    }

                    override fun onError(error: Int) {
                        if (session != token || supportTimeout == null) return
                        fail("on_device_support_unavailable")
                    }
                }
            )
        } catch (_: UnsupportedOperationException) {
            fail("on_device_support_unavailable")
        } catch (_: IllegalStateException) {
            fail("on_device_support_unavailable")
        } catch (_: SecurityException) {
            fail("permission_denied")
        }
    }

    private fun startCycle(token: Int) {
        if (session != token || savedOnResult == null) return
        val sr = recognizer ?: return
        val currentCycle = ++cycle
        fun active() = token == session && currentCycle == cycle && savedOnResult != null
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (active()) Timber.d("AndroidSTT: microphone ready")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                if (!active()) return
                val code = OnDeviceRecognition.errorCode(error)
                // Only ordinary end-of-speech errors may finish an already buffered result.
                // Language/service failures discard it rather than returning false success.
                if (pendingTranscript != null && (code == "no_match" || code == "speech_timeout")) return
                fail(code)
            }

            override fun onResults(results: Bundle?) {
                if (!active()) return
                val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (best.isBlank()) {
                    if (pendingTranscript == null) fail("no_match")
                    return
                }
                pendingTranscript = listOfNotNull(pendingTranscript, best).joinToString(" ")
                Timber.d("AndroidSTT: result buffered")
                scheduleSubmit(token)
                mainHandler.postDelayed({
                    if (session == token && pendingTranscript != null) startCycle(token)
                }, 600L)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (active() && pendingTranscript != null &&
                    !partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().isNullOrBlank()) {
                    scheduleSubmit(token)
                }
            }
        })
        try {
            sr.startListening(recognizerIntent)
        } catch (_: SecurityException) {
            fail("permission_denied")
        } catch (_: IllegalStateException) {
            fail("on_device_service_error")
        } catch (_: UnsupportedOperationException) {
            fail("on_device_service_error")
        }
    }

    private fun scheduleSubmit(token: Int) {
        submitRunnable?.let(mainHandler::removeCallbacks)
        submitRunnable = Runnable {
            if (session != token) return@Runnable
            val transcript = pendingTranscript ?: return@Runnable
            val callback = savedOnResult
            cancelSession()
            callback?.invoke(transcript)
        }.also { mainHandler.postDelayed(it, 2_000L) }
    }

    private fun fail(code: String) {
        val callback = savedOnError
        cancelSession()
        Timber.w("AndroidSTT: stopped (%s)", code)
        callback?.invoke(code)
    }

    private fun clearSupportTimeout() {
        supportTimeout?.let(mainHandler::removeCallbacks)
        supportTimeout = null
    }

    private fun cancelSession() {
        session++
        savedOnResult = null
        savedOnError = null
        pendingTranscript = null
        submitRunnable?.let(mainHandler::removeCallbacks)
        submitRunnable = null
        clearSupportTimeout()
        recognizer?.cancel()
    }

    override fun stopListening() {
        mainHandler.post { cancelSession() }
    }

    override fun shutdown() {
        mainHandler.post {
            closed = true
            cancelSession()
            recognizer?.destroy()
            recognizer = null
        }
    }
}
