package com.silentpulse.messenger.feature.drivemode

import android.app.ActivityManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.RemoteViews
import com.silentpulse.messenger.R
import com.silentpulse.messenger.feature.assistant.VoiceAssistantService
import timber.log.Timber

/**
 * 3×1 home-screen AppWidget — icons only, no text labels.
 *
 *  Slot 1  [btn_notif_reader]  Toggle Notification Reader on/off.
 *                              Icon: ic_notifications_black_24dp (on)
 *                                  / ic_notifications_off_black_24dp (off)
 *
 *  Slot 2  [btn_next_notif]    Jump to next notification in the reader queue.
 *                              Icon: ic_skip_next_black_24dp
 *                              Alpha: 1.0 when reader is on, 0.35 when off.
 *
 *  Slot 3  [btn_voice_ast]     Toggle Voice Assistant on/off.
 *                              Icon: ic_mic_black_24dp (on)
 *                                  / ic_mic_off_black_24dp (off)
 *
 * State is stored via [WidgetPrefs].  After every tap the widget broadcasts
 * [WidgetPrefs.ACTION_STATE_CHANGED] so Quick Settings tiles refresh too.
 */
class DriveModeWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val ACTION_TOGGLE_NOTIF_READER =
            "com.silentpulse.messenger.TOGGLE_NOTIF_READER"
        private const val ACTION_NEXT_NOTIF =
            "com.silentpulse.messenger.NEXT_NOTIFICATION"
        private const val ACTION_TOGGLE_VOICE_AST =
            "com.silentpulse.messenger.TOGGLE_VOICE_AST"
        private const val ACTION_STOP_SPEAKING =
            WidgetPrefs.ACTION_STOP_SPEAKING

        /** Push all live widget instances to re-draw from current prefs. */
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, DriveModeWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) {
                context.sendBroadcast(
                    Intent(context, DriveModeWidgetProvider::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                )
            }
        }
    }

    // ── AppWidgetProvider callbacks ───────────────────────────────────────────

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Timber.d("DriveModeWidget onReceive: ${intent.action}")
        when (intent.action) {
            ACTION_TOGGLE_NOTIF_READER       -> handleToggleNotifReader(context)
            ACTION_NEXT_NOTIF                -> handleNextNotif(context)
            ACTION_TOGGLE_VOICE_AST          -> handleToggleVoiceAst(context)
            ACTION_STOP_SPEAKING             -> handleStopSpeaking(context)
            // QS tile or in-app toggle changed state — redraw the widget too
            WidgetPrefs.ACTION_STATE_CHANGED -> refreshAll(context)
        }
    }

    // ── Action handlers ───────────────────────────────────────────────────────

    private fun handleToggleNotifReader(context: Context) {
        val nowEnabled = !WidgetPrefs.isNotifReaderEnabled(context)
        WidgetPrefs.setNotifReader(context, nowEnabled)
        Timber.d("DriveModeWidget: notif reader → $nowEnabled")
        if (nowEnabled) {
            DriveModeMicService.start(context)
            speakOnce(context, "Notification reader on.")
        } else {
            SilentPulseNotificationListener.sInstance?.stopReading()
            DriveModeMicService.stop(context)
            speakOnce(context, "Notification reader off.")
        }
        notifyAndRefresh(context)
    }

    private fun handleNextNotif(context: Context) {
        Timber.d("DriveModeWidget: next notification")
        context.sendBroadcast(
            Intent(WidgetPrefs.ACTION_NEXT_NOTIFICATION).apply { setPackage(context.packageName) }
        )
    }

    private fun handleToggleVoiceAst(context: Context) {
        val nowEnabled = !WidgetPrefs.isVoiceAstEnabled(context)
        WidgetPrefs.setVoiceAst(context, nowEnabled)
        Timber.d("DriveModeWidget: voice ast → $nowEnabled")
        val svc = Intent(context, VoiceAssistantService::class.java)
        if (nowEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(svc)
            else context.startService(svc)
            speakOnce(context, "Voice assistant on.")
        } else {
            context.stopService(svc)
            speakOnce(context, "Voice assistant off.")
        }
        notifyAndRefresh(context)
    }

    private fun handleStopSpeaking(context: Context) {
        Timber.d("DriveModeWidget: stop speaking")
        // Stop notification listener TTS/STT if it's active
        SilentPulseNotificationListener.sInstance?.stopReading()
        // Stop VoiceAssistantService TTS (receiver also calls resumeWakeWord)
        context.sendBroadcast(
            Intent(WidgetPrefs.ACTION_STOP_SPEAKING).apply { setPackage(context.packageName) }
        )
    }

    private fun notifyAndRefresh(context: Context) {
        WidgetPrefs.broadcastStateChanged(context)
        refreshAll(context)
    }

    /**
     * Returns true if [serviceClass] has a running foreground service in this
     * package's process.  Using ActivityManager is the most reliable check —
     * prefs can be stale after a SIGKILL (e.g. adb install).
     *
     * ActivityManager.getRunningServices() is deprecated at API 26 but still
     * works for checking the caller's own services on all Android versions.
     */
    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int
    ) {
        // Always derive state from process reality, not from prefs that may be
        // stale after a force-kill (e.g. adb install doesn't call onDestroy).
        val notifOn = isServiceRunning(context, DriveModeMicService::class.java)
        val voiceOn = isServiceRunning(context, VoiceAssistantService::class.java)
        // Correct the notif-reader pref if stale (safe — not observed reactively by any component).
        // Do NOT correct the voiceAst pref here: it is bound to a reactive SharedPreference
        // observable in AssistantPresenter. Writing false would cascade through AssistantController's
        // wakeWordSwitch observer and call stopService(VoiceAssistantService) — killing the assistant
        // just because the widget happened to redraw while DriveModeMicService was stopping.
        if (notifOn != WidgetPrefs.isNotifReaderEnabled(context)) WidgetPrefs.setNotifReader(context, notifOn)
        val views   = RemoteViews(context.packageName, R.layout.widget_drive_mode)

        // Tint icons white on dark launcher backgrounds, black on light ones
        val isNight = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val iconColor = if (isNight) Color.WHITE else Color.BLACK

        // Slot 1 — Notification Reader
        views.setImageViewResource(
            R.id.btn_notif_reader,
            if (notifOn) R.drawable.ic_notifications_black_24dp
            else         R.drawable.ic_notifications_off_black_24dp
        )
        views.setInt(R.id.btn_notif_reader, "setColorFilter", iconColor)
        views.setFloat(R.id.btn_notif_reader, "setAlpha", if (notifOn) 1.0f else 0.4f)
        views.setOnClickPendingIntent(R.id.btn_notif_reader,
            pendingBroadcast(context, ACTION_TOGGLE_NOTIF_READER))

        // Slot 2 — Next notification (dims when reader is off)
        views.setImageViewResource(R.id.btn_next_notif, R.drawable.ic_skip_next_black_24dp)
        views.setInt(R.id.btn_next_notif, "setColorFilter", iconColor)
        views.setFloat(R.id.btn_next_notif, "setAlpha", if (notifOn) 1.0f else 0.35f)
        views.setOnClickPendingIntent(R.id.btn_next_notif,
            pendingBroadcast(context, ACTION_NEXT_NOTIF))

        // Slot 3 — Voice Assistant
        views.setImageViewResource(
            R.id.btn_voice_ast,
            if (voiceOn) R.drawable.ic_mic_black_24dp
            else         R.drawable.ic_mic_off_black_24dp
        )
        views.setInt(R.id.btn_voice_ast, "setColorFilter", iconColor)
        views.setFloat(R.id.btn_voice_ast, "setAlpha", if (voiceOn) 1.0f else 0.4f)
        views.setOnClickPendingIntent(R.id.btn_voice_ast,
            pendingBroadcast(context, ACTION_TOGGLE_VOICE_AST))

        // Slot 4 — Stop Speaking (always active, no on/off state)
        views.setImageViewResource(R.id.btn_stop_speaking, R.drawable.ic_cancel_black_24dp)
        views.setInt(R.id.btn_stop_speaking, "setColorFilter", iconColor)
        views.setFloat(R.id.btn_stop_speaking, "setAlpha", 1.0f)
        views.setOnClickPendingIntent(R.id.btn_stop_speaking,
            pendingBroadcast(context, ACTION_STOP_SPEAKING))

        manager.updateAppWidget(widgetId, views)
    }

    private fun pendingBroadcast(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, DriveModeWidgetProvider::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * Speak [text] once via on-device TTS then immediately release the engine.
     * Fire-and-forget: no callbacks need to be tracked by the caller.
     */
    private fun speakOnce(context: Context, text: String) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val id = "widget_feedback"
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { engine?.shutdown() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { engine?.shutdown() }
                })
                engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            }
        }
    }
}
