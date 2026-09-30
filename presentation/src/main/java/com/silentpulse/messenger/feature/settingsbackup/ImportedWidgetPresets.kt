package com.silentpulse.messenger.feature.settingsbackup

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.silentpulse.messenger.R
import com.silentpulse.messenger.feature.stocks.StockWidgetSettings
import com.squareup.moshi.JsonDataException
import timber.log.Timber
import java.io.IOException

object ImportedWidgetPresets {
    const val MENU_ID = 0x5342

    fun chooseStock(context: Context, selected: (StockWidgetSettings) -> Unit) {
        choose(context, { it.stocks.orEmpty() }, { index, settings ->
            context.getString(R.string.settings_backup_preset_stock, index + 1, settings.symbols.joinToString(", "))
        }, selected)
    }

    fun chooseClock(context: Context, selected: (ClockPreset) -> Unit) {
        choose(context, { it.clocks.orEmpty() }, { index, clock ->
            context.getString(R.string.settings_backup_preset_clock, index + 1, clock.city, clock.zone)
        }, selected)
    }

    private fun <T> choose(
        context: Context,
        entries: (SettingsSnapshot) -> List<T>,
        label: (Int, T) -> String,
        selected: (T) -> Unit
    ) {
        val presets = try {
            val raw = AndroidSettingsStore(context).read(SettingsPolicy.PRESETS)[SettingsPolicy.PRESET_KEY] as? String
            entries(raw?.let(SettingsCodec::decode) ?: SettingsSnapshot())
        } catch (_: IOException) {
            return reportFailure(context)
        } catch (_: JsonDataException) {
            return reportFailure(context)
        } catch (_: IllegalArgumentException) {
            return reportFailure(context)
        } catch (_: IllegalStateException) {
            return reportFailure(context)
        } catch (_: ClassCastException) {
            return reportFailure(context)
        } catch (_: SecurityException) {
            return reportFailure(context)
        }
        if (presets.isEmpty()) {
            Toast.makeText(context, R.string.settings_backup_no_presets, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(context).setTitle(R.string.settings_backup_presets)
            .setItems(presets.mapIndexed(label).toTypedArray()) { _, index ->
                selected(presets[index])
                Toast.makeText(context, R.string.settings_backup_preset_loaded, Toast.LENGTH_LONG).show()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun reportFailure(context: Context) {
        Timber.w("Imported widget presets could not be read")
        Toast.makeText(context, R.string.settings_backup_failed, Toast.LENGTH_LONG).show()
    }
}
