package com.silentpulse.messenger.feature.drivemode

import android.Manifest
import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.silentpulse.messenger.BuildConfig
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.silentpulse.messenger.injection.appComponent
import com.silentpulse.messenger.model.Message
import com.silentpulse.messenger.repository.MessageRepository
import io.realm.Realm
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

class SilentPulseNotificationListener : NotificationListenerService() {

    // TTS with onDone callback support
    private var ttsEngine: AndroidTtsEngine? = null
    private var listenerConnected = false

    @Inject lateinit var messageRepo: MessageRepository

    // Voice command state machine
    private enum class ListenState { IDLE, READING, AWAITING_COMMAND, AWAITING_REPLY_TEXT }
    private var listenState = ListenState.IDLE
    private var pendingNotification: StatusBarNotification? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listenTimeoutRunnable: Runnable? = null
    // Deduplication: track last-spoken notification key + timestamp
    private val recentlySpoken = HashMap<String, Long>()
    private val DEDUP_WINDOW_MS = 4_000L

    companion object {
        private val storedNotifications = ConcurrentHashMap<String, StatusBarNotification>()

        fun getStoredNotification(key: String): StatusBarNotification? = storedNotifications[key]

        fun getStoredReplyAction(key: String): Notification.Action? {
            val sbn = storedNotifications[key] ?: return null
            val notification = sbn.notification ?: return null
            notification.actions?.forEach { action ->
                if (action.remoteInputs != null && action.remoteInputs.isNotEmpty()) return action
            }
            return null
        }

        fun clearStoredNotification(key: String) { storedNotifications.remove(key) }
        fun clearAllStoredNotifications() { storedNotifications.clear() }
    }

    // -------- Lifecycle --------

    override fun onCreate() {
        super.onCreate()
        appComponent.inject(this)
        Timber.d("Drive Mode listener: onCreate")
        ttsEngine = AndroidTtsEngine(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected = true
        Timber.d("Drive Mode listener: CONNECTED")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        listenerConnected = false
        Timber.w("Drive Mode listener: DISCONNECTED - requesting rebind")
        requestRebind(ComponentName(this, SilentPulseNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        storedNotifications[sbn.key] = sbn
        if (storedNotifications.size > 50) {
            storedNotifications.keys.firstOrNull()?.let { storedNotifications.remove(it) }
        }

        Timber.d("Drive Mode: notification from pkg=${sbn.packageName} key=${sbn.key} state=$listenState")
        if (!isDriveModeEnabled()) return
        if (!isMessagingApp(sbn.packageName)) return

        // If already reading or listening, ignore the duplicate notification
        if (listenState != ListenState.IDLE) {
            Timber.d("Drive Mode: busy (state=$listenState), ignoring duplicate notification")
            return
        }

        // Deduplicate — same notification key within 4 seconds means a spurious update
        val now = System.currentTimeMillis()
        val lastSpoken = recentlySpoken[sbn.key]
        if (lastSpoken != null && now - lastSpoken < DEDUP_WINDOW_MS) {
            Timber.d("Drive Mode: dedup skip ${sbn.key} (${now - lastSpoken}ms ago)")
            return
        }
        recentlySpoken[sbn.key] = now
        // Trim old entries to avoid unbounded growth
        recentlySpoken.entries.removeAll { now - it.value > 60_000L }

        val extras = sbn.notification?.extras ?: return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() } ?: return
        // EXTRA_BIG_TEXT has full message for expanded notifications; fall back to EXTRA_TEXT
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.takeIf { it.isNotBlank() } ?: return

        val appName = getAppName(sbn.packageName)
        Timber.d("Drive Mode: SPEAKING - from $sender via $appName: $text")

        // Cancel any ongoing listening session for a previous message
        stopListeningNow()
        pendingNotification = sbn
        listenState = ListenState.READING  // block duplicate notifications during TTS

        val readAloud = "Message from $sender via $appName. $text"
        if (isVoiceReplyEnabled()) {
            // Read the message, then prompt for a voice command
            speak(readAloud) {
                mainHandler.post {
                    speak("Say dismiss, delete, or reply.") {
                        mainHandler.post { startCommandListening() }
                    }
                }
            }
        } else {
            speak(readAloud) {
                mainHandler.post { listenState = ListenState.IDLE }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn?.key?.let { clearStoredNotification(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListeningNow()
        ttsEngine?.shutdown()
        ttsEngine = null
        clearAllStoredNotifications()
    }

    // -------- TTS helpers --------

    /** Speak text; onDone is called on a TTS binder thread when TTS finishes. */
    private fun speak(text: String, onDone: (() -> Unit) = {}) {
        val engine = ttsEngine
        if (engine == null) {
            Timber.w("Drive Mode TTS engine null, skipping")
            onDone()
            return
        }
        Timber.d("Drive Mode TTS: $text")
        engine.speak(text, onDone)
    }

    // -------- Voice command state machine --------

    private fun startCommandListening() {
        if (!hasRecordPermission()) {
            speak("Microphone permission is required for voice commands.")
            return
        }
        listenState = ListenState.AWAITING_COMMAND
        Timber.d("Drive Mode STT: starting command listen")
        startSpeechRecognizer { recognized ->
            handleCommandResult(recognized)
        }
    }

    private fun handleCommandResult(text: String) {
        Timber.d("Drive Mode command recognized: \"$text\"")
        val lower = text.lowercase()
        when {
            lower.contains("dismiss") -> {
                pendingNotification?.key?.let { cancelNotification(it) }
                pendingNotification = null
                listenState = ListenState.IDLE
                speak("Message dismissed.")
            }
            lower.contains("delete") -> {
                val sbn = pendingNotification
                pendingNotification = null
                listenState = ListenState.IDLE
                if (sbn != null) {
                    cancelNotification(sbn.key)
                    if (sbn.packageName == applicationContext.packageName) {
                        deleteOwnSmsMessages(sbn.id.toLong())
                    }
                }
                speak("Message deleted.")
            }
            lower.contains("reply") || lower.contains("respond") -> {
                speak("What would you like to say?") {
                    mainHandler.post { startReplyListening() }
                }
            }
            else -> {
                // Unrecognized - remind and re-listen
                Timber.d("Drive Mode: unrecognized command, re-prompting")
                speak("Say dismiss, delete, or reply.") {
                    mainHandler.post { startCommandListening() }
                }
            }
        }
    }

    private fun startReplyListening() {
        if (!hasRecordPermission()) {
            speak("Microphone permission required.")
            return
        }
        listenState = ListenState.AWAITING_REPLY_TEXT
        Timber.d("Drive Mode STT: starting reply-text listen")
        startSpeechRecognizer { replyText ->
            handleReplyText(replyText)
        }
    }

    private fun handleReplyText(replyText: String) {
        Timber.d("Drive Mode reply text: \"$replyText\"")
        listenState = ListenState.IDLE
        val sbn = pendingNotification
        if (sbn == null) {
            speak("No pending message to reply to.")
            return
        }
        val sent = tryInlineReply(sbn.key, replyText)
        pendingNotification = null
        if (sent) {
            speak("Sending: $replyText. Message sent.") 
        } else {
            speak("Failed to send reply. Please try again or reply manually.")
        }
    }

    /**
     * Deletes unread SMS messages in [threadId] from our own database.
     * Runs on a background thread; safe to call from any thread.
     */
    private fun deleteOwnSmsMessages(threadId: Long) {
        Thread {
            try {
                val messageIds = Realm.getDefaultInstance().use { realm ->
                    realm.where(Message::class.java)
                        .equalTo("threadId", threadId)
                        .equalTo("read", false)
                        .findAll()
                        .map { it.id }
                        .toLongArray()
                }
                if (messageIds.isNotEmpty()) {
                    messageRepo.deleteMessages(*messageIds)
                    Timber.d("Drive Mode: deleted ${messageIds.size} message(s) from thread $threadId")
                } else {
                    Timber.d("Drive Mode: no unread messages found in thread $threadId")
                }
            } catch (e: Exception) {
                Timber.e(e, "Drive Mode: failed to delete messages from thread $threadId")
            }
        }.start()
    }

    private fun tryInlineReply(notificationKey: String, replyText: String): Boolean {
        return try {
            val replyAction = getStoredReplyAction(notificationKey) ?: run {
                Timber.w("No inline reply action for key: $notificationKey")
                return false
            }
            val remoteInputs = replyAction.remoteInputs ?: return false
            if (remoteInputs.isEmpty()) return false

            val remoteInput = remoteInputs[0]
            val intent = Intent()
            val bundle = Bundle()
            bundle.putCharSequence(remoteInput.resultKey, replyText)
            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
            replyAction.actionIntent.send(applicationContext, 0, intent)
            Timber.d("Inline reply sent: \"$replyText\"")
            true
        } catch (e: Exception) {
            Timber.e(e, "Inline reply failed")
            false
        }
    }

    // -------- SpeechRecognizer (must run on main thread) --------

    private fun startSpeechRecognizer(onResult: (String) -> Unit) {
        stopListeningNow()

        if (!SpeechRecognizer.isRecognitionAvailable(applicationContext)) {
            speak("Speech recognition is not available on this device.")
            listenState = ListenState.IDLE
            return
        }

        val timeoutMs = getListenTimeoutSecs() * 1000L
        Timber.d("Drive Mode STT: listening for ${timeoutMs / 1000}s, state=$listenState")

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { Timber.d("STT: ready") }
                override fun onBeginningOfSpeech() { Timber.d("STT: speech began") }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { Timber.d("STT: speech ended") }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onResults(results: Bundle?) {
                    cancelListenTimeout()
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val recognized = matches?.firstOrNull()
                    Timber.d("STT result: \"$recognized\"")
                    if (!recognized.isNullOrBlank()) {
                        onResult(recognized)
                    } else {
                        if (listenState == ListenState.AWAITING_COMMAND) {
                            speak("I did not catch that. Say dismiss, delete, or reply.") {
                                mainHandler.post { startCommandListening() }
                            }
                        } else {
                            listenState = ListenState.IDLE
                        }
                    }
                }

                override fun onError(error: Int) {
                    cancelListenTimeout()
                    val msg = sttErrorMessage(error)
                    Timber.e("STT error: $msg ($error)")
                    listenState = ListenState.IDLE
                    val userMsg = when (error) {
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        SpeechRecognizer.ERROR_NO_MATCH -> "I did not hear anything. Say dismiss, delete, or reply."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Please try again."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_SERVER -> "Offline speech model is not available on this device. Please download the Google offline speech model in your device language settings."
                        else -> "Speech recognition error: $msg."
                    }
                    val retryable = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                                 || error == SpeechRecognizer.ERROR_NO_MATCH
                    speak(userMsg) {
                        if (retryable) mainHandler.post { startCommandListening() }
                    }
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)  // Privacy: no audio leaves device
                // Stop listening ~2s after the user finishes speaking
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }
            startListening(intent)
        }

        // Hard timeout - give up if nothing is said within N seconds
        val timeoutRunnable = Runnable {
            Timber.w("Drive Mode STT: hard timeout after ${timeoutMs / 1000}s")
            stopListeningNow()
            listenState = ListenState.IDLE
        }
        listenTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, timeoutMs)
    }

    private fun stopListeningNow() {
        cancelListenTimeout()
        speechRecognizer?.apply {
            stopListening()
            destroy()
        }
        speechRecognizer = null
    }

    private fun cancelListenTimeout() {
        listenTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        listenTimeoutRunnable = null
    }

    // -------- Preference helpers --------

    private fun prefs() = applicationContext.getSharedPreferences(
        "${applicationContext.packageName}_preferences", Context.MODE_PRIVATE
    )

    private fun isDriveModeEnabled(): Boolean = try {
        prefs().getBoolean("drive_mode_enabled", false)
    } catch (e: Exception) { false }

    private fun isVoiceReplyEnabled(): Boolean = try {
        prefs().getBoolean("drive_mode_voice_reply", false)
    } catch (e: Exception) { false }

    private fun getListenTimeoutSecs(): Long = try {
        prefs().getInt("drive_mode_reply_timeout", 30).toLong()
    } catch (e: Exception) { 30L }

    private fun hasRecordPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // -------- App metadata --------

    private fun getAppName(packageName: String): String = when (packageName) {
        "org.thoughtcrime.securesms"   -> "Signal"
        "com.whatsapp"                 -> "WhatsApp"
        "org.telegram.messenger"       -> "Telegram"
        "com.facebook.orca"            -> "Messenger"
        "com.google.android.apps.messaging",
        "com.android.messaging"        -> "SMS"
        "com.microsoft.teams"          -> "Teams"
        "com.microsoft.office.outlook" -> "Outlook"
        applicationContext.packageName -> "SMS"
        else -> try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) { packageName }
    }

    private fun isMessagingApp(packageName: String) =
        packageName == applicationContext.packageName ||
        packageName == "com.google.android.apps.messaging" ||
        packageName == "com.android.messaging" ||
        packageName == "com.whatsapp" ||
        packageName == "com.facebook.orca" ||
        packageName == "org.telegram.messenger" ||
        packageName == "org.thoughtcrime.securesms" ||
        packageName == "com.snapchat.android" ||
        packageName == "com.instagram.android" ||
        packageName == "com.microsoft.teams" ||
        packageName == "com.microsoft.office.outlook" ||
        (BuildConfig.DEBUG && packageName == "com.android.shell") // debug builds only: adb test notifications

    private fun sttErrorMessage(error: Int) = when (error) {
        SpeechRecognizer.ERROR_AUDIO                    -> "audio recording error"
        SpeechRecognizer.ERROR_CLIENT                   -> "client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "insufficient permissions"
        SpeechRecognizer.ERROR_NETWORK                  -> "network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT          -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH                 -> "no speech match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY          -> "recognizer busy"
        SpeechRecognizer.ERROR_SERVER                   -> "server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT           -> "speech timeout"
        else                                            -> "unknown error $error"
    }
}
