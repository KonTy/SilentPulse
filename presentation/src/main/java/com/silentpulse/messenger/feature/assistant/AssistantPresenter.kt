package com.silentpulse.messenger.feature.assistant

import com.silentpulse.messenger.common.base.QkPresenter
import com.silentpulse.messenger.util.Preferences
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDispose
import javax.inject.Inject

class AssistantPresenter @Inject constructor(
    private val prefs: Preferences
) : QkPresenter<AssistantView, AssistantState>(AssistantState()) {

    override fun bindIntents(view: AssistantView) {
        super.bindIntents(view)

        prefs.driveModeEnabled.asObservable()
            .autoDispose(view.scope())
            .subscribe { newState { copy(driveModeEnabled = it) } }

        prefs.driveModeReadSms.asObservable()
            .autoDispose(view.scope())
            .subscribe { newState { copy(driveModeReadSms = it) } }

        prefs.driveModeReadAllNotifications.asObservable()
            .autoDispose(view.scope())
            .subscribe { newState { copy(driveModeReadAll = it) } }

        prefs.driveModeWakeWordEnabled.asObservable()
            .autoDispose(view.scope())
            .subscribe { newState { copy(driveModeWakeWordEnabled = it) } }

        prefs.driveModeVoiceReplyEnabled.asObservable()
            .autoDispose(view.scope())
            .subscribe { newState { copy(driveModeVoiceReplyEnabled = it) } }

        prefs.driveModeReplyTimeoutSecs.asObservable()
            .autoDispose(view.scope())
            .subscribe { secs ->
                val summary = when (secs) {
                    10   -> "10 seconds"
                    20   -> "20 seconds"
                    30   -> "30 seconds"
                    60   -> "1 minute"
                    120  -> "2 minutes"
                    else -> "$secs seconds"
                }
                newState { copy(driveModeTimeoutSummary = summary) }
            }

        prefs.driveModeMaxSttRetries.asObservable()
            .autoDispose(view.scope())
            .subscribe { retries ->
                val summary = when (retries) {
                    0    -> "No retries (give up immediately)"
                    1    -> "1 retry"
                    else -> "$retries retries"
                }
                newState { copy(driveModeMaxRetriesSummary = summary) }
            }

        prefs.driveModeSttEngine.asObservable()
            .autoDispose(view.scope())
            .subscribe { engine -> newState { copy(sttEngine = engine) } }

        prefs.driveModeWhisperModelPath.asObservable()
            .autoDispose(view.scope())
            .subscribe { path ->
                val name = when {
                    path.isBlank() -> "No model selected"
                    else -> java.io.File(path).nameWithoutExtension
                        .removePrefix("ggml-")
                        .replaceFirstChar { it.uppercase() }
                        .let { "$it  ·  ${formatSize(java.io.File(path).length())}" }
                }
                newState { copy(whisperModelName = name) }
            }

        prefs.driveModeTtsEngine.asObservable()
            .autoDispose(view.scope())
            .subscribe { engine ->
                val summary = when (engine) {
                    "kokoro" -> "Kokoro TTS (Expressive AI)"
                    else     -> "Android TTS (Offline)"
                }
                newState { copy(ttsEngineSummary = summary) }
            }

        prefs.driveModeWhisperModelsDir.asObservable()
            .autoDispose(view.scope())
            .subscribe { dir -> newState { copy(whisperModelsDir = dir) } }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L     -> "%.0f MB".format(bytes / 1_000_000.0)
        else                    -> "%.0f KB".format(bytes / 1_000.0)
    }
}
