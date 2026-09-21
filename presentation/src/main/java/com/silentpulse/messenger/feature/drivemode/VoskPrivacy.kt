package com.silentpulse.messenger.feature.drivemode

import org.vosk.LibVosk

object VoskPrivacy {
    fun disableNativeLogging() {
        // Vosk's Android Kaldi handler drops severities above this level, including errors.
        // Native grammar/decoder diagnostics can otherwise include recognized words.
        LibVosk.vosk_set_log_level(-10)
    }
}
