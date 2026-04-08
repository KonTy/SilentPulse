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
import com.silentpulse.messenger.feature.drivemode.DriveModeMicService
import com.silentpulse.messenger.common.base.QkController
import com.silentpulse.messenger.injection.appComponent
import com.silentpulse.messenger.util.Preferences
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDispose
import java.io.File
import javax.inject.Inject

class AssistantController : QkController<AssistantView, AssistantState, AssistantPresenter>(), AssistantView {

    // ── Toolbar ──────────────────────────────────────────────────────────────────

    // ── Card 1 – Enable ─────────────────────────────────────────────────────────
    private val driveModeSwitch: SwitchCompat     get() = rootView!!.findViewById(R.id.driveModeSwitch)
    private val btAutoRow: LinearLayout            get() = rootView!!.findViewById(R.id.btAutoRow)
    private val readSmsSwitch: SwitchCompat        get() = rootView!!.findViewById(R.id.readSmsSwitch)
    private val readAllRow: LinearLayout           get() = rootView!!.findViewById(R.id.readAllRow)
    private val readAllSwitch: SwitchCompat        get() = rootView!!.findViewById(R.id.readAllSwitch)

    // ── Card 2 – STT ─────────────────────────────────────────────────────────────
    private val sttCard: MaterialCardView           get() = rootView!!.findViewById(R.id.sttCard)
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
    private val wakeWordSwitch: SwitchCompat      get() = rootView!!.findViewById(R.id.wakeWordSwitch)
    private val wakeWordLabel: TextView              get() = rootView!!.findViewById(R.id.wakeWordLabel)
    private val voiceReplySwitch: SwitchCompat      get() = rootView!!.findViewById(R.id.voiceReplySwitch)
    private val timeoutRow: LinearLayout            get() = rootView!!.findViewById(R.id.timeoutRow)
    private val timeoutSummary: TextView            get() = rootView!!.findViewById(R.id.timeoutSummary)
    private val retryRow: LinearLayout              get() = rootView!!.findViewById(R.id.retryRow)
    private val retrySummary: TextView              get() = rootView!!.findViewById(R.id.retrySummary)
    private val announcementsRow: LinearLayout       get() = rootView!!.findViewById(R.id.announcementsRow)
    private val announcementsSummary: TextView       get() = rootView!!.findViewById(R.id.announcementsSummary)

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

        // Check model file access every time the screen opens
        checkModelFileAccess()

        // Drive mode master toggle
        driveModeSwitch.checkedChanges()
            .skipInitialValue()
            .autoDispose(scope())
            .subscribe { checked ->
                prefs.driveModeEnabled.set(checked)
                if (checked) {
                    checkNotificationAccess()
                    // Start the persistent foreground mic service from this
                    // Activity context (foreground) so Android allows the
                    // FOREGROUND_SERVICE_TYPE_MICROPHONE transition.
                    DriveModeMicService.start(context)
                } else {
                    DriveModeMicService.stop(context)
                }
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

        // STT: Whisper is the only engine
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

        // Wake word toggle
        wakeWordSwitch.checkedChanges()
            .skipInitialValue()
            .autoDispose(scope())
            .subscribe { enabled -> 
                if (enabled) {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        activity?.let { androidx.core.app.ActivityCompat.requestPermissions(it, arrayOf(android.Manifest.permission.RECORD_AUDIO), 444) }
                        wakeWordSwitch.isChecked = false
                        return@subscribe
                    }
                    prefs.driveModeWakeWordEnabled.set(true)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) { context.startForegroundService(Intent(context, VoiceAssistantService::class.java)) } else { context.startService(Intent(context, VoiceAssistantService::class.java)) }
                } else {
                    prefs.driveModeWakeWordEnabled.set(false)
                    context.stopService(Intent(context, VoiceAssistantService::class.java))
                }
            }

        // Voice reply toggle
        voiceReplySwitch.checkedChanges()
            .skipInitialValue()
            .autoDispose(scope())
            .subscribe { prefs.driveModeVoiceReplyEnabled.set(it) }

        // Timeout row
        timeoutRow.clicks()
            .autoDispose(scope())
            .subscribe { showTimeoutPicker(prefs.driveModeReplyTimeoutSecs.get()) }

        // Retry limit row
        retryRow.clicks()
            .autoDispose(scope())
            .subscribe { showRetryLimitPicker(prefs.driveModeMaxSttRetries.get()) }

        // Max announcements row
        announcementsRow.clicks()
            .autoDispose(scope())
            .subscribe { showAnnouncementsLimitPicker(prefs.driveModeMaxAnnouncements.get()) }
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
            // STT: always Whisper
            whisperSection.isVisible = true
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

            // TTS engine
            ttsEngineSummary.text = state.ttsEngineSummary

            // Behavior
            wakeWordLabel.text = "Wait for keyword '${VoiceAssistantService.WAKE_WORD}'"
            wakeWordSwitch.isChecked = state.driveModeWakeWordEnabled

            voiceReplySwitch.isChecked = state.driveModeVoiceReplyEnabled
            timeoutRow.isVisible       = state.driveModeVoiceReplyEnabled
            timeoutSummary.text        = state.driveModeTimeoutSummary
            retryRow.isVisible         = state.driveModeVoiceReplyEnabled
            retrySummary.text          = state.driveModeMaxRetriesSummary

            // Max announcements (always visible when drive mode is on)
            val maxAnn = prefs.driveModeMaxAnnouncements.get()
            announcementsSummary.text = if (maxAnn == 1) "1 time" else "$maxAnn times"
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


    // ── STT retry limit picker ──────────────────────────────────────────────────

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
    // ── Max announcements per message picker ──────────────────────────────────

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

    // ── TTS engine picker ─────────────────────────────────────────────────────────

    override fun showTtsEnginePicker(current: String) {
        val labels = arrayOf("Android TTS (Offline)", "Kokoro TTS (Expressive AI)")
        val values = arrayOf("android", "kokoro")
        val currentIndex = values.indexOfFirst { it == current }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity!!)
            .setTitle(R.string.settings_drive_mode_tts_engine_title)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                prefs.driveModeTtsEngine.set(values[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    // ── Google STT install dialog ─────────────────────────────────────────────────


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

    // App-private external dir for Kokoro TTS model
    private val defaultKokoroModelDir: String
        get() = context.getExternalFilesDir(null)?.absolutePath?.let { "$it/kokoro-en-v0_19" }
            ?: "/sdcard/Android/data/com.silentpulse.messenger/files/kokoro-en-v0_19"

    // ── File access check ─────────────────────────────────────────────────────

    /**
     * Checks all required model files are present and accessible.
     * Shows an informative dialog listing any missing models so the user
     * can fix the problem before enabling Drive Mode features.
     */
    private fun checkModelFileAccess() {
        val issues = mutableListOf<String>()

        // 1. Whisper STT model
        val whisperPath = prefs.driveModeWhisperModelPath.get()
        if (whisperPath.isBlank()) {
            // Try to auto-detect in default dir
            val defaultDir = File(defaultWhisperModelsDir)
            val found = defaultDir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }
                ?.maxByOrNull { it.length() }
            if (found != null && found.canRead()) {
                prefs.driveModeWhisperModelPath.set(found.absolutePath)
            } else {
                issues.add("\u2022 Whisper STT model (.bin) not found.\n" +
                    "  Place a model file in:\n  $defaultWhisperModelsDir/")
            }
        } else {
            val whisperFile = File(whisperPath)
            if (!whisperFile.exists()) {
                // Maybe the file was at an old location — try default dir
                val defaultDir = File(defaultWhisperModelsDir)
                val found = defaultDir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }
                    ?.maxByOrNull { it.length() }
                if (found != null && found.canRead()) {
                    prefs.driveModeWhisperModelPath.set(found.absolutePath)
                } else {
                    issues.add("\u2022 Whisper STT model not found at:\n  $whisperPath\n" +
                        "  Place a model file in:\n  $defaultWhisperModelsDir/")
                }
            } else if (!whisperFile.canRead()) {
                issues.add("\u2022 Whisper STT model cannot be read (permission denied):\n  $whisperPath\n" +
                    "  Move it to app-private storage:\n  $defaultWhisperModelsDir/")
            }
        }

        // 2. Kokoro TTS model (only check if user selected Kokoro engine)
        val ttsEngine = prefs.driveModeTtsEngine.get()
        if (ttsEngine == "kokoro") {
            val kokoroDir = prefs.driveModeKokoroModelDir.get()
            if (kokoroDir.isBlank()) {
                // Try auto-detect default location
                val defaultDir = File(defaultKokoroModelDir)
                if (defaultDir.isDirectory && File(defaultDir, "model.onnx").canRead()) {
                    prefs.driveModeKokoroModelDir.set(defaultDir.absolutePath)
                } else {
                    issues.add("\u2022 Kokoro TTS model not found.\n" +
                        "  Place model files in:\n  $defaultKokoroModelDir/\n" +
                        "  (model.onnx, voices.bin, tokens.txt, espeak-ng-data/)")
                }
            } else {
                val kokoroModel = File(kokoroDir, "model.onnx")
                if (!File(kokoroDir).isDirectory) {
                    issues.add("\u2022 Kokoro TTS model directory missing:\n  $kokoroDir\n" +
                        "  Move model to:\n  $defaultKokoroModelDir/")
                } else if (!kokoroModel.exists()) {
                    issues.add("\u2022 Kokoro TTS model.onnx missing in:\n  $kokoroDir")
                } else if (!kokoroModel.canRead()) {
                    issues.add("\u2022 Kokoro TTS model cannot be read (permission denied):\n  $kokoroDir\n" +
                        "  Move model to:\n  $defaultKokoroModelDir/")
                } else {
                    // Check all required sub-files
                    val missing = listOf("voices.bin", "tokens.txt").filter {
                        !File(kokoroDir, it).canRead()
                    }
                    if (!File(kokoroDir, "espeak-ng-data").isDirectory) {
                        issues.add("\u2022 Kokoro TTS espeak-ng-data/ directory missing in:\n  $kokoroDir")
                    }
                    if (missing.isNotEmpty()) {
                        issues.add("\u2022 Kokoro TTS missing files in $kokoroDir:\n  ${missing.joinToString(", ")}")
                    }
                }
            }
        }

        // 3. Whisper models dir (for the picker)
        val modelsDir = prefs.driveModeWhisperModelsDir.get()
        if (modelsDir.isBlank()) {
            // Auto-set to default
            val defaultDir = File(defaultWhisperModelsDir)
            if (defaultDir.isDirectory) {
                prefs.driveModeWhisperModelsDir.set(defaultDir.absolutePath)
            }
        }

        if (issues.isNotEmpty()) {
            showModelFileIssuesDialog(issues)
        }
    }

    private fun showModelFileIssuesDialog(issues: List<String>) {
        val ctx = activity ?: return
        val message = "Some model files needed for Drive Mode are missing or inaccessible.\n\n" +
            issues.joinToString("\n\n") +
            "\n\nModel files should be placed in the app\u2019s private storage directory " +
            "(no special permissions needed):\n" +
            context.getExternalFilesDir(null)?.absolutePath + "/"
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Model Files Check")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    companion object {
        private const val RC_WHISPER_FOLDER = 1002
    }
}
