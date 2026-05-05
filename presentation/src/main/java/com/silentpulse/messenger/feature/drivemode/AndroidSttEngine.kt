package com.silentpulse.messenger.feature.drivemode

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import timber.log.Timber
import com.silentpulse.messenger.BuildConfig

/**
 * STT engine wrapping Android's built-in [SpeechRecognizer] (Google Soda on-device).
 *
 * **Architecture — single recognizer, reused across cycles:**
 *   A SpeechRecognizer binds to the recognition service on creation and
 *   initialises a Soda pipeline.  Destroying + recreating it every 5 seconds
 *   (on each no_match timeout) causes race conditions (error 11) and sometimes
 *   makes Soda return no_match even when speech *was* detected.
 *
 *   This engine creates the recognizer **once** and reuses it:
 *     cancel() → setRecognitionListener() → startListening()
 *
 *   It is only destroyed in [shutdown].
 *
 * **Audio feedback:**
 *   The SpeechRecognizer beep is intentionally left audible — it signals to
 *   the user that the system is ready for their command.
 *
 * **Privacy:**
 *   INTERNET permission is removed in the manifest via tools:node="remove".
 *   The kernel blocks all outbound sockets.  Audio stays on-device.
 */
class AndroidSttEngine(private val context: Context) : SttEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var listening = false

    /** Tracks last start time to throttle rapid restarts. */
    private var lastStartTime = 0L

    /**
     * Set to true immediately before calling recognizer.cancel() for a
     * planned restart. Suppresses the resulting ERROR_CLIENT so it does not
     * destroy the recognizer or propagate to the caller.
     */
    private var expectingCancel = false

    /** Accumulated transcript across continuation windows. */
    private var pendingTranscript: String? = null
    /** Runnable that fires after silence to submit the accumulated result. */
    private var submitRunnable: Runnable? = null
    /** Callbacks saved so continuation re-starts can reuse them. */
    private var savedOnResult: ((String) -> Unit)? = null
    private var savedOnError: ((String) -> Unit)? = null

    private companion object {
        /** Minimum gap between consecutive startListening calls. */
        const val MIN_RESTART_GAP_MS = 600L
        /**
         * How long to wait after the last recognised speech before submitting.
         * Gives the user time to pause mid-thought and continue speaking.
         * 2 seconds = comfortable thinking pause.
         */
        const val CONTINUATION_WINDOW_MS = 2_000L
    }

    /** Lazily build the recognizer intent (constant across calls). */
    private val recognizerIntent by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // PRIVACY: Force on-device (offline) recognition.
            // SpeechRecognizer binds to com.google.android.as which is a
            // SEPARATE SYSTEM PROCESS with its own network privileges.
            // network_security_config.xml does NOT constrain it.
            // EXTRA_PREFER_OFFLINE is the only reliable guard against cloud STT.
            putExtra("android.speech.extra.PREFER_OFFLINE", true)

            // ── Keep the session alive much longer ──────────────────────────
            // By default Soda times out after ~5 seconds of silence, which
            // forces a restart (with a new beep) every 5 s.
            // These extras tell the recognizer to keep the mic open for up
            // to 60 s of total silence before giving up.  This means ONE beep
            // per minute instead of one every 5 seconds.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 30_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 15_000L)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    override fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        savedOnResult = onResult
        savedOnError  = onError
        pendingTranscript = null
        cancelPendingSubmit()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Timber.e("AndroidSTT: SpeechRecognizer not available")
            onError("speech_not_available")
            return
        }

        mainHandler.post {
            try {
                val now = System.currentTimeMillis()
                val elapsed = now - lastStartTime
                if (elapsed < MIN_RESTART_GAP_MS) {
                    val delay = MIN_RESTART_GAP_MS - elapsed
                    Timber.d("AndroidSTT: throttle — waiting ${delay}ms")
                    mainHandler.postDelayed({ doStart(onResult, onError) }, delay)
                    return@post
                }
                doStart(onResult, onError)
            } catch (e: Exception) {
                Timber.e(e, "AndroidSTT: startListening failed")
                listening = false
                onError("client_error")
            }
        }
    }

    override fun stopListening() {
        Timber.d("AndroidSTT: stopListening()")
        savedOnResult = null
        savedOnError  = null
        mainHandler.post {
            expectingCancel = false
            cancelPendingSubmit()
            pendingTranscript = null
            try { recognizer?.cancel() } catch (_: Exception) {}
            listening = false
        }
    }

    override fun shutdown() {
        Timber.d("AndroidSTT: shutdown()")
        mainHandler.post {
            listening = false
            cancelPendingSubmit()
            try { recognizer?.cancel() } catch (_: Exception) {}
            try { recognizer?.destroy() } catch (_: Exception) {}
            recognizer = null
        }
    }

    // ── Core ──────────────────────────────────────────────────────────────────

    private fun cancelPendingSubmit() {
        submitRunnable?.let { mainHandler.removeCallbacks(it) }
        submitRunnable = null
    }

    private fun doStart(onResult: (String) -> Unit, onError: (String) -> Unit) {
        lastStartTime = System.currentTimeMillis()

        // Mute STREAM_MUSIC for 600ms to suppress the SpeechRecognizer start beep.
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        mainHandler.postDelayed({ am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) }, 600)

        // Cancel any in-flight recognition (safe even if idle).
        try { recognizer?.cancel() } catch (_: Exception) {}

        // Create the recognizer once and keep it alive.
        if (recognizer == null) {
            Timber.d("AndroidSTT: creating SpeechRecognizer (one-time)")
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        }
        val sr = recognizer!!

        listening = true

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Timber.d("AndroidSTT: [SP_STT] MIC HOT - ready for speech")
            }
            override fun onBeginningOfSpeech() {
                Timber.d("AndroidSTT: speech started")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Timber.d("AndroidSTT: speech ended")
                listening = false
            }
            override fun onError(error: Int) {
                listening = false
                val code = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech_timeout"
                    SpeechRecognizer.ERROR_AUDIO -> "audio_error"
                    SpeechRecognizer.ERROR_CLIENT -> "client_error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission_denied"
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network_error"
                    SpeechRecognizer.ERROR_SERVER -> "server_error"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer_busy"
                    else -> "unknown_error_$error"
                }
                Timber.e("AndroidSTT: error $error → $code")

                // If the recognizer died, null it so the next cycle creates a fresh one.
                if (error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_AUDIO ||
                    code.startsWith("unknown_error")) {
                    Timber.w("AndroidSTT: destroying recognizer after fatal error $error")
                    try { recognizer?.destroy() } catch (_: Exception) {}
                    recognizer = null
                }

                // If we already have buffered speech, any error just means the
                // mic closed — let the 2s submit timer fire naturally.
                if (pendingTranscript != null) {
                    Timber.d("AndroidSTT: error $code with pending transcript — 2s timer will submit")
                    return
                }

                // ERROR_CLIENT from our own cancel() before a planned restart
                // — safe to ignore entirely.
                if (error == SpeechRecognizer.ERROR_CLIENT && expectingCancel) {
                    expectingCancel = false
                    Timber.d("AndroidSTT: expected ERROR_CLIENT from cancel — ignored")
                    return
                }
                expectingCancel = false

                // If the recognizer died, null it so the next cycle creates a fresh one.
                if (error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_AUDIO ||
                    code.startsWith("unknown_error")) {
                    Timber.w("AndroidSTT: destroying recognizer after fatal error $error")
                    try { recognizer?.destroy() } catch (_: Exception) {}
                    recognizer = null
                }

                // Use savedOnError — if stopListening() already nulled it this
                // is a cancel-induced callback and should be silently dropped.
                savedOnError?.invoke(code) ?: Timber.d("AndroidSTT: error $code suppressed (session stopped)")
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = matches?.firstOrNull()?.trim() ?: ""
                Timber.d("AndroidSTT: result=\"$best\" (${matches?.size ?: 0} alternatives)")
                if (BuildConfig.DEBUG) {
                    matches?.forEachIndexed { i, alt ->
                        Timber.d("AndroidSTT:   alt[$i]=\"$alt\"")
                    }
                }

                if (best.isNotBlank()) {
                    // Buffer the result and restart the mic so the user can
                    // keep talking. The 2s silence timer submits when no more
                    // speech arrives.
                    val accumulated = listOfNotNull(pendingTranscript, best).joinToString(" ")
                    pendingTranscript = accumulated
                    Timber.d("AndroidSTT: buffered=\"$accumulated\" — restarting mic for continuation")
                    scheduleSubmit()
                    mainHandler.postDelayed({
                        val onRes = savedOnResult
                        val onErr = savedOnError
                        if (onRes != null && pendingTranscript != null) {
                            expectingCancel = true
                            doStart(onRes, onErr ?: {})
                        }
                    }, 150)
                } else {
                    // Blank result — if we already have buffered speech, the 2s
                    // timer handles submission. Otherwise treat as no_match.
                    if (pendingTranscript != null) {
                        Timber.d("AndroidSTT: blank result with pending — 2s timer will submit")
                    } else {
                        savedOnError?.invoke("no_match") ?: Timber.d("AndroidSTT: no_match suppressed (session stopped)")
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    Timber.d("AndroidSTT: partial=\"$partial\"")
                    // Reset the continuation timer on every partial — user is still speaking.
                    if (pendingTranscript != null) scheduleSubmit()
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        Timber.d("AndroidSTT: starting listening")
        sr.startListening(recognizerIntent)
    }

    private fun scheduleSubmit() {
        cancelPendingSubmit()
        val r = Runnable { submitNow() }
        submitRunnable = r
        mainHandler.postDelayed(r, CONTINUATION_WINDOW_MS)
    }

    private fun submitNow() {
        cancelPendingSubmit()
        val transcript = pendingTranscript ?: return
        pendingTranscript = null
        val cb = savedOnResult
        savedOnResult = null
        savedOnError  = null
        Timber.d("AndroidSTT: submitting=\"$transcript\"")
        cb?.invoke(transcript)
    }
}
