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
        private const val MIN_RECORD_SAMPLES = 48_000          // 3 s minimum before silence detection
        private const val SILENCE_CHUNKS     = 20              // 2 s of trailing silence → stop
        private const val SILENCE_RMS_THRESH = 0.01f           // normalised RMS threshold
        private const val MAX_RECORD_SECONDS = 30              // hard cap

        /**
         * Minimum average RMS the recording must have to be worth transcribing.
         * Below this, the recording is pure silence / background noise and we
         * skip the expensive Whisper inference entirely (saves ~60 s on ggml-small).
         */
        private const val MIN_SPEECH_ENERGY  = 0.002f
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordingJob: Job? = null

    // Model is loaded once and reused across calls (loading can take 1-3 s for large models)
    @Volatile private var whisperCtx: WhisperContext? = null
    private val modelLock = Any()

    init {
        // Pre-load model on background thread immediately so it's ready when the user speaks
        scope.launch(Dispatchers.IO) {
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

    /**
     * Thread-safe model loader with double-checked locking.
     * The model is loaded once and cached for the lifetime of this engine.
     * Blocks the calling thread during the first load (~3-5 s for ggml-small).
     */
    private fun loadModelIfNeeded(): WhisperContext? {
        // Fast path — already loaded
        whisperCtx?.let { return it }
        if (modelPath.isBlank()) return null
        // Slow path — synchronize to prevent two threads loading simultaneously
        synchronized(modelLock) {
            // Double-check inside lock
            whisperCtx?.let { return it }
            return try {
                Timber.d("WhisperSTT: loading model from $modelPath")
                val ctx = WhisperContext(modelPath)
                Timber.d("WhisperSTT: model loaded. System info: ${ctx.systemInfo}")
                whisperCtx = ctx
                ctx
            } catch (e: Throwable) {
                Timber.e(e, "WhisperSTT: failed to load model (${e.javaClass.simpleName})")
                null
            }
        }
    }

    private suspend fun recordAndTranscribe(
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        // ── 1. Pre-load model BEFORE recording ──────────────────────────────
        // This ensures the model is warm when we need it and prevents the
        // race where recording finishes before the model is loaded.
        val ctx = withContext(Dispatchers.IO) { loadModelIfNeeded() }
        if (ctx == null) {
            mainHandler.post { onError("whisper_model_load_failed") }
            return
        }

        // ── 2. Record audio ──────────────────────────────────────────────────
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
        var peakRms = 0.0f
        var speechChunks = 0
        val maxSamples = SAMPLE_RATE * MAX_RECORD_SECONDS

        // Play a short beep so the user knows we're listening (like Google STT)
        try {
            val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80)
            toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
            Thread.sleep(200) // let the beep finish before recording
            toneGen.release()
        } catch (e: Exception) {
            Timber.w(e, "WhisperSTT: beep failed (non-fatal)")
        }

        Timber.d("WhisperSTT: recording started")
        recorder.startRecording()

        try {
            while (coroutineContext.isActive && allSamples.size < maxSamples) {
                val read = recorder.read(chunkBuf, 0, CHUNK_SAMPLES)
                if (read <= 0) continue

                // Add chunk to buffer
                for (i in 0 until read) allSamples.add(chunkBuf[i])

                // VAD: skip silence detection until minimum recording duration
                if (allSamples.size < MIN_RECORD_SAMPLES) continue

                // RMS of this chunk
                var sumSq = 0.0
                for (i in 0 until read) {
                    val f = chunkBuf[i] / 32768.0
                    sumSq += f * f
                }
                val rms = sqrt(sumSq / read).toFloat()
                if (rms > peakRms) peakRms = rms

                if (rms < SILENCE_RMS_THRESH) {
                    silentChunks++
                    if (silentChunks >= SILENCE_CHUNKS) {
                        Timber.d("WhisperSTT: end of speech detected (${allSamples.size} samples, peakRms=%.5f, speechChunks=$speechChunks)".format(peakRms))
                        break
                    }
                } else {
                    speechChunks++
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

        if (allSamples.size < 8_000) { // < 500ms means something went wrong
            Timber.d("WhisperSTT: too short (${allSamples.size} samples), treating as silence")
            mainHandler.post { onError("no_match") }
            return
        }

        // ── 3. Energy pre-filter ─────────────────────────────────────────────
        // If the entire recording is just silence / low background noise,
        // skip the expensive Whisper inference (~60 s on ggml-small) and
        // return "no_match" immediately.
        val floats = WhisperContext.shortsToFloats(allSamples.toShortArray())
        var energySum = 0.0
        for (f in floats) energySum += f * f
        val avgRms = sqrt(energySum / floats.size).toFloat()
        Timber.d("WhisperSTT: recording avgRms=%.5f peakRms=%.5f speechChunks=%d (threshold=%.5f) samples=%d".format(avgRms, peakRms, speechChunks, MIN_SPEECH_ENERGY, allSamples.size))
        if (avgRms < MIN_SPEECH_ENERGY) {
            // avgRms == 0.0 usually means Android silenced the mic (background service)
            val errorCode = if (avgRms == 0.0f) "mic_silenced" else "no_match"
            Timber.d("WhisperSTT: recording is silence (avgRms=$avgRms), skipping transcription (errorCode=$errorCode)")
            mainHandler.post { onError(errorCode) }
            return
        }

        // ── 4. Trim leading silence ──────────────────────────────────────────
        // Whisper works better when speech starts near the beginning of the buffer.
        // Find the first chunk where energy exceeds the silence threshold and
        // start from there (keep 200ms of lead-in for natural onset).
        val chunkSize = CHUNK_SAMPLES  // 100ms per chunk in samples
        var trimStart = 0
        for (i in floats.indices step chunkSize) {
            val end = minOf(i + chunkSize, floats.size)
            var cs = 0.0
            for (j in i until end) cs += floats[j] * floats[j]
            val chunkRms = sqrt(cs / (end - i)).toFloat()
            if (chunkRms >= SILENCE_RMS_THRESH) {
                // Found speech — back up 200ms (3200 samples) for natural onset
                trimStart = maxOf(0, i - 3200)
                break
            }
        }
        val trimmedFloats = if (trimStart > 0) {
            Timber.d("WhisperSTT: trimmed %d samples (%.1fs) of leading silence".format(trimStart, trimStart.toFloat() / SAMPLE_RATE))
            floats.copyOfRange(trimStart, floats.size)
        } else {
            floats
        }

        // ── 5. Transcribe ────────────────────────────────────────────────────
        Timber.d("WhisperSTT: transcribing ${trimmedFloats.size} samples (avgRms=$avgRms, trimmed from ${floats.size})…")

        val text = try {
            ctx.transcribe(trimmedFloats, language)
        } catch (e: Throwable) {
            Timber.e(e, "WhisperSTT: transcription failed (${e.javaClass.simpleName})")
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
