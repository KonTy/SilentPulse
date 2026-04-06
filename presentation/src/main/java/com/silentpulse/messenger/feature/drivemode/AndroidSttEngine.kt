package com.silentpulse.messenger.feature.drivemode

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import timber.log.Timber
import javax.inject.Inject

/**
 * STT engine using Android's built-in SpeechRecognizer with EXTRA_PREFER_OFFLINE.
 * Privacy: requests on-device processing. Requires the Google offline speech model
 * to be downloaded in device language settings for fully air-gapped operation.
 *
 * MUST be called from the main thread (Android SpeechRecognizer requirement).
 */
class AndroidSttEngine @Inject constructor(
    private val context: Context
) : SttEngine {

    private var speechRecognizer: SpeechRecognizer? = null
    private var currentOnResult: ((String) -> Unit)? = null
    private var currentOnError: ((String) -> Unit)? = null

    override fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        currentOnResult = onResult
        currentOnError = onError

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("recognition_unavailable")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { Timber.d("Android STT: ready") }
                override fun onBeginningOfSpeech() { Timber.d("Android STT: speech started") }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { Timber.d("Android STT: speech ended") }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim() ?: ""
                    Timber.d("Android STT result: \"$text\"")
                    if (text.isNotBlank()) currentOnResult?.invoke(text)
                    else currentOnError?.invoke("no_match")
                }

                override fun onError(error: Int) {
                    val code = standardErrorCode(error)
                    Timber.e("Android STT error: $code ($error)")
                    currentOnError?.invoke(code)
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)  // Privacy: request on-device model
                // Stop listening ~2s after speech ends
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }
            startListening(intent)
        }
    }

    override fun stopListening() {
        speechRecognizer?.stopListening()
    }

    override fun shutdown() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        currentOnResult = null
        currentOnError = null
    }

    private fun standardErrorCode(error: Int) = when (error) {
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT           -> "speech_timeout"
        SpeechRecognizer.ERROR_NO_MATCH                 -> "no_match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY          -> "recognizer_busy"
        SpeechRecognizer.ERROR_AUDIO                    -> "audio_error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission_denied"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT          -> "network_error"   // offline model not downloaded
        SpeechRecognizer.ERROR_SERVER                   -> "server_error"
        else                                            -> "stt_error_$error"
    }
}
