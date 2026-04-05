package com.moez.QKSMS.feature.drivemode

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.moez.QKSMS.R
import com.moez.QKSMS.util.Preferences
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject

class DriveModeService : NotificationListenerService() {

    @Inject
    lateinit var ttsEngineFactory: TtsEngineFactory

    @Inject
    lateinit var sttEngine: SttEngine

    @Inject
    lateinit var voiceCommandProcessor: VoiceCommandProcessor

    @Inject
    lateinit var prefs: Preferences

    private var ttsEngine: TtsEngine? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isDriveModeActive = false

    companion object {
        private const val CHANNEL_ID = "drive_mode_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_VOICE_REPLY = "com.moez.QKSMS.VOICE_REPLY"
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("DriveModeService created")
        ttsEngine = ttsEngineFactory.createEngine()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("DriveModeService onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_VOICE_REPLY -> {
                handleVoiceReplyAction()
            }
            else -> {
                isDriveModeActive = true
                updateNotification("Drive Mode Active")
            }
        }
        
        return START_STICKY
    }

    fun isDriveModeEnabledForAllNotifications(): Boolean {
        return isDriveModeActive
    }

    fun handleIncomingNotification(app: String, sender: String, messageBody: String, notificationKey: String) {
        Timber.d("Handling notification from $app - $sender: $messageBody")
        
        val textToSpeak = buildString {
            append("Message from $sender. ")
            append(messageBody)
            append(". Say respond to reply.")
        }

        // Store notification context for potential voice reply
        val notificationContext = VoiceCommandProcessor.NotificationContext(
            app = app,
            sender = sender,
            messageBody = messageBody,
            notificationKey = notificationKey
        )
        voiceCommandProcessor.setCurrentContext(notificationContext)

        // Speak the message
        ttsEngine?.speak(
            text = textToSpeak,
            onDone = {
                // After TTS finishes, start listening
                startVoiceListening()
            }
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        
        if (!isDriveModeActive) return
        if (sbn == null) return

        val packageName = sbn.packageName
        val notification = sbn.notification ?: return

        // Filter for messaging apps
        if (!isMessagingApp(packageName)) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Unknown"
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (text.isNotEmpty()) {
            handleIncomingNotification(getAppName(packageName), title, text, sbn.key)
        }
    }

    private fun getAppName(packageName: String): String {
        return when (packageName) {
            "org.thoughtcrime.securesms" -> "Signal"
            "com.whatsapp" -> "WhatsApp"
            "org.telegram.messenger" -> "Telegram"
            "com.google.android.gm" -> "Gmail"
            "com.facebook.orca" -> "Messenger"
            "com.android.mms", "com.google.android.apps.messaging" -> "SMS"
            else -> {
                try {
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    packageName
                }
            }
        }
    }

    private fun handleVoiceReplyAction() {
        Timber.d("Voice Reply action triggered")
        startVoiceListening()
    }

    private fun startVoiceListening() {
        // Check for RECORD_AUDIO permission
        if (!hasRecordAudioPermission()) {
            Timber.w("RECORD_AUDIO permission not granted")
            ttsEngine?.speak("Microphone permission is required for voice replies.")
            return
        }

        Timber.d("Starting STT listening")
        updateNotification("Listening for your reply...")

        sttEngine.startListening(
            onResult = { recognizedText ->
                Timber.d("Recognized speech: $recognizedText")
                updateNotification("Drive Mode Active")
                
                // Pass to voice command processor
                voiceCommandProcessor.processCommand(recognizedText, voiceCommandProcessor.getCurrentContext())
            },
            onError = { error ->
                Timber.e("STT error: $error")
                updateNotification("Drive Mode Active")
                ttsEngine?.speak("Sorry, I didn't catch that.")
            }
        )
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isMessagingApp(packageName: String): Boolean {
        val messagingApps = setOf(
            "com.google.android.apps.messaging",
            "com.android.messaging",
            "com.whatsapp",
            "com.facebook.orca",
            "com.telegram.messenger",
            "com.snapchat.android",
            "com.instagram.android"
        )
        return messagingApps.contains(packageName)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Drive Mode Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Drive Mode running in the background"
                setSound(null, null)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String = "Drive Mode Active"): Notification {
        val voiceReplyIntent = Intent(this, DriveModeService::class.java).apply {
            action = ACTION_VOICE_REPLY
        }
        val voiceReplyPendingIntent = PendingIntent.getService(
            this,
            0,
            voiceReplyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QKSMS Drive Mode")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notifications_black_24dp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_call_black_24dp,
                "Voice Reply",
                voiceReplyPendingIntent
            )
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("DriveModeService destroyed")
        isDriveModeActive = false
        serviceScope.cancel()
        ttsEngine?.shutdown()
        sttEngine.shutdown()
    }
}
