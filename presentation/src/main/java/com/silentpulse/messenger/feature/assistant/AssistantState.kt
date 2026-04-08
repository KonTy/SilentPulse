package com.silentpulse.messenger.feature.assistant

data class AssistantState(
    val driveModeEnabled: Boolean = false,
    val driveModeReadSms: Boolean = true,
    val driveModeReadAll: Boolean = false,
    val driveModeVoiceReplyEnabled: Boolean = false,
    val driveModeWakeWordEnabled: Boolean = false,
    val driveModeTimeoutSummary: String = "30 seconds",
    val driveModeMaxRetriesSummary: String = "2 retries",
    val sttEngine: String = "whisper",
    val whisperModelName: String = "No model selected",
    val whisperModelsDir: String = "",
    val ttsEngineSummary: String = "Android TTS (Offline)"
)
