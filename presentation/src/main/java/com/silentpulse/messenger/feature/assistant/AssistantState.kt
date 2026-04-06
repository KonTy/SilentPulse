package com.silentpulse.messenger.feature.assistant

data class AssistantState(
    val driveModeEnabled: Boolean = false,
    val driveModeReadSms: Boolean = true,
    val driveModeReadAll: Boolean = false,
    val driveModeVoiceReplyEnabled: Boolean = false,
    val driveModeTimeoutSummary: String = "30 seconds",
    val sttEngine: String = "android",
    val whisperModelName: String = "No model selected",
    val whisperModelsDir: String = "",
    val ttsEngineSummary: String = "Android TTS (Offline)"
)
