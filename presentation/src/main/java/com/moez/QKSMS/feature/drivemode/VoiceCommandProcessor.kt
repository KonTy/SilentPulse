
package com.moez.QKSMS.feature.drivemode

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import timber.log.Timber
import com.moez.QKSMS.common.util.MessageDetailsFormatter
import com.moez.QKSMS.manager.PermissionManager
import com.moez.QKSMS.repository.MessageRepository
import javax.inject.Inject

class VoiceCommandProcessor @Inject constructor(
    private val context: Context,
    private val messageRepository: MessageRepository,
    private val permissionManager: PermissionManager,
    private val messageFormatter: MessageDetailsFormatter
) {
    private var tts: TextToSpeech? = null
    private var pendingReplyContext: NotificationContext? = null
    private var pendingOpenAppContext: NotificationContext? = null

    data class NotificationContext(
        val app: String,
        val sender: String,
        val messageBody: String,
        val notificationKey: String? = null
    )

    fun processCommand(command: String, notificationContext: NotificationContext?) {
        when {
            command.contains("read", ignoreCase = true) -> {
                notificationContext?.let { readMessage(it) }
            }
            command.contains("reply", ignoreCase = true) -> {
                notificationContext?.let { initiateReply(it) }
            }
            command.contains("yes", ignoreCase = true) -> {
                handleYesCommand(command)
            }
            command.contains("no", ignoreCase = true) -> {
                handleNoCommand()
            }
        }
    }

    private fun handleYesCommand(command: String) {
        when {
            pendingOpenAppContext != null -> {
                // User confirmed they want to open the app
                pendingOpenAppContext?.let { openAppForReply(it) }
                pendingOpenAppContext = null
            }
            pendingReplyContext != null -> {
                // User is providing reply text
                val replyText = extractReplyText(command)
                if (replyText.isNotEmpty()) {
                    pendingReplyContext?.let { sendReply(it, replyText) }
                }
            }
        }
    }

    private fun handleNoCommand() {
        when {
            pendingOpenAppContext != null -> {
                speak("Okay, I won't open the app.")
                pendingOpenAppContext = null
            }
            pendingReplyContext != null -> {
                speak("Okay, canceling reply.")
                pendingReplyContext = null
            }
        }
    }

    private fun readMessage(context: NotificationContext) {
        speak("Message from ${context.sender} in ${context.app}: ${context.messageBody}")
    }

    private fun initiateReply(context: NotificationContext) {
        pendingReplyContext = context
        speak("What would you like to reply to ${context.sender}?")
    }

    private fun sendReply(context: NotificationContext, replyText: String) {
        if (context.app.equals("sms", ignoreCase = true)) {
            sendSmsReply(context, replyText)
        } else {
            sendCrossAppReply(context, replyText)
        }
        pendingReplyContext = null
    }

    private fun sendSmsReply(context: NotificationContext, replyText: String) {
        if (!permissionManager.isDefaultSms() || !permissionManager.hasSendSms()) {
            speak("I don't have permission to send SMS messages.")
            return
        }

        try {
            // Use MessageRepository to send SMS
            speak("Sending SMS to ${context.sender}: $replyText")
            // TODO: Implement actual SMS sending via messageRepository
            // messageRepository.sendMessage(...)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send SMS")
            speak("Sorry, I couldn't send the message.")
        }
    }

    private fun sendCrossAppReply(context: NotificationContext, replyText: String) {
        // Try inline reply first
        if (context.notificationKey != null && tryInlineReply(context.notificationKey, replyText)) {
            speak("Reply sent to ${context.sender} via ${context.app}")
            return
        }

        // Fallback: offer to open the app
        speak("I cannot directly reply to ${context.app}. Would you like me to open it?")
        pendingOpenAppContext = context
        pendingReplyContext = null
    }

    private fun tryInlineReply(notificationKey: String, replyText: String): Boolean {
        try {
            val replyAction = SilentPulseNotificationListener.getStoredReplyAction(notificationKey)
                ?: return false

            val remoteInputs = replyAction.remoteInputs ?: return false
            if (remoteInputs.isEmpty()) return false

            val remoteInput = remoteInputs[0]
            val intent = Intent()
            val bundle = Bundle()
            bundle.putCharSequence(remoteInput.resultKey, replyText)
            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)

            replyAction.actionIntent.send(context, 0, intent)
            Timber.d("Inline reply sent successfully")
            return true
        } catch (e: Exception) {
            Timber.e(e, "Failed to send inline reply")
            return false
        }
    }

    private fun openAppForReply(context: NotificationContext) {
        try {
            val notification = SilentPulseNotificationListener.getStoredNotification(
                context.notificationKey ?: return
            )
            
            notification?.notification?.contentIntent?.send()
            speak("Opening ${context.app}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to open app")
            speak("Sorry, I couldn't open ${context.app}")
        }
    }

    private fun extractReplyText(command: String): String {
        // Remove command words like "yes", "reply", etc.
        return command
            .replace("yes", "", ignoreCase = true)
            .replace("reply", "", ignoreCase = true)
            .trim()
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, null)
    }

    fun initializeTTS(onInitialized: () -> Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                onInitialized()
            }
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        pendingReplyContext = null
        pendingOpenAppContext = null
    }
}
