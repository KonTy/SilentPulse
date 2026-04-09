package com.silentpulse.messenger.feature.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import com.silentpulse.messenger.BuildConfig
import timber.log.Timber

/**
 * Debug-only receiver for testing Drive Mode voice reply end-to-end via ADB.
 *
 * Post a test message:
 *   adb shell am broadcast -n com.silentpulse.messenger/.feature.debug.DebugTestReceiver \
 *     -a com.silentpulse.messenger.POST_TEST_MESSAGE \
 *     --es sender "Mike" \
 *     --es message "Hey, are you free this weekend?"
 *
 * Then watch for the reply in logcat:
 *   adb logcat -s DebugTestReceiver
 *
 * All logic is gated on BuildConfig.DEBUG — this receiver is inert in release builds.
 */
class DebugTestReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_POST_TEST_MESSAGE = "com.silentpulse.messenger.POST_TEST_MESSAGE"
        const val KEY_REPLY = "key_text_reply"
        private const val CHANNEL_ID = "debug_test_channel"
        private var nextNotificationId = 9998
        private const val EXTRA_IS_REPLY = "extra_is_reply"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return

        if (intent.getBooleanExtra(EXTRA_IS_REPLY, false)) {
            handleReply(intent)
        } else {
            val sender = intent.getStringExtra("sender")
                ?: intent.getStringExtra("title") ?: "Mike"
            val message = intent.getStringExtra("message")
                ?: intent.getStringExtra("text") ?: "Hey, are you free this weekend? We are thinking of a barbecue Saturday."
            postTestNotification(context, sender, message)
        }
    }

    private fun handleReply(intent: Intent) {
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)?.toString()

        Timber.i("DebugTest: REPLY RECEIVED: \"$reply\"")
        // Also log directly so it's visible without Timber tag filtering
        android.util.Log.i("DebugTestReceiver", ">>> REPLY RECEIVED: \"$reply\" <<<")
    }

    private fun postTestNotification(context: Context, sender: String, message: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val notifId = nextNotificationId++

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Debug Test Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { setSound(null, null) }
            nm.createNotificationChannel(channel)
        }

        // RemoteInput so Drive Mode can send a voice reply back to us
        val remoteInput = RemoteInput.Builder(KEY_REPLY)
            .setLabel("Reply")
            .build()

        // PendingIntent that routes the reply back here (EXTRA_IS_REPLY = true)
        val replyIntent = Intent(context, DebugTestReceiver::class.java).apply {
            action = ACTION_POST_TEST_MESSAGE
            putExtra(EXTRA_IS_REPLY, true)
        }
        val replyPi = PendingIntent.getBroadcast(
            context, notifId, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val replyAction = Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_menu_send),
            "Reply",
            replyPi
        ).addRemoteInput(remoteInput).build()

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(sender)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .addAction(replyAction)
            .setAutoCancel(true)
            .build()

        nm.notify(notifId, notification)

        android.util.Log.d("DebugTestReceiver", "Test notification posted (id=$notifId) — from: $sender, msg: $message")
        Timber.d("DebugTest: posted test notification from $sender: $message")
    }
}
