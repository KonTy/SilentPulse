package com.silentpulse.messenger.feature.drivemode

import android.content.Context
import android.content.Intent

/**
 * Central SharedPreferences helper for widget and Quick Settings tile state.
 *
 * Single source of truth for:
 *  - Notification Reader  (key: [KEY_NOTIF_READER])
 *  - Voice Assistant      (key: [KEY_VOICE_AST])
 *
 * Both the AppWidget ([SilentPulseWidgetProvider]) and the two QS tiles
 * ([NotifReaderTileService], [VoiceAssistantTileService]) read from and write
 * to these prefs.  After any state change, broadcast [ACTION_STATE_CHANGED] to
 * keep all surfaces in sync.
 */
object WidgetPrefs {

    /** SharedPreferences file name (matches the rest of the app). */
    private const val PREFS_SUFFIX = "_preferences"

    /** Pref key — Notification Reader / Drive Mode ON|OFF. */
    const val KEY_NOTIF_READER = "drive_mode_enabled"

    /** Pref key — Voice Assistant wake-word listener ON|OFF. */
    const val KEY_VOICE_AST = "drive_mode_wake_word"

    /** Pref key — Voice Assistant custom wake word (default: "computer"). */
    const val KEY_WAKE_WORD = "voice_ast_wake_word"

    /**
     * Broadcast action sent whenever either flag changes from *any* surface
     * (widget tap, QS tile tap, or in-app toggle).  Both the AppWidget and
     * the QS tiles register for this action and refresh their UI on receipt.
     */
    const val ACTION_STATE_CHANGED = "com.silentpulse.messenger.WIDGET_STATE_CHANGED"

    /**
     * Broadcast action sent by the widget's "Next" button.
     * [VoiceAssistantService] receives this and advances to the next
     * notification when the notification reader is active.
     */
    const val ACTION_NEXT_NOTIFICATION = "com.silentpulse.messenger.NEXT_NOTIFICATION"

    /**
     * Broadcast action sent by the widget's "Stop speaking" button.
     * [VoiceAssistantService] receives this and immediately calls [TextToSpeech.stop].
     */
    const val ACTION_STOP_SPEAKING = "com.silentpulse.messenger.STOP_SPEAKING"

    fun getPrefs(ctx: Context) = ctx.getSharedPreferences(
        "${ctx.packageName}$PREFS_SUFFIX", Context.MODE_PRIVATE
    )

    fun isNotifReaderEnabled(ctx: Context): Boolean =
        getPrefs(ctx).getBoolean(KEY_NOTIF_READER, false)

    fun isVoiceAstEnabled(ctx: Context): Boolean =
        getPrefs(ctx).getBoolean(KEY_VOICE_AST, false)

    fun setNotifReader(ctx: Context, enabled: Boolean) {
        getPrefs(ctx).edit().putBoolean(KEY_NOTIF_READER, enabled).apply()
    }

    fun setVoiceAst(ctx: Context, enabled: Boolean) {
        getPrefs(ctx).edit().putBoolean(KEY_VOICE_AST, enabled).apply()
    }

    fun getWakeWord(ctx: Context): String =
        getPrefs(ctx).getString(KEY_WAKE_WORD, "bubblegum")?.trim()
            ?.lowercase()?.ifBlank { "bubblegum" } ?: "bubblegum"

    fun setWakeWord(ctx: Context, word: String) {
        getPrefs(ctx).edit()
            .putString(KEY_WAKE_WORD, word.trim().lowercase().ifBlank { "bubblegum" })
            .apply()
    }

    /** Broadcast to all surfaces that state has changed. */
    fun broadcastStateChanged(ctx: Context) {
        ctx.sendBroadcast(Intent(ACTION_STATE_CHANGED).apply {
            setPackage(ctx.packageName)
        })
    }
}
