package com.silentpulse.messenger.feature.drivemode

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Fixed messages only: never display or log provider exceptions containing private payloads. */
object SpeechFailure {
    fun message(code: String): String = when (code) {
        "on_device_requires_android_12" ->
            "Android offline recognition requires Android 12 or later. Select an installed Vosk or Whisper model in Drive Mode settings."
        "on_device_unavailable", "on_device_init_failed" ->
            "On-device recognition is unavailable. Enable an on-device speech service in Android settings, or select Vosk or Whisper."
        "on_device_language_unavailable", "on_device_language_unsupported" ->
            "The offline recognition language is not installed or supported. Install English (United States) in Android speech settings, or select Vosk or Whisper."
        "on_device_support_unavailable" ->
            "Android could not verify the installed offline recognition language. Select Vosk or Whisper, or check the on-device service in Android settings."
        "on_device_service_error" ->
            "On-device recognition failed. Check Android speech settings or select an installed Vosk or Whisper model."
        "vosk_no_model_path", "vosk_model_not_found", "vosk_model_load_failed" ->
            "Vosk is unavailable. Configure an installed Vosk model in Drive Mode settings."
        "whisper_no_model_path", "whisper_model_not_found", "whisper_model_load_failed" ->
            "Whisper is unavailable. Configure an installed Whisper model in Drive Mode settings."
        "stt_unknown_engine" ->
            "Select an offline speech recognition engine in Drive Mode settings."
        "tts_offline_voice_unavailable", "tts_voice_selection_failed" ->
            "No installed offline voice is available for this language. Install an offline voice in Android Text-to-speech settings, then restart the assistant."
        "tts_not_ready", "tts_init_failed", "tts_synthesis_failed" ->
            "Offline speech output is unavailable. Check Android Text-to-speech settings and restart the assistant."
        "kokoro_unavailable", "kokoro_synthesis_failed" ->
            "Kokoro is unavailable. Check the installed model in Drive Mode settings."
        "kokoro_privacy_unavailable" ->
            "Kokoro is disabled because this bundled version can log spoken text. Select Android with an installed offline voice in Drive Mode settings."
        "permission_denied" -> "Grant SilentPulse microphone permission in Android settings."
        "no_match", "speech_timeout" -> "No speech was recognized. Please try again."
        else -> "Offline speech is unavailable. Check your speech engine and installed models in settings."
    }

    fun show(context: Context, code: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message(code), Toast.LENGTH_LONG).show()
        }
    }
}
