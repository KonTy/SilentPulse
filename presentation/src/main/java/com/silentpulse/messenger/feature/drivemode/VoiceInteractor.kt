package com.silentpulse.messenger.feature.drivemode

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.silentpulse.messenger.injection.appComponent
import com.silentpulse.messenger.feature.drivemode.SpeechDiagnostics as Timber
import java.util.Locale

/**
 * Shared TTS controller for drive-mode features.
 *
 * Centralises two patterns that used to be duplicated across
 * [SilentPulseNotificationListener] and [VoiceAssistantService]:
 *
 *  1. **`speak(text, onDone)`** — auto-detects the Unicode script of [text]
 *     and validates the TTS locale before each utterance.
 *
 *  2. **`interrupt(onResume)`** — the ONE canonical "stop TTS and resume":
 *     `AndroidTtsEngine.stop()` drops all pending `onDone` callbacks;
 *     [interrupt] guarantees [onResume] is always called on the main thread
 *     even when the utterance was cut short by the user.
 *
 *  3. **`detectLocaleByScript`** — Unicode-range language detector, previously
 *     copy-pasted verbatim in both services.
 *
 * ## Why this stops the stop-button bug
 * Before: `stopSpeakingReceiver` called `tts.stop()` and forgot `resumeWakeWord()`.
 * After:  all callers call `voiceInteractor.interrupt { resumeStuff() }` — the
 * resume is structurally coupled to the stop in one method.
 *
 * @param onReady  called on the main thread once TTS init succeeds.
 *                 Wire `maybeStartListening()` here in [VoiceAssistantService].
 */
class VoiceInteractor internal constructor(
    private val context: Context,
    private val onFailure: (String) -> Unit,
    onReady: (() -> Unit)?,
    createPlatformEngine: ((TextToSpeech.OnInitListener) -> TextToSpeech)?
) {
    constructor(
        context: Context,
        onFailure: (String) -> Unit = {},
        onReady: (() -> Unit)? = null
    ) : this(context, onFailure, onReady, null)

    private val mainHandler = Handler(Looper.getMainLooper())

    internal val ttsEngine: TtsEngine = appComponent.ttsEngineFactory().createEngine(
        onInitialized = { ready ->
            mainHandler.post {
                if (ready) onReady?.invoke()
                else reportFailure(ttsEngine.failureReason ?: "tts_init_failed")
            }
        },
        createPlatformEngine = createPlatformEngine
    )

    /** True once the selected offline engine is ready. */
    val isReady: Boolean get() = ttsEngine.isReady
    val failureReason: String? get() = ttsEngine.failureReason
    private var generation = 0
    private var closed = false
    private data class Utterance(val text: String, val onDone: () -> Unit)
    private val pending = java.util.ArrayDeque<Utterance>()
    private var speaking = false

    private fun reportFailure(code: String) {
        generation++
        pending.clear()
        speaking = false
        ttsEngine.stop()
        SpeechFailure.show(context, code)
        onFailure(code)
    }

    // ── TTS ───────────────────────────────────────────────────────────────────

    /**
     * Speak [text] and fire [onDone] when the utterance completes.
     *
     * Language auto-detection: if [text] contains ≥ 30% non-Latin letters the
     * TTS locale is selected for this utterance; Latin text uses [Locale.getDefault].
     * A selection or synthesis failure aborts the queue through [onFailure], not [onDone].
     *
     * **Important:** if [interrupt] is called before [onDone] fires, [onDone]
     * is silently discarded (the callbacks map is cleared by `stop()`).
     * Pass recovery logic to [interrupt] instead of relying on [onDone].
     */
    fun speak(text: String, onDone: () -> Unit = {}) {
        val token = generation
        mainHandler.post {
            if (closed || token != generation) return@post
            pending.addLast(Utterance(text, onDone))
            speakNext()
        }
    }

    private fun speakNext() {
        if (closed || speaking || pending.isEmpty()) return
        val utterance = pending.removeFirst()
        val token = generation
        if (!ttsEngine.setLocale(detectLocaleByScript(utterance.text) ?: Locale.getDefault())) {
            reportFailure(ttsEngine.failureReason ?: "tts_offline_voice_unavailable")
            return
        }
        speaking = true
        Timber.d("VoiceInteractor: submitting offline speech")
        ttsEngine.speak(utterance.text, onError = { code ->
            mainHandler.post { if (token == generation) reportFailure(code) }
        }) {
            mainHandler.post {
                if (token != generation) return@post
                speaking = false
                utterance.onDone()
                speakNext()
            }
        }
    }

    // ── Stop / interrupt ──────────────────────────────────────────────────────

    /**
     * Hard stop: immediately halt TTS (all pending `onDone` callbacks are
     * cleared by [AndroidTtsEngine.stop]), then call [onResume] on the main thread.
     *
     * **This is the single place where "stop + resume" lives.**
     * Every caller (widget stop button, external `stopReading()`) delegates here,
     * so fixing this method fixes the behaviour everywhere.
     */
    fun interrupt(onResume: (() -> Unit)? = null) {
        generation++
        pending.clear()
        speaking = false
        ttsEngine.stop()                         // clears completionCallbacks — intentional
        mainHandler.post { onResume?.invoke() }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun destroy() {
        generation++
        closed = true
        pending.clear()
        ttsEngine.shutdown()
    }

    // ── Language detection ────────────────────────────────────────────────────

    /**
     * Detect the dominant Unicode script in [text].
     *
     * Returns a [Locale] for the dominant non-Latin script when ≥ 30% of
     * letter characters belong to that script, or **null** for Latin/ASCII text
     * (the configured default locale is used).
     *
     * Zero external dependencies — fully offline.  Previously copy-pasted
     * verbatim in both [SilentPulseNotificationListener] and [VoiceAssistantService].
     */
    fun detectLocaleByScript(text: String): Locale? {
        var total = 0
        var cyrillic = 0; var arabic = 0; var cjk = 0; var kana = 0
        var hangul = 0; var devanagari = 0; var thai = 0; var hebrew = 0; var greek = 0
        for (c in text) {
            if (!c.isLetter()) continue
            total++
            when {
                c in '\u0400'..'\u04FF' || c in '\u0500'..'\u052F' -> cyrillic++
                c in '\u0600'..'\u06FF' || c in '\u0750'..'\u077F' ||
                    c in '\uFB50'..'\uFDFF' || c in '\uFE70'..'\uFEFF' -> arabic++
                c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' ||
                    c in '\uF900'..'\uFAFF' -> cjk++
                c in '\u3040'..'\u309F' || c in '\u30A0'..'\u30FF' -> kana++
                c in '\uAC00'..'\uD7AF' || c in '\u1100'..'\u11FF' -> hangul++
                c in '\u0900'..'\u097F' -> devanagari++
                c in '\u0E00'..'\u0E7F' -> thai++
                c in '\u0590'..'\u05FF' || c in '\uFB1D'..'\uFB4F' -> hebrew++
                c in '\u0370'..'\u03FF' || c in '\u1F00'..'\u1FFF' -> greek++
            }
        }
        if (total == 0) return null
        val threshold = (total * 0.30).toInt()
        return when {
            cyrillic   > threshold -> Locale("ru")
            arabic     > threshold -> Locale("ar")
            kana       > threshold || (cjk > threshold && kana > 0) -> Locale("ja")
            cjk        > threshold -> Locale("zh")
            hangul     > threshold -> Locale("ko")
            devanagari > threshold -> Locale("hi")
            thai       > threshold -> Locale("th")
            hebrew     > threshold -> Locale("he")
            greek      > threshold -> Locale("el")
            else                   -> null
        }
    }
}
