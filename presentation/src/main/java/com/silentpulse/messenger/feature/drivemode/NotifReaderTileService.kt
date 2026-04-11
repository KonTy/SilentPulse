package com.silentpulse.messenger.feature.drivemode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.silentpulse.messenger.R
import timber.log.Timber

/**
 * Quick Settings tile — Notification Reader on/off.
 *
 * Appears in the notification shade; tapping toggles [WidgetPrefs.KEY_NOTIF_READER].
 * Stays in sync with the AppWidget and in-app settings via
 * [WidgetPrefs.ACTION_STATE_CHANGED] broadcast.
 *
 * Requires API 24 (Android 7.0). The manifest entry targets N+, so this
 * class is never loaded on older devices.
 */
@RequiresApi(Build.VERSION_CODES.N)
class NotifReaderTileService : TileService() {

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WidgetPrefs.ACTION_STATE_CHANGED) updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        registerReceiver(stateReceiver, IntentFilter(WidgetPrefs.ACTION_STATE_CHANGED))
        updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
    }

    override fun onClick() {
        super.onClick()
        val nowEnabled = !WidgetPrefs.isNotifReaderEnabled(this)
        WidgetPrefs.setNotifReader(this, nowEnabled)
        Timber.d("NotifReaderTile: toggled → $nowEnabled")

        if (nowEnabled) {
            DriveModeMicService.start(this)
        } else {
            SilentPulseNotificationListener.sInstance?.stopReading()
            DriveModeMicService.stop(this)
        }

        // Notify the AppWidget and other QS tiles
        WidgetPrefs.broadcastStateChanged(this)
        DriveModeWidgetProvider.refreshAll(this)
        updateTile()
    }

    private fun updateTile() {
        val tile    = qsTile ?: return
        val enabled = WidgetPrefs.isNotifReaderEnabled(this)

        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon  = Icon.createWithResource(
            this,
            if (enabled) R.drawable.ic_notifications_black_24dp
            else         R.drawable.ic_notifications_off_black_24dp
        )
        tile.label = getString(R.string.tile_notif_reader)
        tile.updateTile()
    }
}
