package com.silentpulse.messenger.feature.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.checkedChanges
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.base.QkController
import com.silentpulse.messenger.injection.appComponent
import com.silentpulse.messenger.util.Preferences
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDispose
import javax.inject.Inject

class AssistantController : QkController<AssistantView, AssistantState, AssistantPresenter>(), AssistantView {

    // ── Toolbar ──────────────────────────────────────────────────────────────────
    private val toolbar: Toolbar get() = rootView!!.findViewById(R.id.toolbar)

    // ── Card 1 – Enable ─────────────────────────────────────────────────────────
    private val driveModeSwitch: SwitchCompat     get() = rootView!!.findViewById(R.id.driveModeSwitch)
    private val btAutoRow: LinearLayout            get() = rootView!!.findViewById(R.id.btAutoRow)
    private val readSmsSwitch: SwitchCompat        get() = rootView!!.findViewById(R.id.readSmsSwitch)
    private val readAllRow: LinearLayout           get() = rootView!!.findViewById(R.id.readAllRow)
    private val readAllSwitch: SwitchCompat        get() = rootView!!.findViewById(R.id.readAllSwitch)

    // ── Card 2 – STT ─────────────────────────────────────────────────────────────
    private val sttCard: MaterialCardView           get() = rootView!!.findViewById(R.id.sttCard)
    private val sttRadioGroup: RadioGroup           get() = rootView!!.findViewById(R.id.sttRadioGroup)
    private val googleSection: LinearLayout         get() = rootView!!.findViewById(R.id.googleSection)
    private val googleInstallRow: LinearLayout      get() = rootView!!.findViewById(R.id.googleInstallRow)
    private val whisperSection: LinearLayout        get() = rootView!!.findViewById(R.id.whisperSection)
    private val whisperDownloadRow: LinearLayout    get() = rootView!!.findViewById(R.id.whisperDownloadRow)
    private val whisperFolderRow: LinearLayout      get() = rootView!!.findViewById(R.id.whisperFolderRow)
    private val whisperFolderSummary: TextView      get() = rootView!!.findViewById(R.id.whisperFolderSummary)
    private val whisperModelRow: LinearLayout       get() = rootView!!.findViewById(R.id.whisperModelRow)
    private val whisperModelSummary: TextView       get() = rootView!!.findViewById(R.id.whisperModelSummary)

    // ── Card 3 – TTS ─────────────────────────────────────────────────────────────
    private val ttsCard: MaterialCardView           get() = rootView!!.findViewById(R.id.ttsCard)
    private val ttsEngineRow: LinearLayout          get() = rootView!!.findViewById(R.id.ttsEngineRow)
    private val ttsEngineSummary: TextView          get() = rootView!!.findViewById(R.id.ttsEngineSummary)

    // ── Card 4 – Behavior ────────────────────────────────────────────────────────
    private val behaviorCard: MaterialCardView      get() = rootView!!.findViewById(R.id.behaviorCard)
    private val voiceReplySwitch: SwitchCompat      get() = rootView!!.findViewById(R.id.voiceReplySwitch)
    private val timeoutRow: LinearLayout            get() = rootView!!.findViewById(R.id.timeoutRow)
    private val timeoutSummary: TextView            get() = rootView!!.findViewById(R.id.timeoutSummary)

    @Inject override lateinit var presenter: AssistantPresenter
    @Inject lateinit var context: Context
    @Inject lateinit var prefs: Preferences

    init {
        appComponent.inject(this)
        layoutRes = R.layout.assistant_controller
    }

    override fun onViewCreated() {
        toolbar.setNavigationOnClickListener { activity?.onBackPressed() }
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.title_offline_assistant)
        showBackButton(true)

        // Drive mode master toggle
        driveModeSwitch.checkedChanges()
            .skipInitialValue()
            .autoDispose(scope())
            .subscribe { checked ->
                prefs.driveModeEnabled.set(checked)
                if (checked) checkNotificationAccess()
            }

        // Read SMS switch
        readSmsSwitch.checkedChanges()
            .skipInitialValue()
            .autoDispose(scope())
            .subscribe { prefs.driveModeReadSms.set(it) }

        // Read all notifications switch
        readAllSwitch.checkedChanges()
            .skipInitialValue()
            .autoDispose(scope())
            .subscribe { prefs.driveModeReadAllNotifications.set(it) }

        // STT engine radio buttons
        sttRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.driveModeSttEngine.set(if (checkedId == R.id.sttRadioWhisper) "whisper" else "android")
        }

        googleInstallRow.clicks()
            .autoDispose(scope())
            .subscribe { showGoogleSttInstallDialog() }

        whisperDownloadRow.clicks()
            .autoDispose(scope())
            .subscribe { showWhisperDownloadDialog() }

        whisperFolderRow.clicks()
            .autoDispose(scope())
            .subscribe { openFolderPicker() }

        whisperModelRow.clicks()
            .autoDispose(scope())
            .subscribe { showWhisperModelPicker() }

        // TTS engine row
        ttsEngineRow.clicks()
            .autoDispose(scope())
            .subscribe { showTtsEnginePicker(prefs.driveModeTtsEngine.get()) }

        // Voice reply toggle
        voiceReplySwitch.checkedChanges()
            .skipInitialValue()
            .autoDispose(scope())
            .subscribe { prefs.driveModeVoiceReplyEnabled.set(it) }

        // Timeout row
        timeoutRow.clicks()
            .autoDispose(scope())
            .subscribe { showTimeoutPicker(prefs.driveModeReplyTimeoutSecs.get()) }
    }

    override fun render(state: AssistantState) {
        val enabled = state.driveModeEnabled

        // Card 1 — main toggle
        driveModeSwitch.isChecked = enabled
        btAutoRow.isVisible      = enabled
        readSmsSwitch.isChecked  = state.driveModeReadSms
        readAllRow.isVisible     = enabled
        readAllSwitch.isChecked  = state.driveModeReadAll

        // Cards 2–4 visible iff drive mode enabled
        sttCard.isVisible      = enabled
        ttsCard.isVisible      = enabled
        behaviorCard.isVisible = enabled

        if (enabled) {
            // STT engine radio + expandable sections
            val isWhisper = state.sttEngine == "whisper"
            val targetRadio = if (isWhisper) R.id.sttRadioWhisper else R.id.sttRadioGoogle
            if (sttRadioGroup.checkedRadioButtonId != targetRadio) sttRadioGroup.check(targetRadio)
            googleSection.isVisible  = !isWhisper
            whisperSection.isVisible = isWhisper
            if (isWhisper) {
                var displayDir = state.whisperModelsDir.ifBlank { defaultWhisperModelsDir }
                if (displayDir.startsWith("content://")) {
                    val uri = Uri.parse(displayDir)
                    val treeId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                    displayDir = treeId.substringAfterLast(":")
                } else {
                    displayDir = displayDir.substringAfterLast("/")
                }
                if (displayDir.isBlank()) displayDir = state.whisperModelsDir.ifBlank { defaultWhisperModelsDir }
                whisperFolderSummary.text = displayDir
                whisperModelSummary.text  = state.whisperModelName
            }

            // TTS engine
            ttsEngineSummary.text = state.ttsEngineSummary

            // Behavior
            voiceReplySwitch.isChecked = state.driveModeVoiceReplyEnabled
            timeoutRow.isVisible       = state.driveModeVoiceReplyEnabled
            timeoutSummary.text        = state.driveModeTimeoutSummary
        }
    }

    // ── Timeout picker ────────────────────────────────────────────────────────────

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

    // ── TTS engine picker ─────────────────────────────────────────────────────────

    override fun showTtsEnginePicker(current: String) {
        val labels = arrayOf("Android TTS (Offline)", "Piper TTS (Coming Soon)")
        val values = arrayOf("android", "piper")
        val currentIndex = values.indexOfFirst { it == current }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity!!)
            .setTitle(R.string.settings_drive_mode_tts_engine_title)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val selected = values[which]
                if (selected != "android") {
                    dialog.dismiss()
                    MaterialAlertDialogBuilder(activity!!)
                        .setTitle("Coming Soon")
                        .setMessage("${labels[which]} integration is coming in a future update.")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    prefs.driveModeTtsEngine.set(selected)
                    dialog.dismiss()
                }
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    // ── Google STT install dialog ─────────────────────────────────────────────────

    private fun showGoogleSttInstallDialog() {
        MaterialAlertDialogBuilder(activity!!)
            .setTitle("Google Offline Speech Recognition")
            .setMessage(
                "To use Google Speech Recognizer offline:\n\n" +
                "1. Tap \"Open Settings\" below\n" +
                "2. Find the Google app and open its settings\n" +
                "3. Go to Settings \u2192 Voice \u2192 Offline speech recognition\n" +
                "4. Download the language pack for your language"
            )
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:com.google.android.googlequicksearchbox")
                }
                try { startActivity(intent) }
                catch (e: Exception) { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ── Whisper download dialog ───────────────────────────────────────────────────

    private fun showWhisperDownloadDialog() {
        val ctx = activity ?: return
        fun dp(n: Int) = (n * ctx.resources.displayMetrics.density).toInt()

        data class ModelInfo(val name: String, val size: String, val filename: String)
        val modelList = listOf(
            ModelInfo("Tiny",   "~75 MB",  "ggml-tiny.bin"),
            ModelInfo("Base",   "~141 MB", "ggml-base.bin"),
            ModelInfo("Small",  "~488 MB", "ggml-small.bin"),
            ModelInfo("Medium", "~1.5 GB", "ggml-medium.bin"),
            ModelInfo("Large",  "~2.9 GB", "ggml-large-v3-turbo.bin")
        )

        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(ctx).apply {
            text = "Download .bin files from Hugging Face and place them in the \u201cModels folder\u201d. Tap a row to open its download page."
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
            setPadding(dp(20), dp(12), dp(20), dp(8))
        })

        modelList.forEach { info ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(20), dp(4), dp(16), dp(4))
                isClickable = true; isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(ctx).apply { text = info.name; textSize = 15f })
            texts.addView(TextView(ctx).apply { text = info.size; textSize = 13f; alpha = 0.6f })
            row.addView(texts)
            row.addView(TextView(ctx).apply {
                text = "Download \u2197"
                textSize = 13f
                setTextColor(ctx.getColor(android.R.color.holo_blue_dark))
                setPadding(dp(8), 0, 0, 0)
            })
            val url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${info.filename}"
            row.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            root.addView(row)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Download Whisper Models")
            .setView(ScrollView(ctx).apply { addView(root) })
            .setNegativeButton("Close", null)
            .show()
    }

    // ── Folder picker ─────────────────────────────────────────────────────────────

    private fun openFolderPicker() {
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            },
            RC_WHISPER_FOLDER
        )
    }

    // ── Whisper model picker ──────────────────────────────────────────────────────

    private fun showWhisperModelPicker() {
        val ctx = activity ?: return
        val modelsDir = prefs.driveModeWhisperModelsDir.get().ifBlank { defaultWhisperModelsDir }
        val models    = scanWhisperModels(modelsDir)
        if (models.isEmpty()) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.assistant_whisper_model_title)
                .setMessage("No model files (.bin) found in target folder.\n\nUse \"Download models\" to get files, then set the correct \"Models folder\".")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        var currentPath = prefs.driveModeWhisperModelPath.get()
        if (models.none { it.absolutePath == currentPath }) currentPath = models[0].absolutePath
        val labels = models.map { file ->
            "${file.name.removeSuffix(".bin").removePrefix("ggml-").replaceFirstChar { it.uppercase() }}  \u00b7  ${formatModelSize(file.size)}"
        }.toTypedArray()
        val currentIndex = models.indexOfFirst { it.absolutePath == currentPath }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.assistant_whisper_model_title)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                prefs.driveModeWhisperModelPath.set(models[which].absolutePath)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_WHISPER_FOLDER && resultCode == android.app.Activity.RESULT_OK) {
            val treeUri = data?.data ?: return
            
            try {
                activity?.contentResolver?.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) { /* ignore */ }

            prefs.driveModeWhisperModelsDir.set(treeUri.toString())
        }
    }

    // ── Notification access ───────────────────────────────────────────────────────

    private fun checkNotificationAccess() {
        val ctx = activity ?: return
        val granted = androidx.core.app.NotificationManagerCompat
            .getEnabledListenerPackages(ctx)
            .contains(ctx.packageName)
        if (!granted) showNotificationAccessDialog()
    }

    private fun showNotificationAccessDialog() {
        MaterialAlertDialogBuilder(activity!!)
            .setTitle("Drive Mode: Permission Required")
            .setMessage(
                "Drive Mode reads your incoming messages aloud so you can stay hands-free while driving.\n\n" +
                "To work, it needs \"Notification Access\" \u2014 a system permission that lets SilentPulse see notifications.\n\n" +
                "Tap \"Grant Access\" and enable SilentPulse in the list."
            )
            .setPositiveButton("Grant Access") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("Not Now", null)
            .show()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private data class WhisperModelInfo(val name: String, val size: Long, val absolutePath: String)

    private fun scanWhisperModels(dir: String): List<WhisperModelInfo> {
        val ctx = activity ?: return emptyList()
        val isTreeUri = dir.startsWith("content://")
        
        if (isTreeUri) {
            val uri = Uri.parse(dir)
            val docDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, uri) ?: return emptyList()
            if (!docDir.isDirectory) return emptyList()
            
            val treeId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            val parts = treeId.split(":")
            val resolvedDir = if (parts[0].lowercase() == "primary") {
                "/sdcard/${parts[1]}"
            } else null
            
            if (resolvedDir == null) return emptyList()

            return docDir.listFiles()
                .filter { it.isFile && it.name?.endsWith(".bin") == true }
                .map { WhisperModelInfo(it.name ?: "", it.length(), "$resolvedDir/${it.name}") }
                .sortedBy { it.name }
        }

        return java.io.File(dir)
            .takeIf { it.isDirectory }
            ?.listFiles { f -> f.isFile && f.name.endsWith(".bin") }
            ?.map { WhisperModelInfo(it.name, it.length(), it.absolutePath) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun formatModelSize(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L     -> "%.0f MB".format(bytes / 1_000_000.0)
        else                    -> "%.0f KB".format(bytes / 1_000.0)
    }

    // App-private external dir — readable without any storage permission on Android 10+
    private val defaultWhisperModelsDir: String
        get() = context.getExternalFilesDir(null)?.absolutePath?.let { "$it/whisper" }
            ?: "/sdcard/Android/data/com.silentpulse.messenger/files/whisper"

    companion object {
        private const val RC_WHISPER_FOLDER = 1002
    }
}
