package com.silentpulse.messenger.feature.drivemode

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.silentpulse.messenger.R
import com.silentpulse.messenger.util.Preferences
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

class DriveModeService : NotificationListenerService() {

    @Inject
    lateinit var ttsEngineFactory: TtsEngineFactory

    @Inject
    lateinit var sttEngineFactory: SttEngineFactory

    @Inject
    lateinit var voiceCommandProcessor: VoiceCommandProcessor

    @Inject
    lateinit var prefs: Preferences

    private var ttsEngine: TtsEngine? = null
    private var activeSttEngine: SttEngine? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isDriveModeActive = false

    // ── Anti-repeat guards ────────────────────────────────────────────────────
    /** Tracks how many times each notification key+text combo was announced. */
    private val announcementCounts = ConcurrentHashMap<String, Int>()
    /** True while TTS is speaking or STT is listening for a command. */
    @Volatile private var isProcessing = false

    companion object {
        private const val CHANNEL_ID = "drive_mode_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_VOICE_REPLY = "com.silentpulse.messenger.VOICE_REPLY"
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

        // ── Guard: drive mode still on? ──────────────────────────────────────
        if (!isDriveModeEnabledFromPrefs()) {
            Timber.d("Drive mode disabled — ignoring notification")
            return
        }

        // ── Guard: already speaking / listening? ─────────────────────────────
        if (isProcessing) {
            Timber.d("Already processing a notification — skipping")
            return
        }

        // ── Guard: max announcements per unique message ──────────────────────
        val dedupeKey = "$notificationKey|$messageBody"
        val maxAnnouncements = getMaxAnnouncementsPerMessage()
        val count = announcementCounts.getOrDefault(dedupeKey, 0)
        if (count >= maxAnnouncements) {
            Timber.d("Already announced $count/$maxAnnouncements times — skipping")
            return
        }
        announcementCounts[dedupeKey] = count + 1

        isProcessing = true
        
        val textToSpeak = buildString {
            append("Message from $sender. ")
            append(messageBody)
            append(". Say reply, repeat, or stop.")
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
                // Check drive mode is still on before starting STT
                if (!isDriveModeEnabledFromPrefs()) {
                    Timber.d("Drive mode turned off during TTS — stopping")
                    isProcessing = false
                    return@speak
                }
                // After TTS finishes, start listening
                startVoiceListening()
            }
        )
    }

    private fun isDriveModeEnabledFromPrefs(): Boolean {
        return try {
            applicationContext.getSharedPreferences(
                "${applicationContext.packageName}_preferences",
                Context.MODE_PRIVATE
            ).getBoolean("drive_mode_enabled", false)
        } catch (e: Exception) {
            false
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        
        if (!isDriveModeEnabledFromPrefs()) return
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
            "com.microsoft.teams" -> "Teams"
            "com.microsoft.office.outlook" -> "Outlook"
            "eu.faircode.email" -> "FairMail"
            "com.fsck.k9" -> "K-9 Mail"
            "ch.protonmail.android" -> "ProtonMail"
            "com.tutao.tutanota" -> "Tutanota"
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
        if (!hasRecordAudioPermission()) {
            Log.w("DriveModeService", "RECORD_AUDIO permission NOT granted")
            Timber.w("RECORD_AUDIO permission not granted")
            ttsEngine?.speak("Microphone permission is required for voice replies.")
            return
        }

        Log.d("DriveModeService", "Starting STT listening")
        Timber.d("Starting STT listening")
        updateNotification("Listening for your reply...")

        // Create a fresh engine each time — picks up the latest engine preference
        activeSttEngine?.shutdown()
        val engine = sttEngineFactory.create()
        activeSttEngine = engine
        Log.d("DriveModeService", "Created STT engine: ${engine.javaClass.simpleName}")

        engine.startListening(
            onResult = { recognizedText ->
                Log.d("DriveModeService", "STT result: \"$recognizedText\"")
                Timber.d("Recognized speech: $recognizedText")
                activeSttEngine = null

                val lower = recognizedText.lowercase()
                when {
                    // ── Stop: leave notification as-is, stop prompting ───────
                    lower.contains("stop") || lower.contains("done") ||
                    lower.contains("cancel") || lower.contains("enough") -> {
                        Timber.d("Stop command — going idle, notification preserved")
                        isProcessing = false
                        updateNotification("Drive Mode Active")
                        // No further action — notification stays for manual reading
                    }

                    // ── Repeat: re-read current message, then listen again ───
                    lower.contains("repeat") || lower.contains("read again") ||
                    lower.contains("again") || lower.contains("say again") -> {
                        val ctx = voiceCommandProcessor.getCurrentContext()
                        if (ctx != null) {
                            Timber.d("Repeat command — re-reading message")
                            val replayText = buildString {
                                append("Message from ${ctx.sender}. ")
                                append(ctx.messageBody)
                                append(". Say reply, repeat, or stop.")
                            }
                            ttsEngine?.speak(
                                text = replayText,
                                onDone = {
                                    if (isDriveModeEnabledFromPrefs()) {
                                        startVoiceListening()
                                    } else {
                                        isProcessing = false
                                    }
                                }
                            )
                        } else {
                            isProcessing = false
                            updateNotification("Drive Mode Active")
                        }
                    }

                    // ── Everything else: delegate to VoiceCommandProcessor ───
                    else -> {
                        isProcessing = false
                        updateNotification("Drive Mode Active")
                        if (isDriveModeEnabledFromPrefs()) {
                            voiceCommandProcessor.processCommand(recognizedText, voiceCommandProcessor.getCurrentContext())
                        }
                    }
                }
            },
            onError = { errorCode ->
                Log.e("DriveModeService", "STT error code: \"$errorCode\"")
                Timber.e("STT error: $errorCode")
                activeSttEngine = null
                isProcessing = false
                updateNotification("Drive Mode Active")
                if (isDriveModeEnabledFromPrefs()) {
                    handleSttError(errorCode)
                }
            }
        )
    }

    /**
     * Speaks a clear, actionable error message for every possible STT failure.
     * No silent failures — the user always hears exactly what went wrong and
     * what they need to do to fix it.
     */
    private fun handleSttError(code: String) {
        Log.e("DriveModeService", "handleSttError: code=\"$code\"")
        val msg = when {
            code == "whisper_no_model_path" ->
                "Whisper model path is not configured. " +
                "Open Drive Mode settings, tap Speech Recognition Engine, select Whisper, " +
                "then enter the full path to your GGML model file."

            code == "whisper_model_load_failed" ->
                "Failed to load the Whisper model. " +
                "Check the path in Drive Mode settings and make sure the file exists."

            code.startsWith("whisper_error") ->
                "Whisper transcription failed. " +
                "Try again or switch to Android Speech in Drive Mode settings."

            code == "network_error" || code == "server_error" ->
                "Offline speech model is not installed. " +
                "Open Android Settings, go to General Management, " +
                "then Language and Input, then On-device recognition, " +
                "and download a language pack for your language."

            code == "speech_timeout" || code == "no_match" ->
                "I did not hear anything. Say reply, repeat, or stop."

            code == "recognizer_busy" ->
                "Speech recognizer is busy. Please try again in a moment."

            code == "permission_denied" ->
                "Microphone permission is required. " +
                "Please grant it in Android Settings under App Permissions."

            code == "recognition_unavailable" ->
                "Speech recognition is not available. " +
                "Make sure a speech recognition app is installed."

            else -> "Speech recognition error. Please try again."
        }
        ttsEngine?.speak(msg)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isMessagingApp(packageName: String): Boolean {
        val messagingApps = setOf(
            applicationContext.packageName, // SilentPulse own SMS notifications
            "com.google.android.apps.messaging",
            "com.android.messaging",
            "com.whatsapp",
            "com.facebook.orca",
            "com.telegram.messenger",
            "com.snapchat.android",
            "com.instagram.android",
            "com.microsoft.teams",
            "com.microsoft.office.outlook",
            "eu.faircode.email",
            "com.fsck.k9",
            "com.google.android.gm",
            "ch.protonmail.android",
            "com.tutao.tutanota"
        )
        return messagingApps.contains(packageName)
    }

    /**
     * Returns the maximum number of times a single notification message will be
     * announced aloud.  Reads from SharedPreferences each time so it picks up
     * runtime changes immediately.
     */
    private fun getMaxAnnouncementsPerMessage(): Int {
        return try {
            applicationContext.getSharedPreferences(
                "${applicationContext.packageName}_preferences",
                Context.MODE_PRIVATE
            ).getInt("drive_mode_max_announcements", 1)
        } catch (_: Exception) { 1 }
    }

    /**
     * Stops any in-flight TTS / STT and resets processing state.
     * Called when drive mode is toggled off or service is destroyed.
     */
    private fun stopAllProcessing() {
        isProcessing = false
        ttsEngine?.stop()
        activeSttEngine?.stopListening()
        activeSttEngine?.shutdown()
        activeSttEngine = null
        announcementCounts.clear()
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
        stopAllProcessing()
        serviceScope.cancel()
        ttsEngine?.shutdown()
    }
}
