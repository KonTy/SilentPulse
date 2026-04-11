package com.silentpulse.messenger.feature.drivemode

import android.content.Context
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import java.util.Locale

/**
 * Shared TTS controller for drive-mode features.
 *
 * Centralises two patterns that used to be duplicated across
 * [SilentPulseNotificationListener] and [VoiceAssistantService]:
 *
 *  1. **`speak(text, onDone)`** — auto-detects the Unicode script of [text]
 *     and sets the TTS locale before each utterance, then restores the
 *     default locale in the onDone callback.
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
class VoiceInteractor(context: Context, onReady: (() -> Unit)? = null) {

    private val mainHandler = Handler(Looper.getMainLooper())

    internal val ttsEngine: AndroidTtsEngine = AndroidTtsEngine(context) { ready ->
        if (ready) mainHandler.post { onReady?.invoke() }
    }

    /** True once the underlying [AndroidTtsEngine] has finished initialising. */
    val isReady: Boolean get() = ttsEngine.isReady

    // ── TTS ───────────────────────────────────────────────────────────────────

    /**
     * Speak [text] and fire [onDone] when the utterance completes.
     *
     * Language auto-detection: if [text] contains ≥ 30% non-Latin letters the
     * TTS locale is switched for this utterance and restored to [Locale.getDefault]
     * in [onDone].
     *
     * **Important:** if [interrupt] is called before [onDone] fires, [onDone]
     * is silently discarded (the callbacks map is cleared by `stop()`).
     * Pass recovery logic to [interrupt] instead of relying on [onDone].
     */
    fun speak(text: String, onDone: () -> Unit = {}) {
        val detected = detectLocaleByScript(text)
        if (detected != null) {
            ttsEngine.setLocale(detected)
            Timber.d("VoiceInteractor TTS [${detected.language}]: ${text.take(80)}")
        } else {
            ttsEngine.setLocale(Locale.getDefault())
            Timber.d("VoiceInteractor TTS: ${text.take(80)}")
        }
        ttsEngine.speak(text) {
            if (detected != null) ttsEngine.setLocale(Locale.getDefault())
            onDone()
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
        ttsEngine.stop()                         // clears completionCallbacks — intentional
        mainHandler.post { onResume?.invoke() }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun destroy() {
        ttsEngine.shutdown()
    }

    // ── Language detection ────────────────────────────────────────────────────

    /**
     * Detect the dominant Unicode script in [text].
     *
     * Returns a [Locale] for the dominant non-Latin script when ≥ 30% of
     * letter characters belong to that script, or **null** for Latin/ASCII text
     * (TTS stays on whatever locale the engine currently has).
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
