package com.silentpulse.messenger.feature.settingsbackup

import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.silentpulse.messenger.R
import com.silentpulse.messenger.common.base.QkThemedActivity
import com.silentpulse.messenger.manager.WidgetManager
import com.silentpulse.messenger.util.NightModeManager
import dagger.android.AndroidInjection
import javax.inject.Inject
import timber.log.Timber

class SettingsBackupActivity : QkThemedActivity() {
    @Inject lateinit var nightModeManager: NightModeManager
    @Inject lateinit var widgetManager: WidgetManager
    private lateinit var model: SettingsBackupViewModel
    private var confirmation: AlertDialog? = null
    private var deferredRecreation = false

    private val exportFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            val uri = it.data?.data
            if (uri == null) model.documentSelectionFailed() else model.export(uri)
        }
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            val uri = it.data?.data
            if (uri == null) model.documentSelectionFailed() else model.inspect(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == SettingsBackupViewModel::class.java)
                return modelClass.cast(SettingsBackupViewModel(applicationContext, nightModeManager, widgetManager))
            }
        })[SettingsBackupViewModel::class.java]
        setContentView(R.layout.settings_backup_activity)
        title = getString(R.string.settings_backup_title)
        showBackButton(true)
        val export = findViewById<Button>(R.id.settings_backup_export)
        val import = findViewById<Button>(R.id.settings_backup_import)
        val undo = findViewById<Button>(R.id.settings_backup_undo)
        export.setOnClickListener {
            launchPicker {
                exportFile.launch(documentIntent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    putExtra(Intent.EXTRA_TITLE, "silentpulse-settings.json")
                })
            }
        }
        import.setOnClickListener {
            launchPicker { importFile.launch(documentIntent(Intent.ACTION_OPEN_DOCUMENT)) }
        }
        undo.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_backup_undo)
                .setMessage(R.string.settings_backup_undo_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_backup_restore) { _, _ -> model.undoConfirmed() }.show()
        }
        model.state.observe(this) { state ->
            export.isEnabled = !state.busy
            import.isEnabled = !state.busy
            undo.isEnabled = !state.busy
            findViewById<TextView>(R.id.settings_backup_status).setText(
                state.message ?: if (state.busy) R.string.settings_backup_working else R.string.settings_backup_ready
            )
            if (state.pending != null && confirmation == null) {
                confirmation = AlertDialog.Builder(this)
                    .setTitle(R.string.settings_backup_confirm_title)
                    .setMessage(getString(
                        R.string.settings_backup_confirm,
                        state.pending.stores.values.sumOf { it.size },
                        state.pending.stocks.orEmpty().size,
                        state.pending.clocks.orEmpty().size
                    ))
                    .setNegativeButton(android.R.string.cancel) { _, _ -> model.cancelImport() }
                    .setOnCancelListener { model.cancelImport() }
                    .setPositiveButton(R.string.settings_backup_restore) { _, _ -> model.importConfirmed() }
                    .create().also { dialog ->
                        dialog.setOnDismissListener { confirmation = null }
                        dialog.show()
                    }
            }
            if (!state.busy && deferredRecreation) {
                deferredRecreation = false
                super.recreate()
            }
        }
    }

    override fun recreate() {
        if (::model.isInitialized && model.state.value?.busy == true) deferredRecreation = true
        else super.recreate()
    }

    private fun launchPicker(launch: () -> Unit) {
        try {
            launch()
        } catch (_: ActivityNotFoundException) {
            Timber.w("No settings document picker available")
            findViewById<TextView>(R.id.settings_backup_status).setText(R.string.settings_backup_picker_failed)
        } catch (_: SecurityException) {
            Timber.w("Settings document picker access denied")
            findViewById<TextView>(R.id.settings_backup_status).setText(R.string.settings_backup_failed)
        }
    }

    override fun onDestroy() {
        confirmation?.dismiss()
        confirmation = null
        super.onDestroy()
    }

    companion object {
        fun documentIntent(action: String): Intent = Intent(action).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
    }
}
