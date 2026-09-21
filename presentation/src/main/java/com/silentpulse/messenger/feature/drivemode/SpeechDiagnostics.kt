package com.silentpulse.messenger.feature.drivemode

import com.silentpulse.messenger.BuildConfig

/** Debug-only speech state diagnostics. Callers must supply metadata, never speech payloads. */
object SpeechDiagnostics {
    fun d(message: String, vararg args: Any?) {
        if (BuildConfig.DEBUG) timber.log.Timber.d(message, *args)
    }
    fun w(message: String, vararg args: Any?) {
        if (BuildConfig.DEBUG) timber.log.Timber.w(message, *args)
    }
    fun e(message: String, vararg args: Any?) {
        if (BuildConfig.DEBUG) timber.log.Timber.e(message, *args)
    }
}

object SpeechLog {
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) timber.log.Timber.tag(tag).d(message)
    }
    fun v(tag: String, message: String) {
        if (BuildConfig.DEBUG) timber.log.Timber.tag(tag).v(message)
    }
    fun w(tag: String, message: String) {
        if (BuildConfig.DEBUG) timber.log.Timber.tag(tag).w(message)
    }
    fun e(tag: String, message: String) {
        if (BuildConfig.DEBUG) timber.log.Timber.tag(tag).e(message)
    }
}
