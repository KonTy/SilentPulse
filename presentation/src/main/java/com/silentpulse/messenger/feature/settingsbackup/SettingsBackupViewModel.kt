package com.silentpulse.messenger.feature.settingsbackup

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silentpulse.messenger.R
import com.silentpulse.messenger.feature.worldclock.WorldClockWidgetProvider
import com.silentpulse.messenger.manager.WidgetManager
import com.silentpulse.messenger.util.NightModeManager
import com.squareup.moshi.JsonDataException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

data class SettingsBackupState(
    val busy: Boolean = false,
    val message: Int? = null,
    val pending: SettingsSnapshot? = null
)

class SettingsBackupViewModel(
    private val context: Context,
    private val nightModeManager: NightModeManager,
    private val widgetManager: WidgetManager
) : ViewModel() {
    val state = MutableLiveData(SettingsBackupState())
    private val repository = SettingsBackupRepository(
        AndroidSettingsStore(context), PrivateSettingsRollback(context), AndroidSettingsStore.defaults()
    )

    fun export(uri: Uri) = runOperation {
        withContext(Dispatchers.IO) {
            require(uri.scheme == "content")
            val json = SettingsCodec.encode(repository.export())
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use {
                it.write(json.toByteArray(Charsets.UTF_8))
                it.flush()
            }
            // Do not report success for a provider that failed to persist the selected document.
            val saved = requireNotNull(context.contentResolver.openInputStream(uri)).use { readSettingsText(it) }
            check(SettingsCodec.decode(saved) == SettingsCodec.decode(json))
        }
        SettingsBackupState(message = R.string.settings_backup_exported)
    }

    fun inspect(uri: Uri) = runOperation {
        val snapshot = withContext(Dispatchers.IO) {
            require(uri.scheme == "content")
            val json = requireNotNull(context.contentResolver.openInputStream(uri)).use { readSettingsText(it) }
            SettingsCodec.decode(json)
        }
        SettingsBackupState(pending = snapshot)
    }

    fun cancelImport() { state.value = SettingsBackupState() }

    fun documentSelectionFailed() {
        Timber.w("Settings document picker returned no document")
        state.value = SettingsBackupState(message = R.string.settings_backup_failed)
    }

    fun importConfirmed() {
        val snapshot = state.value?.pending ?: return
        applySettings { repository.import(snapshot) }
    }

    fun undoConfirmed() = applySettings { repository.undo() }

    private fun applySettings(write: () -> Boolean) = runOperation {
        // A themed activity can be destroyed while preferences notify observers. Never bind writes
        // or rollback to that activity's lifecycle; complete persistence before presentation updates.
        withContext(NonCancellable) {
            val changed = withContext(Dispatchers.IO) { write() }
            if (!changed) return@withContext SettingsBackupState(message = R.string.settings_backup_unchanged)
            try {
                nightModeManager.updateCurrentTheme()
                widgetManager.updateTheme()
                AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(ComponentName(context, WorldClockWidgetProvider::class.java))
                    .forEach { WorldClockWidgetProvider.updateWidget(context, it) }
                SettingsBackupState(message = R.string.settings_backup_imported)
            } catch (_: RuntimeException) {
                Timber.w("Saved settings could not refresh the UI")
                SettingsBackupState(message = R.string.settings_backup_refresh_failed)
            }
        }
    }

    private fun runOperation(block: suspend () -> SettingsBackupState) {
        if (state.value?.busy == true) return
        state.value = SettingsBackupState(busy = true)
        viewModelScope.launch {
            state.value = try {
                block()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: SettingsImportException) {
                Timber.w("Settings import failed; rollback verified: %s", failure.rollbackSucceeded)
                SettingsBackupState(message = if (failure.rollbackSucceeded)
                    R.string.settings_backup_rolled_back else R.string.settings_backup_rollback_failed)
            } catch (_: IOException) {
                failedOperation()
            } catch (_: JsonDataException) {
                failedOperation()
            } catch (_: IllegalArgumentException) {
                failedOperation()
            } catch (_: IllegalStateException) {
                failedOperation()
            } catch (_: ClassCastException) {
                failedOperation()
            } catch (_: SecurityException) {
                failedOperation()
            }
        }
    }

    private fun failedOperation(): SettingsBackupState {
        Timber.w("Settings backup operation failed")
        return SettingsBackupState(message = R.string.settings_backup_failed)
    }
}
