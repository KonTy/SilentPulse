package com.silentpulse.messenger.feature.assistant

data class AssistantState(
    val driveModeEnabled: Boolean = false,
    val driveModeReadSms: Boolean = true,
    val driveModeReadAll: Boolean = false,
    val driveModeVoiceReplyEnabled: Boolean = false,
    val driveModeWakeWordEnabled: Boolean = false,
    val driveModeTimeoutSummary: String = "30 seconds",
    val driveModeMaxRetriesSummary: String = "2 retries",
    // STT
    val sttEngine: String = "android",        // "android" or "vosk"
    val voskModelName: String = "No model",
    // TTS
    val ttsEngine: String = "android",        // "android" or "kokoro"
    val kokoroModelName: String = "No model"
)
