package com.silentpulse.messenger.feature.drivemode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.Closeable
import java.util.concurrent.Executors

/**
 * Kotlin wrapper around the whisper.cpp JNI bridge.
 *
 * Thread safety: whisper inference is single-threaded by design. All
 * [transcribe] calls are serialised on a dedicated single-thread dispatcher.
 *
 * @param modelPath  Absolute path to a GGML Whisper model file (.bin).
 *                   Models available at https://huggingface.co/ggerganov/whisper.cpp
 *                   Recommended: ggml-base.bin (141 MB)  → fast, multilingual
 *                                ggml-small.bin (466 MB) → better accuracy
 *                                ggml-medium.bin (1.5 GB)→ best accuracy
 */
class WhisperContext(private val modelPath: String) : Closeable {

    private val nativePtr: Long = WhisperLib.initContext(modelPath).also {
        require(it != 0L) {
            "Failed to load Whisper model from: $modelPath\n" +
            "Make sure the file exists and is a valid GGML model. " +
            "Download models from https://huggingface.co/ggerganov/whisper.cpp"
        }
    }

    // Single-threaded dispatcher — whisper.cpp is not thread-safe
    private val inferenceDispatcher =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /**
     * Transcribe raw 16 kHz mono PCM samples.
     *
     * @param samples    Float array of PCM data in range [-1.0, 1.0]
     * @param language   BCP-47 language code (e.g. "en", "fr") or null for
     *                   automatic language detection.
     * @param translate  If true, result is always in English regardless of source.
     * @return           Transcribed text, trimmed. Empty string if no speech.
     */
    suspend fun transcribe(
        samples: FloatArray,
        language: String? = null,
        translate: Boolean = false
    ): String = withContext(inferenceDispatcher) {
        Timber.d("Whisper: transcribing ${samples.size} samples, lang=$language")
        WhisperLib.fullTranscribe(nativePtr, samples, language, translate)
        val count = WhisperLib.getTextSegmentCount(nativePtr)
        buildString {
            for (i in 0 until count) {
                val seg = WhisperLib.getTextSegment(nativePtr, i)
                if (seg.isNotBlank()) append(seg.trim()).append(' ')
            }
        }.trim()
    }

    /** Returns the language detected in the last [transcribe] call (e.g. "en"). */
    val detectedLanguage: String
        get() = WhisperLib.getDetectedLanguage(nativePtr)

    /** Hardware/SIMD capabilities reported by ggml. */
    val systemInfo: String
        get() = WhisperLib.getSystemInfo()

    override fun close() {
        WhisperLib.freeContext(nativePtr)
        inferenceDispatcher.close()
    }

    // ── JNI bindings ──────────────────────────────────────────────────────────

    private object WhisperLib {
        init {
            System.loadLibrary("whisper_silentpulse")
        }

        @JvmStatic external fun initContext(modelPath: String): Long
        @JvmStatic external fun freeContext(ctxPtr: Long)
        @JvmStatic external fun fullTranscribe(
            ctxPtr: Long,
            audioData: FloatArray,
            language: String?,
            translate: Boolean
        )
        @JvmStatic external fun getTextSegmentCount(ctxPtr: Long): Int
        @JvmStatic external fun getTextSegment(ctxPtr: Long, index: Int): String
        @JvmStatic external fun getDetectedLanguage(ctxPtr: Long): String
        @JvmStatic external fun getSystemInfo(): String
    }

    companion object {
        /** Convert 16-bit PCM short samples to float in [-1, 1]. */
        fun shortsToFloats(shorts: ShortArray): FloatArray =
            FloatArray(shorts.size) { i -> shorts[i] / 32768.0f }
    }
}
