package com.silentpulse.messenger.feature.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Lightweight voice activity detector using [AudioRecord].
 *
 * Continuously reads raw PCM from the mic and computes the RMS amplitude.
 * When the amplitude exceeds [SPEECH_THRESHOLD_RMS] for [TRIGGER_FRAMES]
 * consecutive frames, it fires the [onSpeechDetected] callback.
 *
 * This is NOT a speech recogniser — it just detects "someone is talking".
 * The caller can then spin up a heavier SpeechRecognizer, which avoids
 * the beep-every-5-seconds problem of looping SpeechRecognizer directly.
 *
 * Runs on a background thread. Callbacks are posted to the main thread.
 */
class VoiceActivityDetector(private val context: Context) {

    companion object {
        private const val TAG = "VAD"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /**
         * RMS threshold to consider "speech is happening".
         * Typical quiet room: 50-200.  Normal speech at arm's length: 1000-5000.
         * We need this high enough to ignore TV/AC/background and the STT beep
         * sound (~2000-3000 RMS), but low enough to catch normal speech (~3000+).
         */
        const val SPEECH_THRESHOLD_RMS = 3500

        /**
         * How many consecutive frames must exceed the threshold before
         * we fire the callback.  1 frame ≈ 20 ms at 16 kHz / 320 samples.
         * 3 frames ≈ 60 ms — enough to reject a single click or pop.
         */
        private const val TRIGGER_FRAMES = 3

        /**
         * After firing the callback, ignore further triggers for this long.
         * Must be longer than a full STT cycle (~5-8s) so the STT beep itself
         * doesn't immediately retrigger VAD.
         */
        private const val COOLDOWN_MS = 10_000L
    }

    private var audioRecord: AudioRecord? = null
    private var running = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var thread: Thread? = null
    @Volatile private var cooldownUntil = 0L

    // Externally adjustable threshold (settings can tune this).
    var threshold = SPEECH_THRESHOLD_RMS

    /**
     * Starts monitoring the mic in the background.
     * [onSpeechDetected] is called on the main thread when speech-like
     * audio is detected.  It will NOT fire again until [COOLDOWN_MS] elapses
     * or [resume] is called explicitly.
     */
    fun start(onSpeechDetected: () -> Unit) {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING),
            SAMPLE_RATE  // at least 1 second buffer
        )

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException creating AudioRecord", e)
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise")
            recorder.release()
            return
        }

        audioRecord = recorder
        running = true
        cooldownUntil = 0L

        thread = Thread({
            Log.d(TAG, "VAD thread started (threshold=$threshold)")
            recorder.startRecording()
            val buf = ShortArray(320)  // 20 ms at 16 kHz
            var consecutiveAbove = 0

            while (running) {
                val read = recorder.read(buf, 0, buf.size)
                if (read <= 0) continue

                if (System.currentTimeMillis() < cooldownUntil) {
                    continue
                }

                val rms = computeRms(buf, read)
                if (rms >= threshold) {
                    consecutiveAbove++
                    if (consecutiveAbove >= TRIGGER_FRAMES) {
                        Log.d(TAG, "Speech detected! RMS=$rms (threshold=$threshold)")
                        cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
                        consecutiveAbove = 0
                        mainHandler.post { onSpeechDetected() }
                    }
                } else {
                    consecutiveAbove = 0
                }
            }

            recorder.stop()
            recorder.release()
            Log.d(TAG, "VAD thread stopped")
        }, "VAD-Monitor")
        thread!!.start()
    }

    /**
     * Stops monitoring and releases the mic.
     */
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        audioRecord = null
    }

    /**
     * Resets the cooldown so the detector can fire again immediately.
     * Call this after SpeechRecognizer finishes its session.
     */
    fun resume() {
        cooldownUntil = 0L
    }

    private fun computeRms(buf: ShortArray, length: Int): Int {
        var sum = 0L
        for (i in 0 until length) {
            val s = buf[i].toLong()
            sum += s * s
        }
        return Math.sqrt(sum.toDouble() / length).toInt()
    }
}
