package com.silentpulse.messenger.feature.assistant

import android.util.Log
import com.silentpulse.messenger.BuildConfig

/**
 * Debug-only voice pipeline logger.
 *
 * ## How it works
 * Every function is `inline` and the body is wrapped in `if (BuildConfig.DEBUG)`.
 * R8/ProGuard eliminates the entire call site — including string concatenation —
 * in release builds.  Zero overhead in production.
 *
 * ## Logcat filter for the full voice pipeline (paste into Android Studio logcat):
 * ```
 * tag:SP_WAKE | tag:SP_STT | tag:SP_ROUTE | tag:SP_XAPP | tag:SP_SESSION | tag:SP_LAUNCH | tag:MicrocoreVoice
 * ```
 *
 * Or to follow one call end-to-end from wake word to Microcore reply:
 * ```
 * adb logcat SP_WAKE:V SP_STT:V SP_ROUTE:V SP_XAPP:V SP_SESSION:V SP_LAUNCH:V MicrocoreVoice:V '*:S'
 * ```
 *
 * ## Tag legend
 * | Tag          | What it covers                                          |
 * |--------------|---------------------------------------------------------|
 * | SP_WAKE      | Vosk wake word: prime, cooldown, confidence, fire       |
 * | SP_STT       | SpeechRecognizer: ready, partials, all alternatives     |
 * | SP_ROUTE     | routeCommand() decision trace — which handler claimed it|
 * | SP_XAPP      | Cross-app: EXECUTE_COMMAND sent, TTS_REPLY received     |
 * | SP_SESSION   | SessionManager: open, touch, expire, close              |
 * | SP_LAUNCH    | App launch/close: fuzzy match scores, chosen package    |
 * | MicrocoreVoice | Microcore side (separate app, same pipeline)          |
 */
object VoiceDebugLog {

    const val WAKE    = "SP_WAKE"
    const val STT     = "SP_STT"
    const val ROUTE   = "SP_ROUTE"
    const val XAPP    = "SP_XAPP"
    const val SESSION = "SP_SESSION"
    const val LAUNCH  = "SP_LAUNCH"

    // ── Wake word ────────────────────────────────────────────────────────────

    @JvmStatic
    inline fun wake(msg: () -> String) {
        if (BuildConfig.DEBUG) Log.v(WAKE, msg())
    }

    @JvmStatic
    inline fun wakeW(msg: () -> String) {
        if (BuildConfig.DEBUG) Log.w(WAKE, msg())
    }

    // ── STT ──────────────────────────────────────────────────────────────────

    @JvmStatic
    inline fun stt(msg: () -> String) {
        if (BuildConfig.DEBUG) Log.v(STT, msg())
    }

    @JvmStatic
    inline fun sttE(msg: () -> String) {
        if (BuildConfig.DEBUG) Log.e(STT, msg())
    }

    // ── Routing ──────────────────────────────────────────────────────────────

    @JvmStatic
    inline fun route(msg: () -> String) {
        if (BuildConfig.DEBUG) Log.v(ROUTE, msg())
    }

    // ── Cross-app ────────────────────────────────────────────────────────────

    @JvmStatic
    inline fun xapp(msg: () -> String) {
        if (BuildConfig.DEBUG) Log.d(XAPP, msg())
    }

    @JvmStatic
    inline fun xappW(msg: () -> String) {
        if (BuildConfig.DEBUG) Log.w(XAPP, msg())
    }

    // ── Session ──────────────────────────────────────────────────────────────

    @JvmStatic
    inline fun session(msg: () -> String) {
        if (BuildConfig.DEBUG) Log.d(SESSION, msg())
    }

    // ── App launch ───────────────────────────────────────────────────────────

    @JvmStatic
    inline fun launch(msg: () -> String) {
        if (BuildConfig.DEBUG) Log.d(LAUNCH, msg())
    }

    // ── Convenience: dump a list of match candidates ─────────────────────────

    @JvmStatic
    inline fun launchCandidates(tag: String, candidates: List<Triple<String, String, Int>>) {
        if (BuildConfig.DEBUG) {
            if (candidates.isEmpty()) {
                Log.d(LAUNCH, "[$tag] no candidates matched")
            } else {
                candidates.take(5).forEachIndexed { i, (label, pkg, dist) ->
                    Log.d(LAUNCH, "[$tag] candidate[$i] label=\"$label\" pkg=$pkg dist=$dist")
                }
            }
        }
    }
}
