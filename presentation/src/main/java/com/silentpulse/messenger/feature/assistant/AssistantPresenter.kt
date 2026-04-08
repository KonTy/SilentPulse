package com.silentpulse.messenger.feature.assistant

import com.silentpulse.messenger.common.base.QkPresenter
import com.silentpulse.messenger.util.Preferences
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDispose
import java.io.File
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

        // STT engine selection
        prefs.driveModeSttEngine.asObservable()
            .autoDispose(view.scope())
            .subscribe { engine -> newState { copy(sttEngine = engine) } }

        // Vosk model name
        prefs.driveModeVoskModelPath.asObservable()
            .autoDispose(view.scope())
            .subscribe { path ->
                val name = when {
                    path.isBlank() -> "No model — tap to import"
                    else -> File(path).name.ifBlank { File(path).parentFile?.name ?: "Unknown" }
                }
                newState { copy(voskModelName = name) }
            }

        // TTS engine selection
        prefs.driveModeTtsEngine.asObservable()
            .autoDispose(view.scope())
            .subscribe { engine -> newState { copy(ttsEngine = engine) } }

        // Kokoro model name
        prefs.driveModeKokoroModelDir.asObservable()
            .autoDispose(view.scope())
            .subscribe { dir ->
                val name = when {
                    dir.isBlank() -> "No model — tap to import"
                    else -> File(dir).name.ifBlank { "Unknown" }
                }
                newState { copy(kokoroModelName = name) }
            }
    }
}
