package com.silentpulse.messenger.feature.drivemode

import java.util.Locale

interface TtsEngine {
    /** Exactly one terminal callback; errors must not execute success-dependent actions. */
    fun speak(text: String, onError: (String) -> Unit = {}, onDone: () -> Unit = {})
    fun setLocale(locale: Locale): Boolean = true
    fun stop()
    fun shutdown()
    val isReady: Boolean
    val failureReason: String?
}
