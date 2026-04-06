package com.silentpulse.messenger.feature.drivemode

interface TtsEngine {
    fun speak(text: String, onDone: () -> Unit = {})
    fun stop()
    fun shutdown()
    val isReady: Boolean
}
