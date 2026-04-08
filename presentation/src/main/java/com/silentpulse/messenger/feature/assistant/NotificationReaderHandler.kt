package com.silentpulse.messenger.feature.assistant

import android.content.Context
import android.util.Log
import com.silentpulse.messenger.feature.drivemode.NotifSnapshot
import com.silentpulse.messenger.feature.drivemode.SilentPulseNotificationListener

/**
 * Reads all active notifications aloud one-by-one, with voice commands to
 * skip, dismiss, reply, or stop.
 *
 * ## Command phrases — "read my notifications" entry point
 *   - "read notifications" / "read my notifications"
 *   - "what are my notifications" / "check notifications"
 *   - "any notifications" / "new notifications"
 *   - "read alerts" / "check alerts"
 *
 * ## Per-notification commands (spoken after each one is read)
 *   - **skip / next** — move to the next notification
 *   - **dismiss** — cancel the notification, move to next
 *   - **reply** — ask for reply text, send via RemoteInput inline reply
 *   - **read again / repeat** — re-read the current notification
 *   - **stop / done / quit / cancel** — stop reading and return to wake-word mode
 *
 * ## State machine (owned by VoiceAssistantService)
 * The service holds three fields:
 *   - `notifReaderActive: Boolean`    — whether we are in notification-reading mode
 *   - `notifReaderList: List<NotifSnapshot>` — snapshot fetched at start
 *   - `notifReaderIndex: Int`         — index of the notification being read
 *   - `notifReaderAwaitingReply: Boolean` — waiting for reply text from STT
 *
 * All routing decisions are delegated here; the service only calls `speak()` and
 * `startSttOneShot()`.
 */
class NotificationReaderHandler(private val context: Context) {

    companion object {
        private const val TAG = "NotifReader"
    }

    // ── Entry-point detection ─────────────────────────────────────────────────

    fun isReadNotificationsCommand(c: String): Boolean =
        c.contains("read notification") ||
        c.contains("check notification") ||
        c.contains("check my notification") ||
        c.contains("what are my notification") ||
        c.contains("any notification") ||
        c.contains("new notification") ||
        c.contains("read alerts") ||
        c.contains("check alerts") ||
        c.contains("my notification") ||
        c == "notifications"

    // ── Notification snapshot ─────────────────────────────────────────────────

    /**
     * Fetches active notifications from the NotificationListenerService instance.
     * Returns empty list if the listener isn't connected.
     */
    fun fetchNotifications(): List<NotifSnapshot> {
        val listener = SilentPulseNotificationListener.sInstance
        if (listener == null) {
            Log.w(TAG, "NotificationListenerService not connected")
            return emptyList()
        }
        return listener.getVoiceAssistantNotifications().also {
            Log.d(TAG, "Fetched ${it.size} notifications")
        }
    }

    // ── Speech formatting ─────────────────────────────────────────────────────

    /**
     * Builds the TTS string for a single notification.
     * e.g. "Notification 2 of 5. WhatsApp. John: Hey, are you free tonight?"
     */
    fun formatForSpeech(item: NotifSnapshot, index: Int, total: Int): String {
        val counter = if (total > 1) "Notification $index of $total. " else ""
        return "${counter}${item.appName}. ${item.title}: ${item.text}"
    }

    /**
     * Prompt appended after reading a notification.
     * Includes "reply" only if the notification supports inline reply.
     */
    fun promptForCommands(item: NotifSnapshot): String {
        return if (item.hasReplyAction) {
            "Say skip, reply, dismiss, or stop."
        } else {
            "Say skip, dismiss, or stop."
        }
    }

    // ── Per-notification command matching ─────────────────────────────────────

    fun isSkipCommand(c: String): Boolean =
        c.contains("skip") || c.contains("next") ||
        c == "pass" || c == "move on" || c.contains("move to next")

    fun isDismissCommand(c: String): Boolean =
        c.contains("dismiss") || c.contains("clear") ||
        c.contains("remove") || c.contains("delete")

    fun isReplyCommand(c: String): Boolean =
        c.contains("reply") || c.contains("respond") || c.contains("answer")

    fun isRepeatCommand(c: String): Boolean =
        c.contains("read again") || c.contains("repeat") || c.contains("again")

    fun isStopCommand(c: String): Boolean =
        c.contains("stop") || c.contains("done") ||
        c.contains("quit") || c.contains("exit") || c.contains("cancel") ||
        c.contains("that's all") || c.contains("thats all") ||
        c == "enough"

    // ── Inline reply / dismiss delegation ─────────────────────────────────────

    fun sendReply(notifKey: String, replyText: String): Boolean {
        val listener = SilentPulseNotificationListener.sInstance ?: return false
        return listener.tryInlineReply(notifKey, replyText).also {
            Log.d(TAG, "sendReply key=$notifKey result=$it")
        }
    }

    fun dismiss(notifKey: String) {
        SilentPulseNotificationListener.sInstance?.dismissNotification(notifKey)
        Log.d(TAG, "dismiss key=$notifKey")
    }
}
