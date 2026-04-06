package com.silentpulse.messenger.feature.drivemode

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import timber.log.Timber
import kotlin.math.sqrt

/**
 * Fully offline STT engine backed by whisper.cpp via custom JNI.
 *
 * Audio flow:
 *   AudioRecord (16 kHz mono int16)
 *   → VAD silence detection → stop capture
 *   → ShortArray → FloatArray  (÷ 32768)
 *   → WhisperContext.transcribe(floats, language)
 *   → onResult callback on main thread
 *
 * No files written to disk. No network calls. Ever.
 *
 * Model setup:
 *   1. Download a GGML model from https://huggingface.co/ggerganov/whisper.cpp
 *      e.g. ggml-base.bin (~141 MB) or ggml-small.bin (~466 MB)
 *   2. Copy to device storage (SD card or /sdcard/Download/)
 *   3. Set the path in Settings → Voice & Drive Mode → Whisper Model Path
 *
 * @param modelPath  Absolute path to the GGML .bin model file.
 * @param language   BCP-47 language code or null for auto-detection.
 */
class WhisperSttEngine(
    private val context: Context,
    private val modelPath: String,
    private val language: String? = null
) : SttEngine {

    companion object {
        private const val SAMPLE_RATE        = 16_000          // Hz — Whisper requirement
        private const val CHUNK_SAMPLES      = 1_600           // 100 ms per chunk
        private const val MIN_SPEECH_SAMPLES = 8_000           // 500 ms minimum recording
        private const val SILENCE_CHUNKS     = 15              // 1.5 s of silence → stop
        private const val SILENCE_RMS_THRESH = 0.01f           // normalised RMS threshold
        private const val MAX_RECORD_SECONDS = 30              // hard cap
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordingJob: Job? = null

    // Model is loaded once and reused across calls (loading can take 1-3 s for large models)
    @Volatile private var whisperCtx: WhisperContext? = null
    private val modelLock = Any()

    init {
        // Pre-load model on background thread immediately so it's ready when the user speaks
        scope.launch {
            loadModelIfNeeded()
        }
    }

    override fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!hasRecordPermission()) {
            onError("permission_denied")
            return
        }
        if (modelPath.isBlank()) {
            onError("whisper_no_model_path")
            return
        }

        recordingJob = scope.launch {
            recordAndTranscribe(onResult, onError)
        }
    }

    override fun stopListening() {
        recordingJob?.cancel()
        recordingJob = null
    }

    override fun shutdown() {
        stopListening()
        scope.cancel()
        synchronized(modelLock) {
            whisperCtx?.close()
            whisperCtx = null
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private suspend fun loadModelIfNeeded(): WhisperContext? {
        synchronized(modelLock) {
            whisperCtx?.let { return it }
        }
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("WhisperSTT: loading model from $modelPath")
                val ctx = WhisperContext(modelPath)
                Timber.d("WhisperSTT: model loaded. System info: ${ctx.systemInfo}")
                synchronized(modelLock) { whisperCtx = ctx }
                ctx
            } catch (e: Exception) {
                Timber.e(e, "WhisperSTT: failed to load model")
                null
            }
        }
    }

    private suspend fun recordAndTranscribe(
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBufSize, CHUNK_SAMPLES * 2 * 4) // room for 4 chunks

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Timber.e("WhisperSTT: AudioRecord init failed")
            mainHandler.post { onError("audio_error") }
            return
        }

        val allSamples = mutableListOf<Short>()
        val chunkBuf   = ShortArray(CHUNK_SAMPLES)
        var silentChunks = 0
        val maxSamples = SAMPLE_RATE * MAX_RECORD_SECONDS

        Timber.d("WhisperSTT: recording started")
        recorder.startRecording()

        try {
            while (coroutineContext.isActive && allSamples.size < maxSamples) {
                val read = recorder.read(chunkBuf, 0, CHUNK_SAMPLES)
                if (read <= 0) continue

                // Add chunk to buffer
                for (i in 0 until read) allSamples.add(chunkBuf[i])

                // VAD: skip silence detection until we have minimum speech
                if (allSamples.size < MIN_SPEECH_SAMPLES) continue

                // RMS of this chunk
                var sumSq = 0.0
                for (i in 0 until read) {
                    val f = chunkBuf[i] / 32768.0
                    sumSq += f * f
                }
                val rms = sqrt(sumSq / read).toFloat()

                if (rms < SILENCE_RMS_THRESH) {
                    silentChunks++
                    if (silentChunks >= SILENCE_CHUNKS) {
                        Timber.d("WhisperSTT: end of speech detected (${allSamples.size} samples)")
                        break
                    }
                } else {
                    silentChunks = 0
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
        }

        if (!coroutineContext.isActive) {
            Timber.d("WhisperSTT: cancelled")
            return
        }

        if (allSamples.size < MIN_SPEECH_SAMPLES) {
            Timber.d("WhisperSTT: too short, treating as silence")
            mainHandler.post { onError("no_match") }
            return
        }

        // Load model (usually already loaded from init)
        val ctx = loadModelIfNeeded()
        if (ctx == null) {
            mainHandler.post { onError("whisper_model_load_failed") }
            return
        }

        // Convert to float and transcribe
        Timber.d("WhisperSTT: transcribing ${allSamples.size} samples…")
        val floats = WhisperContext.shortsToFloats(allSamples.toShortArray())

        val text = try {
            ctx.transcribe(floats, language)
        } catch (e: Exception) {
            Timber.e(e, "WhisperSTT: transcription failed")
            mainHandler.post { onError("whisper_error:${e.message}") }
            return
        }

        Timber.d("WhisperSTT: result=\"$text\" lang=${ctx.detectedLanguage}")

        if (text.isBlank()) {
            mainHandler.post { onError("no_match") }
        } else {
            mainHandler.post { onResult(text) }
        }
    }

    private fun hasRecordPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
