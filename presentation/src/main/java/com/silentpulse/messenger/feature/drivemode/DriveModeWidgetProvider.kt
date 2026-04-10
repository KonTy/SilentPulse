package com.silentpulse.messenger.feature.drivemode

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.silentpulse.messenger.R
import timber.log.Timber

/**
 * Home-screen widget for Drive Mode: ON / OFF / STOP buttons.
 *
 * - **ON**  — enables Drive Mode (writes shared pref, updates UI).
 * - **OFF** — disables Drive Mode, stops TTS + STT.
 * - **STOP** — immediately silences TTS and cancels voice command flow.
 *
 * Communicates with [SilentPulseNotificationListener] via its static
 * [sInstance] reference — no network, no IPC, just in-process calls.
 */
class DriveModeWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_DRIVE_ON  = "com.silentpulse.messenger.DRIVE_MODE_ON"
        const val ACTION_DRIVE_OFF = "com.silentpulse.messenger.DRIVE_MODE_OFF"
        const val ACTION_DRIVE_STOP = "com.silentpulse.messenger.DRIVE_MODE_STOP"

        /** Poke all widget instances so they refresh their ON/OFF label. */
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, DriveModeWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) {
                val intent = Intent(context, DriveModeWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_DRIVE_ON -> {
                Timber.d("DriveModeWidget: ON pressed")
                setDriveModeEnabled(context, true)
                refreshAll(context)
            }
            ACTION_DRIVE_OFF -> {
                Timber.d("DriveModeWidget: OFF pressed")
                setDriveModeEnabled(context, false)
                // Stop any ongoing TTS/STT
                SilentPulseNotificationListener.sInstance?.stopReading()
                refreshAll(context)
            }
            ACTION_DRIVE_STOP -> {
                Timber.d("DriveModeWidget: STOP pressed")
                SilentPulseNotificationListener.sInstance?.stopReading()
                // Don't change Drive Mode enabled state — just silence current speech
            }
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_drive_mode)
        val enabled = isDriveModeEnabled(context)

        // Status text
        views.setTextViewText(
            R.id.widget_status,
            if (enabled) "ON" else "OFF"
        )
        views.setTextColor(
            R.id.widget_status,
            if (enabled) 0xFF4CAF50.toInt() else 0xFF888888.toInt()
        )

        // ON button
        views.setOnClickPendingIntent(
            R.id.btn_on,
            makePendingIntent(context, ACTION_DRIVE_ON)
        )
        // OFF button
        views.setOnClickPendingIntent(
            R.id.btn_off,
            makePendingIntent(context, ACTION_DRIVE_OFF)
        )
        // STOP button
        views.setOnClickPendingIntent(
            R.id.btn_stop,
            makePendingIntent(context, ACTION_DRIVE_STOP)
        )

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun makePendingIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, DriveModeWidgetProvider::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun isDriveModeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(
            "${context.packageName}_preferences", Context.MODE_PRIVATE
        ).getBoolean("drive_mode_enabled", false)
    }

    private fun setDriveModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(
            "${context.packageName}_preferences", Context.MODE_PRIVATE
        ).edit().putBoolean("drive_mode_enabled", enabled).apply()
        Timber.d("DriveModeWidget: drive_mode_enabled = $enabled")
    }
}
