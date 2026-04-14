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
        /**
         * Minimum number of **consecutive** partial matches required before the
         * detector is truly armed.  A real spoken word produces multiple
         * consistent partials over its duration (~5-8 for a 600-800 ms word);
         * random keyboard clicks / ambient noise may fluke 2-3 consecutive.
         * Requiring ≥5 demands sustained, consistent recognition.
         */
        private const val MIN_CONSECUTIVE_PRIMES = 5
        /**
         * Minimum time span (ms) between the FIRST partial match and the arming
         * partial.  A real spoken "bubblegum" (~600ms) produces its first
         * partial at ~150ms and keeps matching until ~600ms.  Silence/noise
         * hallucinations tend to be short bursts.
         * Requiring the prime window to span ≥ 400ms filters those out.
         */
        private const val MIN_PRIME_DURATION_MS = 400L
        /**
         * Minimum Vosk confidence on the FINAL result for a primed fire.
         * Real speech produces conf > 0 (typically 0.3-0.99).
         * Silence/noise hallucinations produce conf = 0.0.
         * Setting this > 0 blocks all zero-confidence hallucination fires.
         */
        private const val MIN_CONF_PRIMED = 0.01
    }

    /**
     * Grammar with **distractor words** to reduce false positives.
     *
     * With only 2 tokens (wake word + [unk]) Vosk is a coin-flip —
     * any vaguely speech-like noise has ~50% chance of landing on the
     * wake word.  By adding many phonetically diverse distractors, noise
     * distributes across ~20+ words instead.  Only a real spoken
     * "bubblegum" consistently produces "bubblegum" partials.
     *
     * Distractors are chosen to cover a wide phoneme range and include
     * a few words starting with "b" to absorb labial noise.
     */
    private val grammar: String get() {
        val distractors = listOf(
            // labial / "b"-starts — absorb lip/pop/plosive noise
            "banana", "butterfly", "beautiful", "blanket",
            // other common multi-syllable words (diverse onsets)
            "chocolate", "strawberry", "watermelon", "pineapple",
            "umbrella", "elephant", "telephone", "adventure",
            "wonderful", "cucumber", "helicopter", "microphone",
            "dinosaur", "catastrophe", "aluminum", "trampoline"
        )
        val words = (listOf(wakeWord.lowercase()) + distractors)
            .joinToString(", ") { "\"$it\"" }
        return "[$words, \"[unk]\"]"
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var onWakeWord: (() -> Unit)? = null
    private var onReady: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    @Volatile private var isPaused = false
    @Volatile private var lastTriggerMs = 0L
    /** True when enough consecutive partials matched — final result must confirm before we fire. */
    @Volatile private var wakeWordPrimed = false
    /** Timestamp of last partial prime — priming expires after 3 s of silence. */
    @Volatile private var primedAtMs = 0L
    /** Timestamp of the FIRST consecutive partial match (start of prime window). */
    @Volatile private var primeStartMs = 0L
    /** Running count of consecutive partial matches for the current prime window. */
    @Volatile private var consecutivePrimes = 0

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

            Log.d(TAG, "Vosk listening for wake word \"$wakeWord\" (grammar has ${grammar.count { it == ',' } + 1} tokens)")
            onReady?.invoke()
        } catch (e: IOException) {
            val msg = "Failed to start Vosk SpeechService: ${e.message}"
            Log.e(TAG, msg, e)
            onError?.invoke(msg)
        }
    }

    /**
     * Parse the JSON hypothesis from the recognizer and check for the wake word.
     * Two-phase detection with consecutive-prime noise filter:
     *   1. Partial matching wake word → increments consecutive prime count.
     *      Detector becomes ARMED only after [MIN_CONSECUTIVE_PRIMES] consecutive
     *      matching partials — this filters out one-off noise flukes from road/engine hum.
     *   2a. Final matching wake word  → fires (high confidence path).
     *   2b. Final "[unk]" while armed → also fires.
     *       Some words (e.g. "bubblegum") only appear in partials; the
     *       grammar decoder finalises them as "[unk]".  Multiple consecutive
     *       partials are the acoustic confirmation, so we trust and fire on "[unk]".
     * Cooldown prevents rapid re-triggering.
     */
    private fun checkForWakeWord(hypothesis: String, partial: Boolean) {
        try {
            val json = JSONObject(hypothesis)
            val now  = System.currentTimeMillis()

            if (partial) {
                val text = json.optString("partial", "").trim()
                if (text.equals(wakeWord, ignoreCase = true)) {
                    if (consecutivePrimes == 0) primeStartMs = now
                    consecutivePrimes++
                    primedAtMs = now
                    val duration = now - primeStartMs
                    if (consecutivePrimes >= MIN_CONSECUTIVE_PRIMES
                        && duration >= MIN_PRIME_DURATION_MS
                        && !wakeWordPrimed) {
                        Log.d("SP_WAKE", "[PRIME] armed after $consecutivePrimes consecutive partials, ${duration}ms span")
                        wakeWordPrimed = true
                    } else if (!wakeWordPrimed) {
                        Log.d("SP_WAKE", "[PARTIAL] \"$text\" ($consecutivePrimes/${MIN_CONSECUTIVE_PRIMES} needed, ${duration}ms/${MIN_PRIME_DURATION_MS}ms)")
                    }
                } else {
                    if (consecutivePrimes > 0) {
                        Log.d("SP_WAKE", "[PARTIAL-RESET] noise partial=\"$text\" (had $consecutivePrimes primes)")
                    } else if (text.isNotEmpty() && !text.equals("[unk]", ignoreCase = true)) {
                        // Log distractor hits to verify noise distributes away from wake word
                        Log.d("SP_WAKE", "[DISTRACTOR] \"$text\"")
                    }
                    wakeWordPrimed = false
                    consecutivePrimes = 0
                }
                return
            }

            // ── Final / result ────────────────────────────────────────────────
            val text = json.optString("text", "").trim()

            // Expire stale primes
            if (wakeWordPrimed && now - primedAtMs > 3_000L) {
                Log.d("SP_WAKE", "[STALE-PRIME] discarded after ${now - primedAtMs}ms")
                wakeWordPrimed = false
                consecutivePrimes = 0
            }

            val isWakeWord = text.equals(wakeWord, ignoreCase = true)
            val isUnk      = text.equals("[unk]", ignoreCase = true) || text.isEmpty()

            // Fire if: word confirmed in final, OR primed and final is [unk]
            // (some words only appear in partials; [unk] final + prime = trusted hit)
            if (!isWakeWord && !(isUnk && wakeWordPrimed)) {
                if (text.isNotEmpty()) {
                    Log.d("SP_WAKE", "[FINAL-MISS] text=\"$text\" primed=$wakeWordPrimed")
                }
                wakeWordPrimed = false
                consecutivePrimes = 0
                return
            }

            // Extract per-word confidence from Vosk final result JSON:
            // {"result":[{"conf":0.87,"end":1.2,"start":0.5,"word":"computer"}],"text":"computer"}
            val conf = try {
                json.optJSONArray("result")?.getJSONObject(0)?.optDouble("conf", 0.0) ?: 0.0
            } catch (_: Exception) { 0.0 }

            // For primed hits where the final is [unk]: these are the riskiest
            // path for false positives in silence.  The partials matched but Vosk
            // couldn't commit the word in the final — possibly a hallucination.
            // Require the prime window to still be fresh (< 3s) and log generously.
            if (isUnk && wakeWordPrimed) {
                val primeDuration = primedAtMs - primeStartMs
                Log.d("SP_WAKE", "[UNK-PRIMED] primes=$consecutivePrimes primeDuration=${primeDuration}ms finalJson=$hypothesis")
            }

            // In a 2-word grammar (wake word + [unk]), Vosk regularly
            // hallucinates the wake word on silence/noise — both as partials
            // AND as final results with conf=0.0.  Requiring priming for ALL
            // fires means a real spoken word must produce ≥3 consecutive
            // matching partials spanning ≥200ms before we'll act on it.
            // This eliminates all silence false triggers.
            if (!wakeWordPrimed) {
                Log.d("SP_WAKE", "[COLD-REJECT] final=\"$text\" conf=${"%.2f".format(conf)} — not primed, ignoring")
                consecutivePrimes = 0
                return
            }

            // Note: Vosk grammar-mode always reports conf=0.00 on finals,
            // so confidence cannot be used as a filter.  The 5-prime + 400ms
            // priming requirement is the primary false-positive defense.

            // Cooldown guard
            if (now - lastTriggerMs < COOLDOWN_MS) {
                Log.d("SP_WAKE", "[COOLDOWN] suppressed ${now - lastTriggerMs}ms ago (min ${COOLDOWN_MS}ms)")
                wakeWordPrimed = false
                consecutivePrimes = 0
                return
            }

            val wasPrimed = wakeWordPrimed
            lastTriggerMs  = now
            wakeWordPrimed = false
            consecutivePrimes = 0
            Log.d("SP_WAKE", ">>>[FIRE] conf=${"%.2f".format(conf)} primed=$wasPrimed msSincePrime=${now-primedAtMs}")
            isPaused = true
            stopInternalService()
            onWakeWord?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Vosk JSON: $hypothesis", e)
        }
    }
}
