package com.moez.QKSMS.feature.drivemode

interface TtsEngine {
    fun speak(text: String, onDone: () -> Unit = {})
    fun stop()
    fun shutdown()
    val isReady: Boolean
}
