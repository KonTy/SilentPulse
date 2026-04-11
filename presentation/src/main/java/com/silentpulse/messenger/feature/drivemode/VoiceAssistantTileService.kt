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
import com.silentpulse.messenger.feature.assistant.VoiceAssistantService
import timber.log.Timber

/**
 * Quick Settings tile — Voice Assistant on/off.
 *
 * Tapping toggles [WidgetPrefs.KEY_VOICE_AST], starts or stops
 * [VoiceAssistantService], and broadcasts [WidgetPrefs.ACTION_STATE_CHANGED]
 * so the AppWidget and other tiles refresh.
 *
 * Requires API 24 (Android 7.0).
 */
@RequiresApi(Build.VERSION_CODES.N)
class VoiceAssistantTileService : TileService() {

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
        val nowEnabled = !WidgetPrefs.isVoiceAstEnabled(this)
        WidgetPrefs.setVoiceAst(this, nowEnabled)
        Timber.d("VoiceAstTile: toggled → $nowEnabled")

        val svc = Intent(this, VoiceAssistantService::class.java)
        if (nowEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
        } else {
            stopService(svc)
        }

        WidgetPrefs.broadcastStateChanged(this)
        DriveModeWidgetProvider.refreshAll(this)
        updateTile()
    }

    private fun updateTile() {
        val tile    = qsTile ?: return
        val enabled = WidgetPrefs.isVoiceAstEnabled(this)

        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon  = Icon.createWithResource(
            this,
            if (enabled) R.drawable.ic_mic_black_24dp
            else         R.drawable.ic_mic_off_black_24dp
        )
        tile.label = getString(R.string.tile_voice_ast)
        tile.updateTile()
    }
}
