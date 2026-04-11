package com.silentpulse.messenger.feature.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.checkedChanges
import com.silentpulse.messenger.R
import com.silentpulse.messenger.feature.drivemode.DriveModeMicService
import com.silentpulse.messenger.feature.drivemode.DriveModeWidgetProvider
import com.silentpulse.messenger.feature.drivemode.WidgetPrefs
import com.silentpulse.messenger.common.base.QkController
import com.silentpulse.messenger.injection.appComponent
import com.silentpulse.messenger.util.Preferences
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDispose
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import java.io.File
import javax.inject.Inject

class AssistantController : QkController<AssistantView, AssistantState, AssistantPresenter>(), AssistantView {

    // ── Card 1 – Enable ──────────────────────────────────────────────────────
    private val driveModeSwitch: SwitchCompat    get() = rootView!!.findViewById(R.id.driveModeSwitch)
    private val btAutoRow: LinearLayout          get() = rootView!!.findViewById(R.id.btAutoRow)
    private val readSmsSwitch: SwitchCompat      get() = rootView!!.findViewById(R.id.readSmsSwitch)
    private val readAllRow: LinearLayout         get() = rootView!!.findViewById(R.id.readAllRow)
    private val readAllSwitch: SwitchCompat      get() = rootView!!.findViewById(R.id.readAllSwitch)

    // ── Card 2 – STT ─────────────────────────────────────────────────────────
    private val sttCard: MaterialCardView         get() = rootView!!.findViewById(R.id.sttCard)
    private val sttEngineToggle: MaterialButtonToggleGroup get() = rootView!!.findViewById(R.id.sttEngineToggle)
    private val googleSttSection: LinearLayout    get() = rootView!!.findViewById(R.id.googleSttSection)
    private val voskSection: LinearLayout         get() = rootView!!.findViewById(R.id.voskSection)
    private val voskDownloadRow: LinearLayout     get() = rootView!!.findViewById(R.id.voskDownloadRow)
    private val voskImportRow: LinearLayout       get() = rootView!!.findViewById(R.id.voskImportRow)
    private val voskModelRow: LinearLayout        get() = rootView!!.findViewById(R.id.voskModelRow)
    private val voskModelSummary: TextView        get() = rootView!!.findViewById(R.id.voskModelSummary)

    // ── Card 3 – TTS ─────────────────────────────────────────────────────────
    private val ttsCard: MaterialCardView         get() = rootView!!.findViewById(R.id.ttsCard)
    private val ttsEngineToggle: MaterialButtonToggleGroup get() = rootView!!.findViewById(R.id.ttsEngineToggle)
    private val googleTtsSection: LinearLayout    get() = rootView!!.findViewById(R.id.googleTtsSection)
    private val kokoroSection: LinearLayout       get() = rootView!!.findViewById(R.id.kokoroSection)
    private val kokoroDownloadRow: LinearLayout   get() = rootView!!.findViewById(R.id.kokoroDownloadRow)
    private val kokoroImportRow: LinearLayout     get() = rootView!!.findViewById(R.id.kokoroImportRow)
    private val kokoroModelRow: LinearLayout      get() = rootView!!.findViewById(R.id.kokoroModelRow)
    private val kokoroModelSummary: TextView      get() = rootView!!.findViewById(R.id.kokoroModelSummary)
    private val kokoroSpeakerRow: LinearLayout    get() = rootView!!.findViewById(R.id.kokoroSpeakerRow)
    private val kokoroSpeakerSummary: TextView    get() = rootView!!.findViewById(R.id.kokoroSpeakerSummary)
    private val kokoroSpeedRow: LinearLayout      get() = rootView!!.findViewById(R.id.kokoroSpeedRow)
    private val kokoroSpeedSummary: TextView      get() = rootView!!.findViewById(R.id.kokoroSpeedSummary)

    // ── Card 4 – Behavior ────────────────────────────────────────────────────
    private val behaviorCard: MaterialCardView    get() = rootView!!.findViewById(R.id.behaviorCard)
    private val wakeWordSwitch: SwitchCompat      get() = rootView!!.findViewById(R.id.wakeWordSwitch)
    private val wakeWordLabel: TextView           get() = rootView!!.findViewById(R.id.wakeWordLabel)
    private val voiceReplySwitch: SwitchCompat    get() = rootView!!.findViewById(R.id.voiceReplySwitch)
    private val timeoutRow: LinearLayout          get() = rootView!!.findViewById(R.id.timeoutRow)
    private val timeoutSummary: TextView          get() = rootView!!.findViewById(R.id.timeoutSummary)
    private val retryRow: LinearLayout            get() = rootView!!.findViewById(R.id.retryRow)
    private val retrySummary: TextView            get() = rootView!!.findViewById(R.id.retrySummary)
    private val announcementsRow: LinearLayout    get() = rootView!!.findViewById(R.id.announcementsRow)
    private val announcementsSummary: TextView    get() = rootView!!.findViewById(R.id.announcementsSummary)

    @Inject override lateinit var presenter: AssistantPresenter
    @Inject lateinit var context: Context
    @Inject lateinit var prefs: Preferences

    init {
        appComponent.inject(this)
        layoutRes = R.layout.assistant_controller
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.title_offline_assistant)
        showBackButton(true)

        // ── Card 1: Enable ───────────────────────────────────────────────────

        driveModeSwitch.checkedChanges()
            .skipInitialValue()
            .autoDispose(scope())
            .subscribe { checked ->
                prefs.driveModeEnabled.set(checked)
                if (checked) {
                    checkNotificationAccess()
                    DriveModeMicService.start(context)
                } else {
                    DriveModeMicService.stop(context)
                }
                // Keep AppWidget and QS tiles in sync
                WidgetPrefs.broadcastStateChanged(context)
                DriveModeWidgetProvider.refreshAll(context)
            }

        readSmsSwitch.checkedChanges()
            .skipInitialValue()
            .autoDispose(scope())
            .subscribe { prefs.driveModeReadSms.set(it) }

        readAllSwitch.checkedChanges()
            .skipInitialValue()
            .autoDispose(scope())
            .subscribe { prefs.driveModeReadAllNotifications.set(it) }

        // ── Card 2: STT engine toggle ─────────────────────────────────────────

        sttEngineToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val engine = when (checkedId) {
                R.id.sttBtnVosk -> "vosk"
                else             -> "android"
            }
            prefs.driveModeSttEngine.set(engine)
            googleSttSection.isVisible = (engine == "android")
            voskSection.isVisible      = (engine == "vosk")
        }

        voskDownloadRow.clicks()
            .autoDispose(scope())
            .subscribe {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://alphacephei.com/vosk/models")))
            }

        voskImportRow.clicks()
            .autoDispose(scope())
            .subscribe { openZipPicker(RC_VOSK_ZIP) }

        voskModelRow.clicks()
            .autoDispose(scope())
            .subscribe { showVoskModelPicker() }

        // ── Card 3: TTS engine toggle ─────────────────────────────────────────

        ttsEngineToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val engine = when (checkedId) {
                R.id.ttsBtnKokoro -> "kokoro"
                else              -> "android"
            }
            prefs.driveModeTtsEngine.set(engine)
            googleTtsSection.isVisible = (engine == "android")
            kokoroSection.isVisible    = (engine == "kokoro")
        }

        kokoroDownloadRow.clicks()
            .autoDispose(scope())
            .subscribe {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/thewh1teagle/kokoro-onnx/releases")))
            }

        kokoroImportRow.clicks()
            .autoDispose(scope())
            .subscribe { openZipPicker(RC_KOKORO_ZIP) }

        kokoroModelRow.clicks()
            .autoDispose(scope())
            .subscribe { showKokoroModelPicker() }

        kokoroSpeakerRow.clicks()
            .autoDispose(scope())
            .subscribe { showKokoroSpeakerPicker(prefs.driveModeKokoroSpeakerId.get()) }

        kokoroSpeedRow.clicks()
            .autoDispose(scope())
            .subscribe { showKokoroSpeedPicker(prefs.driveModeKokoroSpeed.get()) }

        // ── Card 4: Behavior ─────────────────────────────────────────────────

        wakeWordSwitch.checkedChanges()
            .skipInitialValue()
            .autoDispose(scope())
            .subscribe { enabled ->
                if (enabled) {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.RECORD_AUDIO
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        activity?.let {
                            androidx.core.app.ActivityCompat.requestPermissions(
                                it, arrayOf(android.Manifest.permission.RECORD_AUDIO), 444
                            )
                        }
                        wakeWordSwitch.isChecked = false
                        return@subscribe
                    }
                    prefs.driveModeWakeWordEnabled.set(true)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(Intent(context, VoiceAssistantService::class.java))
                    } else {
                        context.startService(Intent(context, VoiceAssistantService::class.java))
                    }
                } else {
                    prefs.driveModeWakeWordEnabled.set(false)
                    context.stopService(Intent(context, VoiceAssistantService::class.java))
                }
                // Keep AppWidget and QS tiles in sync
                WidgetPrefs.broadcastStateChanged(context)
                DriveModeWidgetProvider.refreshAll(context)
            }

        voiceReplySwitch.checkedChanges()
            .skipInitialValue()
            .autoDispose(scope())
            .subscribe { prefs.driveModeVoiceReplyEnabled.set(it) }

        timeoutRow.clicks()
            .autoDispose(scope())
            .subscribe { showTimeoutPicker(prefs.driveModeReplyTimeoutSecs.get()) }

        retryRow.clicks()
            .autoDispose(scope())
            .subscribe { showRetryLimitPicker(prefs.driveModeMaxSttRetries.get()) }

        announcementsRow.clicks()
            .autoDispose(scope())
            .subscribe { showAnnouncementsLimitPicker(prefs.driveModeMaxAnnouncements.get()) }
    }

    override fun render(state: AssistantState) {
        val enabled = state.driveModeEnabled

        // Card 1
        driveModeSwitch.isChecked = enabled
        btAutoRow.isVisible       = enabled
        readSmsSwitch.isChecked   = state.driveModeReadSms
        readAllRow.isVisible      = enabled
        readAllSwitch.isChecked   = state.driveModeReadAll

        // Cards 2–4 visible iff assistant enabled
        sttCard.isVisible      = enabled
        ttsCard.isVisible      = enabled
        behaviorCard.isVisible = enabled

        if (enabled) {
            // STT engine
            val sttIsVosk = state.sttEngine == "vosk"
            val sttBtnToCheck = if (sttIsVosk) R.id.sttBtnVosk else R.id.sttBtnGoogle
            if (sttEngineToggle.checkedButtonId != sttBtnToCheck) {
                sttEngineToggle.check(sttBtnToCheck)
            }
            googleSttSection.isVisible = !sttIsVosk
            voskSection.isVisible      = sttIsVosk
            voskModelSummary.text = state.voskModelName

            // TTS engine
            val ttsIsKokoro = state.ttsEngine == "kokoro"
            val ttsBtnToCheck = if (ttsIsKokoro) R.id.ttsBtnKokoro else R.id.ttsBtnAndroid
            if (ttsEngineToggle.checkedButtonId != ttsBtnToCheck) {
                ttsEngineToggle.check(ttsBtnToCheck)
            }
            googleTtsSection.isVisible = !ttsIsKokoro
            kokoroSection.isVisible    = ttsIsKokoro

            if (ttsIsKokoro) {
                kokoroModelSummary.text = state.kokoroModelName
                val spkId = prefs.driveModeKokoroSpeakerId.get()
                kokoroSpeakerSummary.text = "Voice $spkId"
                val speed = prefs.driveModeKokoroSpeed.get().toFloatOrNull() ?: 1.0f
                kokoroSpeedSummary.text = "%.1f\u00d7".format(speed) + if (speed == 1.0f) " (normal)" else ""
            }

            // Behavior
            wakeWordLabel.text = "Wait for keyword '${VoiceAssistantService.WAKE_WORD}'"
            wakeWordSwitch.isChecked = state.driveModeWakeWordEnabled

            voiceReplySwitch.isChecked = state.driveModeVoiceReplyEnabled
            timeoutRow.isVisible       = state.driveModeVoiceReplyEnabled
            timeoutSummary.text        = state.driveModeTimeoutSummary
            retryRow.isVisible         = state.driveModeVoiceReplyEnabled
            retrySummary.text          = state.driveModeMaxRetriesSummary

            val maxAnn = prefs.driveModeMaxAnnouncements.get()
            announcementsSummary.text = if (maxAnn == 1) "1 time" else "$maxAnn times"
        }
    }

    // ── File picker ──────────────────────────────────────────────────────────

    private fun openZipPicker(requestCode: Int) {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                // Accept zip files across different MIME types
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream"
                ))
            },
            requestCode
        )
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != android.app.Activity.RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            RC_VOSK_ZIP -> importModelAsync(uri, "vosk") { path ->
                prefs.driveModeVoskModelPath.set(path)
                Toast.makeText(context, "Vosk model imported", Toast.LENGTH_SHORT).show()
            }
            RC_KOKORO_ZIP -> importModelAsync(uri, "kokoro") { path ->
                prefs.driveModeKokoroModelDir.set(path)
                Toast.makeText(context, "Kokoro model imported", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Extract zip on a background thread, then set the pref and show a toast on main thread.
     */
    private fun importModelAsync(uri: Uri, subDir: String, onDone: (String) -> Unit) {
        val ctx = context
        Toast.makeText(ctx, "Importing model…", Toast.LENGTH_SHORT).show()
        io.reactivex.Observable
            .fromCallable { ModelImporter.importZip(ctx, uri, subDir) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .autoDispose(scope())
            .subscribe({ path ->
                if (path != null) {
                    onDone(path)
                } else {
                    Toast.makeText(ctx, "Import failed — check the file is a valid .zip", Toast.LENGTH_LONG).show()
                }
            }, { e ->
                Toast.makeText(ctx, "Import error: ${e.message}", Toast.LENGTH_LONG).show()
            })
    }

    // ── Vosk model picker ────────────────────────────────────────────────────

    private fun showVoskModelPicker() {
        val ctx = activity ?: return
        val models = ModelImporter.listModels(context, "vosk")
        if (models.isEmpty()) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Vosk Models")
                .setMessage("No Vosk models imported yet.\n\nTap 'Browse Vosk models' to download one, then use 'Import model (.zip)' to install it.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val currentPath = prefs.driveModeVoskModelPath.get()
        val labels = models.map { it.first }.toTypedArray()
        val currentIndex = models.indexOfFirst { it.second == currentPath }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Select Vosk Model")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                prefs.driveModeVoskModelPath.set(models[which].second)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Kokoro model picker ──────────────────────────────────────────────────

    private fun showKokoroModelPicker() {
        val ctx = activity ?: return
        val models = ModelImporter.listModels(context, "kokoro")
        if (models.isEmpty()) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("Kokoro Voice Models")
                .setMessage("No Kokoro models imported yet.\n\nTap 'Browse Kokoro voices' to download one, then use 'Import voice model (.zip)' to install it.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val currentPath = prefs.driveModeKokoroModelDir.get()
        val labels = models.map { it.first }.toTypedArray()
        val currentIndex = models.indexOfFirst { it.second == currentPath }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Select Kokoro Voice")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                prefs.driveModeKokoroModelDir.set(models[which].second)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Kokoro speaker picker ─────────────────────────────────────────────────

    private fun showKokoroSpeakerPicker(current: Int) {
        val ctx = activity ?: return
        // kokoro-en-v0_19 has 10 speakers (0-9)
        val labels = (0..9).map { "Voice $it" }.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Select Speaker")
            .setSingleChoiceItems(labels, current.coerceIn(0, 9)) { dialog, which ->
                prefs.driveModeKokoroSpeakerId.set(which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Kokoro speed picker ───────────────────────────────────────────────────

    private fun showKokoroSpeedPicker(current: String) {
        val ctx = activity ?: return
        val speeds = arrayOf("0.7", "0.85", "1.0", "1.1", "1.2", "1.4", "1.6")
        val labels = arrayOf("0.7\u00d7 (slow)", "0.85\u00d7", "1.0\u00d7 (normal)",
            "1.1\u00d7", "1.2\u00d7", "1.4\u00d7", "1.6\u00d7 (fast)")
        val currentIndex = speeds.indexOfFirst { it == current }.coerceAtLeast(2)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Speech Speed")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                prefs.driveModeKokoroSpeed.set(speeds[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Shared pickers (kept from original) ─────────────────────────────────

    override fun showTimeoutPicker(currentSecs: Int) {
        val labels = context.resources.getStringArray(R.array.drive_mode_timeout_labels)
        val values = context.resources.getIntArray(R.array.drive_mode_timeout_values)
        val currentIndex = values.indexOfFirst { it == currentSecs }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity!!)
            .setTitle(R.string.settings_drive_mode_timeout_title)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                prefs.driveModeReplyTimeoutSecs.set(values[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    override fun showRetryLimitPicker(currentRetries: Int) {
        val labels = arrayOf("No retries", "1 retry", "2 retries", "3 retries", "5 retries")
        val values = intArrayOf(0, 1, 2, 3, 5)
        val currentIndex = values.indexOfFirst { it == currentRetries }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity!!)
            .setTitle(R.string.settings_drive_mode_retry_title)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                prefs.driveModeMaxSttRetries.set(values[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    override fun showTtsEnginePicker(current: String) {
        // Deprecated — engine is now selected via inline toggle in the TTS card.
        // This method is kept to satisfy the AssistantView interface contract.
    }

    private fun showAnnouncementsLimitPicker(currentMax: Int) {
        val labels = arrayOf("1 time (no repeat)", "2 times", "3 times", "5 times", "Unlimited")
        val values = intArrayOf(1, 2, 3, 5, 999)
        val currentIndex = values.indexOfFirst { it == currentMax }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity!!)
            .setTitle("Max repeats per message")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                prefs.driveModeMaxAnnouncements.set(values[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    // ── Notification access ──────────────────────────────────────────────────

    private fun checkNotificationAccess() {
        val ctx = activity ?: return
        val granted = androidx.core.app.NotificationManagerCompat
            .getEnabledListenerPackages(ctx)
            .contains(ctx.packageName)
        if (!granted) showNotificationAccessDialog()
    }

    private fun showNotificationAccessDialog() {
        MaterialAlertDialogBuilder(activity!!)
            .setTitle("Notification Access Required")
            .setMessage(
                "The Offline Assistant reads incoming notifications aloud.\n\n" +
                "It needs \"Notification Access\" — a system permission that lets " +
                "SilentPulse see notifications.\n\n" +
                "Tap \"Grant Access\" and enable SilentPulse in the list."
            )
            .setPositiveButton("Grant Access") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("Not Now", null)
            .show()
    }

    companion object {
        private const val RC_VOSK_ZIP   = 1003
        private const val RC_KOKORO_ZIP = 1004
    }
}
