package com.silentpulse.messenger.feature.drivemode

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** The single Android TTS submission boundary, including widget and private-message speech. */
object OfflineTts {
    internal fun isInstalledOffline(voice: Voice): Boolean {
        // The SDK documents null features as an error, not an empty feature set.
        val features = voice.features ?: return false
        return !voice.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features
    }

    internal fun matchesLocale(voice: Voice, locale: Locale): Boolean =
        voice.locale.language == locale.language &&
            (locale.script.isEmpty() || voice.locale.script == locale.script)

    internal fun chooseVoice(voices: Set<Voice>, locale: Locale): Voice? =
        voices.filter { isInstalledOffline(it) && matchesLocale(it, locale) }
            .sortedWith(compareByDescending<Voice> { it.locale == locale }
                .thenByDescending { it.locale.country == locale.country }
                .thenBy { it.name })
            .firstOrNull()

    /** Never use setLanguage: it can implicitly select a network or not-yet-installed voice. */
    fun prepare(engine: TextToSpeech, locale: Locale): String? {
        return try {
            val selected = chooseVoice(engine.voices.orEmpty(), locale)
                ?: return "tts_offline_voice_unavailable"
            if (engine.setVoice(selected) != TextToSpeech.SUCCESS) {
                return "tts_voice_selection_failed"
            }
            val actual = engine.voice
            if (actual == null || actual.name != selected.name ||
                !isInstalledOffline(actual) || !matchesLocale(actual, locale)) {
                "tts_voice_selection_failed"
            } else null
        } catch (_: IllegalStateException) {
            "tts_voice_selection_failed"
        } catch (_: SecurityException) {
            "tts_voice_selection_failed"
        }
    }

    /** Revalidate immediately before every submission, not only at engine init. */
    fun speak(
        engine: TextToSpeech,
        locale: Locale,
        text: String,
        queueMode: Int,
        params: Bundle?,
        utteranceId: String
    ): String? {
        prepare(engine, locale)?.let { return it }
        return try {
            if (engine.speak(text, queueMode, params, utteranceId) == TextToSpeech.SUCCESS) null
            else "tts_synthesis_failed"
        } catch (_: IllegalStateException) {
            "tts_synthesis_failed"
        } catch (_: SecurityException) {
            "tts_synthesis_failed"
        }
    }
}
