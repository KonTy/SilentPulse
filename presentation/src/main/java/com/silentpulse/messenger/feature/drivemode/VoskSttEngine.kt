package com.silentpulse.messenger.feature.drivemode

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import timber.log.Timber
import java.io.File

/**
 * Fully air-gapped STT engine using Vosk (https://alphacephei.com/vosk/).
 *
 * Vosk runs entirely on-device with no network calls at all —
 * it doesn't even attempt to connect, unlike Android's SpeechRecognizer.
 * Perfect for voice-command recognition (fast, lightweight models).
 *
 * Model: vosk-model-small-en-us-0.15 (~40 MB) at [modelPath].
 * Expected latency: <1 second for short commands.
 */
class VoskSttEngine(
    private val context: Context,
    private val modelPath: String
) : SttEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speechService: SpeechService? = null
    @Volatile private var listening = false

    override fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (modelPath.isBlank()) {
            Timber.e("VoskSTT: model path is blank")
            onError("vosk_no_model_path")
            return
        }

        val modelDir = File(modelPath)
        if (!modelDir.exists() || !modelDir.isDirectory) {
            Timber.e("VoskSTT: model directory not found: $modelPath")
            onError("vosk_model_not_found")
            return
        }

        // Load model if not loaded yet
        if (model == null) {
            try {
                Timber.d("VoskSTT: loading model from $modelPath")
                model = Model(modelPath)
                Timber.d("VoskSTT: model loaded")
            } catch (e: Exception) {
                Timber.e(e, "VoskSTT: failed to load model")
                onError("vosk_model_load_failed")
                return
            }
        }

        try {
            stopListeningInternal()

            val recognizer = Recognizer(model, 16000.0f)
            val service = SpeechService(recognizer, 16000.0f)
            speechService = service
            listening = true

            service.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    if (hypothesis == null) return
                    try {
                        val json = JSONObject(hypothesis)
                        val partial = json.optString("partial", "")
                        if (partial.isNotBlank()) {
                            Timber.d("VoskSTT: partial=\"$partial\"")
                        }
                    } catch (_: Exception) {}
                }

                override fun onResult(hypothesis: String?) {
                    if (!listening) return
                    listening = false
                    if (hypothesis == null) {
                        mainHandler.post { onError("no_match") }
                        return
                    }
                    try {
                        val json = JSONObject(hypothesis)
                        val text = json.optString("text", "").trim()
                        Timber.d("VoskSTT: result=\"$text\"")
                        if (text.isNotBlank()) {
                            mainHandler.post { onResult(text) }
                        } else {
                            mainHandler.post { onError("no_match") }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "VoskSTT: failed to parse result")
                        mainHandler.post { onError("vosk_parse_error") }
                    }
                }

                override fun onFinalResult(hypothesis: String?) {
                    if (!listening) return
                    listening = false
                    if (hypothesis == null) {
                        mainHandler.post { onError("no_match") }
                        return
                    }
                    try {
                        val json = JSONObject(hypothesis)
                        val text = json.optString("text", "").trim()
                        Timber.d("VoskSTT: final result=\"$text\"")
                        if (text.isNotBlank()) {
                            mainHandler.post { onResult(text) }
                        } else {
                            mainHandler.post { onError("no_match") }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "VoskSTT: failed to parse final result")
                        mainHandler.post { onError("vosk_parse_error") }
                    }
                }

                override fun onError(exception: Exception?) {
                    listening = false
                    Timber.e(exception, "VoskSTT: recognition error")
                    mainHandler.post { onError("vosk_error") }
                }

                override fun onTimeout() {
                    listening = false
                    Timber.d("VoskSTT: timeout")
                    mainHandler.post { onError("speech_timeout") }
                }
            })

            Timber.d("VoskSTT: listening started")
        } catch (e: Exception) {
            Timber.e(e, "VoskSTT: failed to start listening")
            listening = false
            onError("vosk_error")
        }
    }

    override fun stopListening() {
        stopListeningInternal()
    }

    override fun shutdown() {
        stopListeningInternal()
        model?.close()
        model = null
    }

    private fun stopListeningInternal() {
        if (listening) {
            try {
                speechService?.stop()
            } catch (_: Exception) {}
            listening = false
        }
        try {
            speechService?.shutdown()
        } catch (_: Exception) {}
        speechService = null
    }
}
