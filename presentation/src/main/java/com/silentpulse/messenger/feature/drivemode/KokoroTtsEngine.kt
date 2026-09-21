package com.silentpulse.messenger.feature.drivemode

/**
 * Preserves the selected engine without silently falling back to another provider.
 *
 * Bundled Sherpa-ONNX 1.12.35 logs the full input when token conversion fails,
 * even with model.debug=false (OfflineTtsKokoroImpl::Generate). Its Android macro
 * writes both stderr and logcat and exposes no runtime suppression switch.
 * Do not pass private text to it until a privacy-safe native build is available.
 */
class KokoroTtsEngine : TtsEngine {
    override val isReady = false
    override val failureReason = "kokoro_privacy_unavailable"

    override fun speak(text: String, onError: (String) -> Unit, onDone: () -> Unit) {
        onError(failureReason)
    }

    override fun stop() {}
    override fun shutdown() {}
}
