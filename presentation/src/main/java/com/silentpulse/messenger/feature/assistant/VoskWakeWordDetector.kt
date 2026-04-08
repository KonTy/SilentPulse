package com.silentpulse.messenger.feature.assistant

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

/**
 * Vosk-based keyword spotter for the "Computer" wake word.
 *
 * Uses Vosk's grammar-constrained recognizer with `["computer", "[unk]"]`
 * so the decoder has a tiny search space — very low CPU, no beeps, fully offline.
 *
 * Flow:
 *   [init] → loads model from bundled assets (one-time)
 *   [start] → begins listening for "computer"
 *   [pause] → stops Vosk SpeechService, releases mic for SpeechRecognizer
 *   [resume] → creates fresh SpeechService, resumes keyword spotting
 *   [destroy] → releases all resources
 *
 * All callbacks fire on the main thread (Vosk SpeechService posts via Handler).
 */
class VoskWakeWordDetector(private val context: Context) {

    companion object {
        private const val TAG = "VoskWakeWord"
        private const val SAMPLE_RATE = 16000.0f
        /** Grammar for wake-word phase. */
        private const val GRAMMAR = "[\"computer\", \"[unk]\"]"
        /** Grammar for stop-word phase (active while TTS is speaking). */
        private const val STOP_GRAMMAR = "[\"stop\", \"computer stop\", \"[unk]\"]"
        /** Minimum ms between two wake-word fires — prevents double/rapid triggers. */
        private const val COOLDOWN_MS = 3_000L
        /**
         * Minimum per-word Vosk confidence for a COLD (unprimed) final hit.
         * Primed hits (partial "computer" already seen) fire unconditionally —
         * the partial itself is the acoustic confirmation.
         * 0.0 means Vosk didn't report conf — treat as pass.
         */
        private const val MIN_CONFIDENCE_COLD = 0.80
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var stopService: SpeechService? = null
    private var onWakeWord: (() -> Unit)? = null
    private var onReady: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    @Volatile private var isPaused = false
    @Volatile private var lastTriggerMs = 0L
    /** True when a partial "computer" was seen — final result must confirm before we fire. */
    @Volatile private var wakeWordPrimed = false
    /** Timestamp of last partial prime — priming expires after 2 s of silence. */
    @Volatile private var primedAtMs = 0L

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Unpack the Vosk model from assets (async I/O).  Call once from onCreate.
     * [onModelReady] fires when the model is loaded and ready to start.
     * [onModelError] fires if unpacking or loading fails.
     */
    fun init(onModelReady: () -> Unit, onModelError: (String) -> Unit) {
        Log.d(TAG, "Unpacking Vosk model from assets…")
        StorageService.unpack(context, "model-en-us", "model",
            { model ->
                Log.d(TAG, "Vosk model loaded successfully")
                this.model = model
                onModelReady()
            },
            { exception ->
                val msg = "Failed to load Vosk model: ${exception.message}"
                Log.e(TAG, msg, exception)
                onModelError(msg)
            }
        )
    }

    /** @return true if the model has been loaded. */
    val isModelLoaded: Boolean get() = model != null

    // ── Start / pause / resume / destroy ──────────────────────────────────────

    /**
     * Start listening for the wake word.
     *
     * @param onWakeWordDetected fires when "computer" is heard.
     * @param onListening fires once the mic is active and we're ready.
     * @param onErr fires on unrecoverable errors.
     */
    fun start(
        onWakeWordDetected: () -> Unit,
        onListening: (() -> Unit)? = null,
        onErr: ((String) -> Unit)? = null
    ) {
        this.onWakeWord = onWakeWordDetected
        this.onReady = onListening
        this.onError = onErr
        this.isPaused = false
        startInternal()
    }

    /**
     * Pause wake word detection and **release the mic** so Android's
     * SpeechRecognizer can use it.
     */
    fun pause() {
        Log.d(TAG, "Pausing — releasing mic")
        isPaused = true
        stopInternalService()
    }

    /**
     * Resume wake word detection after SpeechRecognizer finishes.
     * Creates a fresh Vosk SpeechService (new AudioRecord).
     */
    fun resume() {
        Log.d(TAG, "Resuming wake word detection")
        isPaused = false
        startInternal()
    }

    /**
     * Start a lightweight Vosk listener using the stop grammar
     * `["stop", "computer stop", "[unk]"]`. Intended to run while TTS is
     * speaking so the user can interrupt long reads.
     *
     * @param onStop Called when "stop" or "computer stop" is heard.
     */
    fun startStopListening(onStop: () -> Unit) {
        val m = model ?: return
        stopStopListening() // ensure clean state
        try {
            val recognizer = Recognizer(m, SAMPLE_RATE, STOP_GRAMMAR)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            stopService = service
            service.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    // Ignored — only committed results are used to avoid false positives.
                }
                override fun onResult(hypothesis: String?) {
                    hypothesis ?: return
                    if (checkForStopWord(hypothesis)) {
                        stopStopListening()
                        onStop()
                    }
                }
                override fun onFinalResult(hypothesis: String?) {
                    hypothesis ?: return
                    if (checkForStopWord(hypothesis)) {
                        stopStopListening()
                        onStop()
                    }
                }
                override fun onError(e: Exception?) {
                    Log.w(TAG, "Stop-listener error: ${e?.message}")
                }
                override fun onTimeout() {
                    // Restart stop-listener — TTS may still be playing
                    val currentStop = stopService
                    if (currentStop != null) {
                        stopStopListening()
                        startStopListening(onStop)
                    }
                }
            })
            Log.d(TAG, "Stop-listener active (grammar: stop / computer stop)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start stop-listener", e)
        }
    }

    /** Stop the stop-word listener without affecting wake-word detection. */
    fun stopStopListening() {
        try {
            stopService?.stop()
            stopService?.shutdown()
        } catch (_: Exception) {}
        stopService = null
    }

    /** Permanently release all resources. */
    fun destroy() {
        Log.d(TAG, "Destroying VoskWakeWordDetector")
        isPaused = true
        stopStopListening()
        stopInternalService()
        try { model?.close() } catch (_: Exception) {}
        model = null
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun stopInternalService() {
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (_: Exception) {}
        speechService = null
    }

    private fun startInternal() {
        val m = model
        if (m == null) {
            val msg = "Vosk model not loaded — call init() first"
            Log.e(TAG, msg)
            onError?.invoke(msg)
            return
        }

        try {
            val recognizer = Recognizer(m, SAMPLE_RATE, GRAMMAR)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            speechService = service

            service.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    if (isPaused || hypothesis == null) return
                    // Partials only arm (prime) the detector — they don't fire it.
                    // The committed final/result must confirm before we trigger.
                    checkForWakeWord(hypothesis, partial = true)
                }

                override fun onResult(hypothesis: String?) {
                    if (isPaused || hypothesis == null) return
                    checkForWakeWord(hypothesis, partial = false)
                }

                override fun onFinalResult(hypothesis: String?) {
                    if (isPaused || hypothesis == null) return
                    checkForWakeWord(hypothesis, partial = false)
                }

                override fun onError(e: Exception?) {
                    val msg = "Vosk error: ${e?.message}"
                    Log.e(TAG, msg, e)
                    onError?.invoke(msg)
                }

                override fun onTimeout() {
                    Log.d(TAG, "Vosk timeout — restarting")
                    stopInternalService()
                    if (!isPaused) startInternal()
                }
            })

            Log.d(TAG, "Vosk listening for wake word \"computer\"")
            onReady?.invoke()
        } catch (e: IOException) {
            val msg = "Failed to start Vosk SpeechService: ${e.message}"
            Log.e(TAG, msg, e)
            onError?.invoke(msg)
        }
    }

    /** Returns true if the final Vosk result contains a stop word. */
    private fun checkForStopWord(hypothesis: String): Boolean {
        return try {
            val json = JSONObject(hypothesis)
            val text = json.optString("text", "").trim()
            text.equals("stop", ignoreCase = true) ||
                text.equals("computer stop", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    /**
     * Parse the JSON hypothesis from the recognizer and check for "computer".
     * Grammar constrains output to ONLY "computer" or "[unk]", so exact match
     * is safe.  Two-phase confirmation reduces false positives:
     *   1. Partial "computer" → arms the detector (primes it).
     *   2. Final/result "computer" with conf ≥ MIN_CONFIDENCE → fires.
     * If the final comes back as "[unk]", or the final "computer" is low-confidence,
     * the prime is cleared and we wait for the next partial.
     * Cooldown prevents rapid re-triggering.
     */
    private fun checkForWakeWord(hypothesis: String, partial: Boolean) {
        try {
            val json = JSONObject(hypothesis)
            val now  = System.currentTimeMillis()

            if (partial) {
                // Partial: "computer" arms the detector — no actual trigger yet.
                val text = json.optString("partial", "").trim()
                if (text.equals("computer", ignoreCase = true)) {
                    if (!wakeWordPrimed) {
                        Log.d(TAG, "Wake word primed by partial")
                    }
                    wakeWordPrimed = true
                    primedAtMs    = now
                } else {
                    // Any other partial clears the prime.
                    wakeWordPrimed = false
                }
                return
            }

            // ── Final / result ────────────────────────────────────────────────
            val text = json.optString("text", "").trim()

            // Expire stale primes (e.g., Vosk gave a late final after a long pause)
            if (wakeWordPrimed && now - primedAtMs > 3_000L) {
                Log.d(TAG, "Stale wake-word prime discarded (${now - primedAtMs}ms)")
                wakeWordPrimed = false
            }

            if (!text.equals("computer", ignoreCase = true)) {
                // "[unk]" or empty → clear prime
                wakeWordPrimed = false
                return
            }

            // Extract per-word confidence from Vosk final result JSON:
            // {"result":[{"conf":0.87,"end":1.2,"start":0.5,"word":"computer"}],"text":"computer"}
            val conf = try {
                json.optJSONArray("result")?.getJSONObject(0)?.optDouble("conf", 0.0) ?: 0.0
            } catch (_: Exception) { 0.0 }

            // If the wake word was primed by a partial, trust it — fire unconditionally
            // (the partial already gave us one acoustic match; requiring high conf on the
            // final was causing false negatives because Vosk sometimes finalizes with low
            // confidence for very short words in a 2-word grammar).
            // For cold (unprimed) hits, require a stronger acoustic match.
            if (!wakeWordPrimed) {
                val threshold = MIN_CONFIDENCE_COLD
                if (conf > 0.0 && conf < threshold) {
                    Log.d(TAG, "Cold wake word suppressed: conf=${"%.2f".format(conf)} < $threshold")
                    return
                }
            }

            // Cooldown guard
            if (now - lastTriggerMs < COOLDOWN_MS) {
                Log.d(TAG, "Wake word suppressed by cooldown (${now - lastTriggerMs}ms since last)")
                wakeWordPrimed = false
                return
            }

            val wasPrimed = wakeWordPrimed
            lastTriggerMs  = now
            wakeWordPrimed = false
            Log.d(TAG, ">>> WAKE WORD fired (final, conf=${"%.2f".format(conf)}, wasPrimed=$wasPrimed)")
            isPaused = true
            stopInternalService()
            onWakeWord?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Vosk JSON: $hypothesis", e)
        }
    }
}
