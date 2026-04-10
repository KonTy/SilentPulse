package com.silentpulse.messenger.feature.drivemode

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import timber.log.Timber
import com.silentpulse.messenger.manager.PermissionManager
import com.silentpulse.messenger.repository.MessageRepository
import javax.inject.Inject

class VoiceCommandProcessor @Inject constructor(
    private val context: Context,
    private val messageRepository: MessageRepository,
    private val permissionManager: PermissionManager,
    private val ttsEngineFactory: TtsEngineFactory
) {
    private var ttsEngine: TtsEngine? = null
    private var pendingReplyContext: NotificationContext? = null
    private var pendingOpenAppContext: NotificationContext? = null
    private var currentContext: NotificationContext? = null

    data class NotificationContext(
        val app: String,
        val sender: String,
        val messageBody: String,
        val notificationKey: String? = null
    )

    init {
        ttsEngine = ttsEngineFactory.createEngine()
    }

    fun setCurrentContext(context: NotificationContext) {
        Timber.d("VCP: setCurrentContext(${context.app}/${context.sender})")
        currentContext = context
    }

    fun getCurrentContext(): NotificationContext? = currentContext

    fun processCommand(command: String, notificationContext: NotificationContext?) {
        Timber.d("processCommand(\"$command\") context=${notificationContext?.app}/${notificationContext?.sender}")
        when {
            command.contains("read", ignoreCase = true) -> {
                Timber.d("VCP: READ command")
                notificationContext?.let { readMessage(it) }
            }
            command.contains("reply", ignoreCase = true) || command.contains("respond", ignoreCase = true) -> {
                Timber.d("VCP: REPLY command")
                notificationContext?.let { initiateReply(it) }
            }
            command.contains("dismiss", ignoreCase = true) || command.contains("delete", ignoreCase = true) ||
            command.contains("skip", ignoreCase = true) || command.contains("next", ignoreCase = true) -> {
                Timber.d("Dismiss/skip command — clearing context")
                currentContext = null
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
        Timber.d("VCP: YES command — pendingOpen=${pendingOpenAppContext != null} pendingReply=${pendingReplyContext != null}")
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
        Timber.d("VCP: NO command — pendingOpen=${pendingOpenAppContext != null} pendingReply=${pendingReplyContext != null}")
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
            speak("Sending SMS to ${context.sender}: $replyText")
            // TODO: Implement actual SMS sending via messageRepository
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
        Timber.d("VCP TTS: \"${if (text.length > 60) text.take(60) + "…" else text}\"")
        ttsEngine?.speak(text)
    }

    fun shutdown() {
        Timber.d("VCP: shutdown()")
        ttsEngine?.shutdown()
        pendingReplyContext = null
        pendingOpenAppContext = null
        currentContext = null
    }
}
