package com.silentpulse.messenger.feature.drivemode

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.silentpulse.messenger.util.Preferences
import timber.log.Timber
import java.io.File

/**
 * Kokoro TTS engine powered by Sherpa-ONNX.
 * Fully on-device — no network calls, no cloud services.
 *
 * Model files expected in [modelDir]:
 *   model.onnx, voices.bin, tokens.txt, espeak-ng-data/
 */
class KokoroTtsEngine(
    context: Context,
    private val prefs: Preferences
) : TtsEngine {

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var stopped = false
    @Volatile private var speaking = false

    override var isReady: Boolean = false
        private set

    init {
        try {
            val modelDir = prefs.driveModeKokoroModelDir.get()
            if (modelDir.isNotBlank() && File(modelDir, "model.onnx").exists()) {
                initEngine(modelDir)
            } else {
                // Try app-private external directory (no storage permission needed on Android 10+)
                val appPrivateDir = context.getExternalFilesDir(null)?.let {
                    File(it, "kokoro-en-v0_19").absolutePath
                } ?: "/sdcard/Android/data/com.silentpulse.messenger/files/kokoro-en-v0_19"
                if (File(appPrivateDir, "model.onnx").exists()) {
                    prefs.driveModeKokoroModelDir.set(appPrivateDir)
                    initEngine(appPrivateDir)
                } else {
                    Timber.w("Kokoro model not found at $modelDir or $appPrivateDir")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Kokoro TTS engine")
        }
    }

    private fun initEngine(modelDir: String) {
        val modelPath = "$modelDir/model.onnx"
        val voicesPath = "$modelDir/voices.bin"
        val tokensPath = "$modelDir/tokens.txt"
        val dataDir = "$modelDir/espeak-ng-data"

        // Verify all required files exist
        listOf(modelPath, voicesPath, tokensPath).forEach { path ->
            if (!File(path).exists()) {
                Timber.e("Kokoro: missing required file: $path")
                return
            }
        }
        if (!File(dataDir).isDirectory) {
            Timber.e("Kokoro: espeak-ng-data directory missing: $dataDir")
            return
        }

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = modelPath,
                    voices = voicesPath,
                    tokens = tokensPath,
                    dataDir = dataDir,
                    lengthScale = 1.0f,
                ),
                numThreads = 4,
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 2,
        )

        tts = OfflineTts(config = config)
        initAudioTrack()
        isReady = true
        Timber.d("Kokoro TTS engine initialized (model: $modelDir, speakers: ${tts?.numSpeakers()})")
    }

    private fun initAudioTrack() {
        val sampleRate = tts?.sampleRate() ?: 24000
        val bufLength = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )

        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        audioTrack = AudioTrack(
            attr, format, bufLength, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }

    override fun speak(text: String, onDone: () -> Unit) {
        if (!isReady || tts == null) {
            Timber.w("Kokoro TTS not ready, skipping speech")
            onDone()
            return
        }

        stopped = false
        speaking = true

        Thread(Runnable {
            try {
                val track = audioTrack ?: run {
                    Timber.e("AudioTrack is null")
                    onDone()
                    return@Runnable
                }

                track.play()

                val sid = prefs.driveModeKokoroSpeakerId.get()
                val speed = prefs.driveModeKokoroSpeed.get().toFloatOrNull() ?: 1.0f

                tts?.generateWithCallback(
                    text = text,
                    sid = sid,
                    speed = speed,
                ) { samples: FloatArray ->
                    if (!stopped) {
                        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        1 // continue
                    } else {
                        0 // abort
                    }
                }

                // Wait for track to finish playing buffered audio
                if (!stopped) {
                    track.stop()
                }
            } catch (e: Exception) {
                Timber.e(e, "Kokoro TTS speak error")
            } finally {
                speaking = false
                onDone()
            }
        }, "kokoro-tts-speak").start()
    }

    override fun stop() {
        stopped = true
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) {
            Timber.e(e, "Error stopping Kokoro AudioTrack")
        }
    }

    override fun shutdown() {
        stop()
        try {
            audioTrack?.release()
            audioTrack = null
        } catch (_: Exception) {}
        try {
            tts?.free()
            tts = null
        } catch (_: Exception) {}
        isReady = false
        Timber.d("Kokoro TTS engine shut down")
    }
}
