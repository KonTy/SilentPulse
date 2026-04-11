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
 * Vosk-based keyword spotter for a configurable wake word (default: "computer").
 *
 * Uses Vosk's grammar-constrained recognizer so the decoder has a tiny search
 * space — very low CPU, no beeps, fully offline.
 *
 * @param wakeWord The word to listen for (lowercase). Read once at construction;
 *                 restart the detector to apply a changed word.
 */
class VoskWakeWordDetector(private val context: Context, private val wakeWord: String = "computer") {

    companion object {
        private const val TAG = "VoskWakeWord"
        private const val SAMPLE_RATE = 16000.0f
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

    /** Grammar string built from the configured wake word. */
    private val grammar get() = "[\"${wakeWord.lowercase()}\", \"[unk]\"]"

    private var model: Model? = null
    private var speechService: SpeechService? = null
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

    /** Permanently release all resources. */
    fun destroy() {
        Log.d(TAG, "Destroying VoskWakeWordDetector")
        isPaused = true
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
            val recognizer = Recognizer(m, SAMPLE_RATE, grammar)
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

            Log.d(TAG, "Vosk listening for wake word \"$wakeWord\"")
            onReady?.invoke()
        } catch (e: IOException) {
            val msg = "Failed to start Vosk SpeechService: ${e.message}"
            Log.e(TAG, msg, e)
            onError?.invoke(msg)
        }
    }

    /**
     * Parse the JSON hypothesis from the recognizer and check for "computer".
     * Two-phase detection:
     *   1. Partial matching wake word → arms the detector (primes it).
     *   2a. Final matching wake word  → fires (high confidence path).
     *   2b. Final "[unk]" while primed → also fires.
     *       Some words (e.g. "bubblegum") only appear in partials; the
     *       grammar decoder finalises them as "[unk]".  The partial IS the
     *       acoustic confirmation, so we trust the prime and fire on "[unk]".
     * Cooldown prevents rapid re-triggering.
     */
    private fun checkForWakeWord(hypothesis: String, partial: Boolean) {
        try {
            val json = JSONObject(hypothesis)
            val now  = System.currentTimeMillis()

            if (partial) {
                val text = json.optString("partial", "").trim()
                if (text.equals(wakeWord, ignoreCase = true)) {
                    if (!wakeWordPrimed) {
                        Log.d("SP_WAKE", "[PRIME] partial text=\"$text\"")
                    }
                    wakeWordPrimed = true
                    primedAtMs    = now
                } else {
                    wakeWordPrimed = false
                }
                return
            }

            // ── Final / result ────────────────────────────────────────────────
            val text = json.optString("text", "").trim()

            // Expire stale primes
            if (wakeWordPrimed && now - primedAtMs > 3_000L) {
                Log.d("SP_WAKE", "[STALE-PRIME] discarded after ${now - primedAtMs}ms")
                wakeWordPrimed = false
            }

            val isWakeWord = text.equals(wakeWord, ignoreCase = true)
            val isUnk      = text.equals("[unk]", ignoreCase = true) || text.isEmpty()

            // Fire if: word confirmed in final, OR primed and final is [unk]
            // (some words only appear in partials; [unk] final + prime = trusted hit)
            if (!isWakeWord && !(isUnk && wakeWordPrimed)) {
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
                    Log.d("SP_WAKE", "[COLD-SUPPRESS] conf=${"%.2f".format(conf)} < $threshold")
                    return
                }
            }

            // Cooldown guard
            if (now - lastTriggerMs < COOLDOWN_MS) {
                Log.d("SP_WAKE", "[COOLDOWN] suppressed ${now - lastTriggerMs}ms ago (min ${COOLDOWN_MS}ms)")
                wakeWordPrimed = false
                return
            }

            val wasPrimed = wakeWordPrimed
            lastTriggerMs  = now
            wakeWordPrimed = false
            Log.d("SP_WAKE", ">>>[FIRE] conf=${"%.2f".format(conf)} primed=$wasPrimed msSincePrime=${now-primedAtMs}")
            isPaused = true
            stopInternalService()
            onWakeWord?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Vosk JSON: $hypothesis", e)
        }
    }
}
