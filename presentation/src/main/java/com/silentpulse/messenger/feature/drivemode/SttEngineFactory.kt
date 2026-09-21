package com.silentpulse.messenger.feature.drivemode

import android.content.Context
import com.silentpulse.messenger.util.Preferences
import java.io.File
import javax.inject.Inject

/** No implicit fallback: the selected local engine either works or reports why it cannot. */
class SttEngineFactory @Inject constructor(
    private val context: Context,
    private val prefs: Preferences
) {
    companion object {
        const val ENGINE_VOSK = "vosk"
        const val ENGINE_ANDROID = "android"
        const val ENGINE_WHISPER = "whisper"

        internal fun select(
            preference: String,
            android: () -> SttEngine,
            vosk: () -> SttEngine,
            whisper: () -> SttEngine
        ): SttEngine = when (preference.ifBlank { ENGINE_ANDROID }) {
            ENGINE_ANDROID -> android()
            ENGINE_VOSK -> vosk()
            ENGINE_WHISPER -> whisper()
            else -> UnavailableSttEngine("stt_unknown_engine")
        }
    }

    fun create(): SttEngine = select(
        prefs.driveModeSttEngine.get(),
        android = { AndroidSttEngine(context) },
        vosk = {
            val path = prefs.driveModeVoskModelPath.get()
            when {
                path.isBlank() -> UnavailableSttEngine("vosk_no_model_path")
                !File(path).isDirectory -> UnavailableSttEngine("vosk_model_not_found")
                else -> VoskSttEngine(context, path)
            }
        },
        whisper = {
            val path = prefs.driveModeWhisperModelPath.get()
            when {
                path.isBlank() -> UnavailableSttEngine("whisper_no_model_path")
                !File(path).isFile -> UnavailableSttEngine("whisper_model_not_found")
                else -> WhisperSttEngine(context, path, prefs.driveModeWhisperLanguage.get().ifBlank { "en" })
            }
        }
    )
}

internal class UnavailableSttEngine(private val code: String) : SttEngine {
    override fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) = onError(code)
    override fun stopListening() {}
    override fun shutdown() {}
}
