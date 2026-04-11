package com.silentpulse.messenger.feature.drivemode

import android.Manifest
import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import com.silentpulse.messenger.BuildConfig
import com.silentpulse.messenger.feature.assistant.ConfirmSendWorkflow
import com.silentpulse.messenger.injection.appComponent
import com.silentpulse.messenger.model.Message
import com.silentpulse.messenger.repository.MessageRepository
import io.realm.Realm
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** Snapshot of a notification for voice presentation by the assistant. */
data class NotifSnapshot(
    val packageName: String,
    val key: String,
    val appName: String,
    val title: String,
    val text: String,
    val hasReplyAction: Boolean
)

class SilentPulseNotificationListener : NotificationListenerService() {

    // TTS with onDone callback support
    private var ttsEngine: AndroidTtsEngine? = null
    private var listenerConnected = false

    @Inject lateinit var messageRepo: MessageRepository
    @Inject lateinit var sttEngineFactory: SttEngineFactory

    // Voice command state machine
    private enum class ListenState { IDLE, READING, AWAITING_COMMAND, AWAITING_REPLY_TEXT }
    private var listenState = ListenState.IDLE
    private var pendingNotification: StatusBarNotification? = null
    /** Non-null while the confirm-before-send workflow is active. */
    private var confirmWorkflow: ConfirmSendWorkflow? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    // Deduplication: track last-spoken notification key + timestamp
    private val recentlySpoken = HashMap<String, Long>()
    private val DEDUP_WINDOW_MS = 4_000L

    // ── Retry & loop guards ────────────────────────────────────────────────
    /** Counts ALL voice command attempts (error + unrecognized) for the current notification. */
    private var totalVoiceAttempts = 0
    /** Absolute cap: after this many failed attempts for one notification, give up. */
    private val MAX_VOICE_ATTEMPTS = 5
    /** Delay (ms) after TTS finishes before starting the microphone — prevents TTS echo bleed. */
    private val STT_START_DELAY_MS = 800L

    // ── Persistent STT engine — avoids reloading the 488 MB model each time ──
    private var cachedSttEngine: SttEngine? = null
    /** Monotonically incrementing session ID — stale callbacks are discarded. */
    private var sttSessionId = 0



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


        /** Live instance — set in onCreate, cleared in onDestroy. */
        @Volatile var sInstance: SilentPulseNotificationListener? = null

        /**
         * Find the active nav notification from [pkg] and fire its Stop action.
         * @return true if a stop action was found and fired.
         */
        fun fireStopNavAction(pkg: String): Boolean =
            sInstance?.stopNavForPkg(pkg) ?: false

        /** Scan ALL active notifications for any nav app's Stop action. */
        fun fireStopAnyNav(): Boolean =
            sInstance?.stopAnyNavigation() ?: false

        /**
         * Whisper commonly hallucinates short phrases when given silence or noise.
         * These should NOT be treated as a valid voice command.
         */
        private val HALLUCINATION_PATTERNS = setOf(
            "[blank_audio]", "blank audio", "(soft music)", "(music)",
            "thank you", "thanks for watching", "thank you for watching",
            "you", "the", "bye", "subtitles by", "subscribe", "[music]",
            "so", "uh", "um", "hmm", "huh", "oh",
            "thanks", "okay", "yes", "no"
        )
    }


    /** Called by companion [fireStopNavAction] to stop navigation for [pkg]. */
    fun stopNavForPkg(pkg: String): Boolean {
        if (!listenerConnected) return false
        val sbns = try { getActiveNotifications() } catch (_: Exception) { return false }
        val navSbn = sbns.firstOrNull { it.packageName == pkg } ?: return false
        val actions = navSbn.notification?.actions ?: return false
        val stopAction = actions.firstOrNull { action ->
            val title = action.title?.toString()?.lowercase() ?: ""
            title.contains("stop") || title.contains("end") ||
            title.contains("exit") || title.contains("close")
        } ?: actions.firstOrNull()
        return try { stopAction?.actionIntent?.send(); stopAction != null }
        catch (_: Exception) { false }
    }

    /**
     * Scan ALL active notifications for any known nav app and fire its Stop action.
     * Used when the user says "stop navigation" without specifying which app.
     */
    fun stopAnyNavigation(): Boolean {
        if (!listenerConnected) return false
        val navPackages = setOf(
            "com.google.android.apps.maps",
            "net.osmand", "net.osmand.plus", "net.osmand.dev",
            "app.organicmaps", "app.organicmaps.debug"
        )
        val sbns = try { getActiveNotifications() } catch (_: Exception) { return false }
        for (sbn in sbns) {
            if (sbn.packageName !in navPackages) continue
            val actions = sbn.notification?.actions ?: continue
            val stopAction = actions.firstOrNull { action ->
                val title = action.title?.toString()?.lowercase() ?: ""
                title.contains("stop") || title.contains("end") ||
                title.contains("exit") || title.contains("close")
            } ?: actions.firstOrNull() ?: continue
            return try { stopAction.actionIntent?.send(); true }
            catch (_: Exception) { false }
        }
        return false
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        appComponent.inject(this)
        sInstance = this
        Timber.d("Drive Mode listener: onCreate")
        ttsEngine = AndroidTtsEngine(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected = true
        // Invalidate any cached STT engine so the factory picks the right engine
        // (fixes stale Whisper cache after preference/code changes)
        cachedSttEngine?.shutdown()
        cachedSttEngine = null
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
        Log.d("DriveModeTTS", "onNotificationPosted: pkg=" + (sbn?.packageName ?: "null") + " key=" + (sbn?.key ?: "null") + " flags=0x" + Integer.toHexString(sbn?.notification?.flags ?: 0))
        if (sbn == null) return

        storedNotifications[sbn.key] = sbn
        if (storedNotifications.size > 50) {
            storedNotifications.keys.firstOrNull()?.let { storedNotifications.remove(it) }
        }

        Timber.d("Drive Mode: notification from pkg=${sbn.packageName} key=${sbn.key} state=$listenState")
        if (!isDriveModeEnabled()) return
        if (!isMessagingApp(sbn.packageName)) return

        // Skip ongoing / foreground-service notifications (e.g. VoiceAssistantService, Drive Mode itself)
        val flags = sbn.notification?.flags ?: 0
        if (flags and Notification.FLAG_ONGOING_EVENT != 0 || flags and Notification.FLAG_FOREGROUND_SERVICE != 0) {
            Timber.d("Drive Mode: skipping ongoing/foreground notification ${sbn.key}")
            return
        }

        // If already reading or listening, ignore the duplicate notification
        if (listenState != ListenState.IDLE) {
            Timber.d("Drive Mode: busy (state=$listenState), ignoring duplicate notification")
            return
        }

        // Deduplicate - same notification key within 4 seconds means a spurious update
        val now = System.currentTimeMillis()
        val lastSpoken = recentlySpoken[sbn.key]
        if (lastSpoken != null && now - lastSpoken < DEDUP_WINDOW_MS) {
            Timber.d("Drive Mode: dedup skip ${sbn.key}")
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
        Timber.d("Drive Mode: listenState → READING")
        listenState = ListenState.READING  // block duplicate notifications during TTS
        totalVoiceAttempts = 0  // reset for this new notification

        val readAloud = "Message from $sender via $appName. $text"
        if (isVoiceReplyEnabled()) {
            // Pre-load STT engine while TTS speaks
            ensureSttEngine()
            // Read the message, then prompt for a voice command
            speak(readAloud) {
                mainHandler.post {
                    speak("Say dismiss, delete, reply, repeat, or stop.") {
                        mainHandler.postDelayed({ startCommandListening() }, STT_START_DELAY_MS)
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
        sInstance = null
        stopListeningNow()
        // DriveModeMicService is persistent — do NOT stop it here
        cachedSttEngine?.shutdown()
        cachedSttEngine = null
        ttsEngine?.shutdown()
        ttsEngine = null
        clearAllStoredNotifications()
    }

    // ── TTS helpers ────────────────────────────────────────────────────────

    /** Speak text; onDone is called on a TTS binder thread when TTS finishes. */
    private fun speak(text: String, onDone: (() -> Unit) = {}) {
        val engine = ttsEngine
        if (engine == null) {
            Timber.w("Drive Mode TTS engine null, skipping")
            onDone()
            return
        }
        // Detect script and switch TTS locale for non-Latin text
        val detected = detectLocaleByScript(text)
        if (detected != null) {
            engine.setLocale(detected)
            Timber.d("Drive Mode TTS [${detected.language}]: $text")
        } else {
            engine.setLocale(Locale.getDefault())
            Timber.d("Drive Mode TTS: $text")
        }
        engine.speak(text) {
            // Reset to default locale after speaking foreign text
            if (detected != null) engine.setLocale(Locale.getDefault())
            onDone()
        }
    }

    /**
     * Zero-dependency language detection via Unicode script ranges.
     * Returns a Locale when >= 30% of letters belong to a non-Latin script,
     * or null if the text is predominantly Latin / English.
     */
    private fun detectLocaleByScript(text: String): Locale? {
        var total = 0
        var cyrillic = 0; var arabic = 0; var cjk = 0; var kana = 0
        var hangul = 0; var devanagari = 0; var thai = 0; var hebrew = 0; var greek = 0
        for (c in text) {
            if (!c.isLetter()) continue
            total++
            when {
                c in '\u0400'..'\u04FF' || c in '\u0500'..'\u052F' -> cyrillic++
                c in '\u0600'..'\u06FF' || c in '\u0750'..'\u077F' || c in '\uFB50'..'\uFDFF' || c in '\uFE70'..'\uFEFF' -> arabic++
                c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c in '\uF900'..'\uFAFF' -> cjk++
                c in '\u3040'..'\u309F' || c in '\u30A0'..'\u30FF' -> kana++
                c in '\uAC00'..'\uD7AF' || c in '\u1100'..'\u11FF' -> hangul++
                c in '\u0900'..'\u097F' -> devanagari++
                c in '\u0E00'..'\u0E7F' -> thai++
                c in '\u0590'..'\u05FF' || c in '\uFB1D'..'\uFB4F' -> hebrew++
                c in '\u0370'..'\u03FF' || c in '\u1F00'..'\u1FFF' -> greek++
            }
        }
        if (total == 0) return null
        val threshold = (total * 0.30).toInt()
        return when {
            cyrillic  > threshold -> Locale("ru")
            arabic    > threshold -> Locale("ar")
            kana      > threshold || (cjk > threshold && kana > 0) -> Locale("ja")
            cjk       > threshold -> Locale("zh")
            hangul    > threshold -> Locale("ko")
            devanagari > threshold -> Locale("hi")
            thai      > threshold -> Locale("th")
            hebrew    > threshold -> Locale("he")
            greek     > threshold -> Locale("el")
            else -> null  // Latin / English - keep default
        }
    }

            // ── Voice command state machine ────────────────────────────────────────

    private fun startCommandListening() {
        if (!hasRecordPermission()) {
            speak("Microphone permission is required for voice commands.")
            listenState = ListenState.IDLE
            return
        }

        // Absolute attempt cap — prevents infinite loops of ANY kind
        totalVoiceAttempts++
        if (totalVoiceAttempts > MAX_VOICE_ATTEMPTS) {
            Timber.w("Drive Mode: max voice attempts ($MAX_VOICE_ATTEMPTS) reached, giving up")
            speak("Voice commands not available right now.") {
                mainHandler.post {
                    listenState = ListenState.IDLE
                    totalVoiceAttempts = 0
                }
            }
            return
        }

        Timber.d("Drive Mode: listenState → AWAITING_COMMAND")
        listenState = ListenState.AWAITING_COMMAND
        Timber.d("Drive Mode STT: starting command listen (attempt $totalVoiceAttempts/$MAX_VOICE_ATTEMPTS)")
        startSttListening { recognized ->
            handleCommandResult(recognized)
        }
    }

    /**
     * Returns true if the transcribed text looks like a Whisper hallucination
     * (phantom output generated from silence or background noise).
     */
    private fun isHallucination(text: String): Boolean {
        val cleaned = text.trim().lowercase()
            .removeSuffix(".")
            .removeSuffix(",")
            .trim()
        if (cleaned.length < 2) return true
        return HALLUCINATION_PATTERNS.any { cleaned == it }
    }

    /**
     * Simple Levenshtein edit-distance check.
     * Returns true if [a] and [b] are within [maxDist] edits of each other.
     * Used for fuzzy voice command matching (Whisper often mishears short words).
     */
    private fun fuzzyMatch(a: String, b: String, maxDist: Int): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > maxDist) return false
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i-1][j] + 1, dp[i][j-1] + 1, dp[i-1][j-1] + cost)
            }
            // Early exit: if entire row exceeds maxDist, no point continuing
            if (dp[i].min()!! > maxDist) return false
        }
        return dp[a.length][b.length] <= maxDist
    }

    private fun handleCommandResult(text: String) {
        Timber.d("Drive Mode command recognized: \"$text\"")

        // ── Confirm-send workflow intercept ──────────────────────────────────
        // While a confirm workflow is active (user just dictated a reply and we
        // are waiting for yes / no / read back / dictate again) every STT result
        // must be routed here instead of the normal command handler.
        if (confirmWorkflow?.isActive == true) {
            confirmWorkflow!!.handleInput(text)
            return
        }

        val lower = text.lowercase().trim()

        // Filter Whisper hallucinations — treat as silence
        if (isHallucination(text)) {
            Timber.d("Drive Mode: ignoring Whisper hallucination: \"$text\"")
            retryOrGiveUp("I did not hear anything. Say dismiss, delete, reply, repeat, or stop.")
            return
        }

        // Fuzzy match: Whisper often mishears short commands.
        // Use word-level edit-distance matching for the 5 commands.
        val words = lower.replace(Regex("[^a-z\\s]"), "").split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val matchesDismiss = lower.contains("dismiss") || lower.contains("skip") || lower.contains("next") ||
            words.any { fuzzyMatch(it, "dismiss", 3) || fuzzyMatch(it, "skip", 2) || fuzzyMatch(it, "next", 2) }
        val matchesDelete = lower.contains("delete") || words.any { fuzzyMatch(it, "delete", 2) }
        val matchesReply = lower.contains("reply") || lower.contains("respond") ||
            words.any { fuzzyMatch(it, "reply", 2) || fuzzyMatch(it, "respond", 3) }
        val matchesRead = lower.contains("read") || lower.contains("repeat") ||
            words.any { fuzzyMatch(it, "read", 2) || fuzzyMatch(it, "repeat", 2) }
        val matchesStop = lower.contains("stop") || lower.contains("done") || lower.contains("cancel") || lower.contains("enough") ||
            words.any { fuzzyMatch(it, "stop", 2) || fuzzyMatch(it, "done", 2) || fuzzyMatch(it, "cancel", 3) }

        Timber.d("Drive Mode: match results: dismiss=$matchesDismiss delete=$matchesDelete reply=$matchesReply read=$matchesRead stop=$matchesStop")
        when {
            matchesDismiss -> {
                Timber.d("Drive Mode: → DISMISS")
                totalVoiceAttempts = 0
                pendingNotification?.key?.let { cancelNotification(it) }
                pendingNotification = null
                listenState = ListenState.IDLE
                speak("Message dismissed.")
            }
            matchesDelete -> {
                Timber.d("Drive Mode: → DELETE")
                totalVoiceAttempts = 0
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
            matchesReply -> {
                Timber.d("Drive Mode: → REPLY")
                totalVoiceAttempts = 0
                speak("What would you like to say?") {
                    mainHandler.postDelayed({ startReplyListening() }, STT_START_DELAY_MS)
                }
            }
            matchesRead -> {
                Timber.d("Drive Mode: → READ/REPEAT")
                // Re-read the message
                totalVoiceAttempts = 0
                val sbn = pendingNotification
                if (sbn != null) {
                    val extras = sbn.notification?.extras
                    val sender = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Unknown"
                    val body = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                        ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
                    speak("$sender said: $body. Say dismiss, delete, reply, repeat, or stop.") {
                        mainHandler.postDelayed({ startCommandListening() }, STT_START_DELAY_MS)
                    }
                } else {
                    speak("No message to read.") {
                        mainHandler.post { listenState = ListenState.IDLE }
                    }
                }
            }
            matchesStop -> {
                Timber.d("Drive Mode: → STOP")
                // Stop: leave notification in place, go idle — user can read it later
                totalVoiceAttempts = 0
                pendingNotification = null
                listenState = ListenState.IDLE
                speak("Stopped. Notification is still there for you to read later.")
            }
            else -> {
                // Unrecognized command — DO NOT reset totalVoiceAttempts
                Timber.d("Drive Mode: unrecognized command \"$text\", re-prompting")
                retryOrGiveUp("I heard \"${text.take(30)}\". Say dismiss, delete, reply, repeat, or stop.")
            }
        }
    }

    /**
     * Speaks the prompt and retries STT, or gives up if max attempts reached.
     * Does NOT reset [totalVoiceAttempts]. Keeps [listenState] as AWAITING_COMMAND
     * during the TTS prompt to block duplicate notifications.
     */
    private fun retryOrGiveUp(prompt: String) {
        Timber.d("Drive Mode: retryOrGiveUp attempt=$totalVoiceAttempts/$MAX_VOICE_ATTEMPTS")
        if (totalVoiceAttempts >= MAX_VOICE_ATTEMPTS) {
            Timber.w("Drive Mode: giving up after $totalVoiceAttempts attempts")
            speak("Voice commands not available right now.") {
                mainHandler.post {
                    listenState = ListenState.IDLE
                    totalVoiceAttempts = 0
                }
            }
        } else {
            // Speak the prompt then restart
            speak(prompt) {
                mainHandler.postDelayed({ startCommandListening() }, STT_START_DELAY_MS)
            }
        }
    }

    private fun startReplyListening() {
        if (!hasRecordPermission()) {
            speak("Microphone permission required.")
            listenState = ListenState.IDLE
            return
        }
        Timber.d("Drive Mode: listenState → AWAITING_REPLY_TEXT")
        listenState = ListenState.AWAITING_REPLY_TEXT
        Timber.d("Drive Mode STT: starting reply-text listen")
        startSttListening { replyText ->
            handleReplyText(replyText)
        }
    }

    private fun handleReplyText(replyText: String) {
        Timber.d("Drive Mode reply text: \"$replyText\"")
        // Filter hallucinations in reply mode too
        if (isHallucination(replyText)) {
            speak("I did not hear your reply. Please try again, or say dismiss.") {
                mainHandler.postDelayed({ startCommandListening() }, STT_START_DELAY_MS)
            }
            return
        }
        val sbn = pendingNotification
        if (sbn == null) {
            listenState = ListenState.IDLE
            speak("No pending message to reply to.")
            return
        }
        val sender = sbn.notification?.extras
            ?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "contact"
        // Keep listenState at AWAITING_COMMAND so new notifications are blocked
        // while the user is confirming the reply.
        listenState = ListenState.AWAITING_COMMAND
        confirmWorkflow = ConfirmSendWorkflow(
            speak    = { text, onDone -> speak(text, onDone ?: {}) },
            startStt = { mainHandler.postDelayed({ startCommandListening() }, STT_START_DELAY_MS) },
            onSend   = { text ->
                confirmWorkflow = null
                val sent = tryInlineReply(sbn.key, text)
                pendingNotification = null
                listenState = ListenState.IDLE
                speak(if (sent) "Reply sent." else "Failed to send reply. Please try again or reply manually.")
            },
            onCancel = {
                confirmWorkflow = null
                // Notification still pending — let user pick another action.
                speak("Reply cancelled. Say dismiss, delete, reply, repeat, or stop.") {
                    mainHandler.postDelayed({ startCommandListening() }, STT_START_DELAY_MS)
                }
            }
        )
        confirmWorkflow!!.startWithText(sender, replyText)
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

    /** Public: VoiceAssistantService can use this to reply during notification reading. */
    fun tryInlineReply(notificationKey: String, replyText: String): Boolean {
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

    // ── Microphone access ────────────────────────────────────────────
    // DriveModeMicService runs persistently while Drive Mode is enabled,
    // started from the Activity (foreground context). We do NOT start/stop
    // it per-recording because Android 14+ blocks starting a foreground
    // service with MICROPHONE type from a background context.

    private fun startMicService() {
        // No-op: DriveModeMicService is managed by Activity / MainActivity
    }

    private fun stopMicService() {
        // No-op: DriveModeMicService stays alive while Drive Mode is on
    }

    // ── STT via SttEngine (Whisper) — persistent engine, model stays loaded ──

    /**
     * Starts speech recognition using the cached STT engine (model stays in memory).
     * Uses a session ID to discard stale callbacks from cancelled sessions.
     */
    /**
     * Pre-loads the STT engine in the background so it's
     * ready when we actually need to record. Call this during TTS playback.
     */
    private fun ensureSttEngine() {
        // If we have a stale Whisper engine cached, drop it so the factory
        // can create the preferred Android STT engine instead.
        if (cachedSttEngine is WhisperSttEngine) {
            Timber.d("ensureSttEngine: replacing stale WhisperSttEngine with preferred engine")
            cachedSttEngine?.shutdown()
            cachedSttEngine = null
        }
        if (cachedSttEngine != null) return
        try {
            Log.d("DriveModeTTS", "ensureSttEngine: pre-loading STT engine during TTS")
            cachedSttEngine = sttEngineFactory.create()
            Timber.d("ensureSttEngine: created ${cachedSttEngine?.javaClass?.simpleName}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to pre-load STT engine")
        }
    }


        private fun startSttListening(onResult: (String) -> Unit) {
        // Start foreground mic service so Android unsilences the microphone
        startMicService()

        // Stop any in-flight recording but keep the engine & model in memory
        cachedSttEngine?.stopListening()
        val sessionId = ++sttSessionId

        // Lazily create the engine (model loads once, reused across all listens)
        if (cachedSttEngine == null) {
            Log.d("DriveModeTTS", "startSttListening: creating STT engine (first time)")
            try {
                cachedSttEngine = sttEngineFactory.create()
            } catch (e: Exception) {
                Timber.e(e, "Failed to create STT engine")
                speak("Speech recognition is not available.") {
                    mainHandler.post { listenState = ListenState.IDLE }
                }
                return
            }
        }

        val engine = cachedSttEngine!!
        Timber.d("Drive Mode STT: using ${engine.javaClass.simpleName} (session $sessionId)")

        engine.startListening(
            onResult = { recognizedText ->
                // Discard stale callbacks from cancelled sessions
                if (sessionId != sttSessionId) {
                    Timber.d("Drive Mode STT: stale session $sessionId (current=$sttSessionId), ignoring result")
                    return@startListening
                }
                Log.d("DriveModeTTS", "STT result: \"$recognizedText\"")
                Timber.d("STT result: \"$recognizedText\"")
                if (recognizedText.isNotBlank()) {
                    mainHandler.post {
                        stopMicService()
                        onResult(recognizedText)
                    }
                } else {
                    // Empty result - treat like no match
                    mainHandler.post {
                        if (listenState == ListenState.AWAITING_COMMAND) {
                            retryOrGiveUp("Say dismiss, delete, reply, repeat, or stop.")
                        } else if (listenState == ListenState.AWAITING_REPLY_TEXT) {
                            speak("I did not hear your reply. Say dismiss to cancel.") {
                                mainHandler.postDelayed({ startCommandListening() }, STT_START_DELAY_MS)
                            }
                        } else {
                            listenState = ListenState.IDLE
                        }
                    }
                }
            },
            onError = { errorCode ->
                // Discard stale callbacks from cancelled sessions
                if (sessionId != sttSessionId) {
                    Timber.d("Drive Mode STT: stale session $sessionId (current=$sttSessionId), ignoring error")
                    return@startListening
                }
                Log.e("DriveModeTTS", "STT error: $errorCode (attempt $totalVoiceAttempts/$MAX_VOICE_ATTEMPTS)")
                Timber.e("STT error: $errorCode")
                mainHandler.post {
                    stopMicService()
                    val userMsg = when (errorCode) {
                        "mic_silenced" ->
                            "Microphone is not available. Please open SilentPulse to activate Drive Mode mic."
                        "whisper_no_model_path" ->
                            "Whisper model path is not configured. Open settings to set it up."
                        "whisper_model_load_failed" ->
                            "Failed to load the Whisper model. Check the path in settings."
                        "speech_timeout", "no_match" ->
                            "I did not hear anything. Say dismiss, delete, reply, repeat, or stop."
                        "network_error", "server_error", "server_disconnected" ->
                            "Offline speech model is not available. Download it in Android settings."
                        "permission_denied" ->
                            "Microphone permission is required."
                        "recognition_unavailable" ->
                            "Speech recognition is not available on this device."
                        else ->
                            "Speech recognition error: $errorCode."
                    }

                    val retryable = (errorCode == "speech_timeout" || errorCode == "no_match") && errorCode != "mic_silenced"
                    if (retryable) {
                        // Silent restart on first attempt — no wasted time speaking
                        retryOrGiveUp(userMsg)
                    } else {
                        // Non-retryable error — bail out
                        speak(userMsg) {
                            mainHandler.post { listenState = ListenState.IDLE }
                        }
                    }
                }
            }
        )
    }

    /** Public: cancel a notification by key; usable from VoiceAssistantService. */
    fun dismissNotification(key: String) {
        try { cancelNotification(key) } catch (_: Exception) {}
        clearStoredNotification(key)
    }

    /**
     * Returns a snapshot of currently active non-ongoing notifications, suitable
     * for the voice assistant to read aloud. Skips our own ongoing notification
     * and silent/grouped children that have no useful text.
     */
    fun getVoiceAssistantNotifications(): List<NotifSnapshot> {
        if (!listenerConnected) return emptyList()
        val sbns = try { getActiveNotifications() } catch (_: Exception) { return emptyList() }
        val ownPkg = applicationContext.packageName
        return sbns.mapNotNull { sbn ->
            val flags = sbn.notification?.flags ?: 0
            if (flags and android.app.Notification.FLAG_ONGOING_EVENT != 0) return@mapNotNull null
            if (flags and android.app.Notification.FLAG_FOREGROUND_SERVICE != 0) return@mapNotNull null
            if (sbn.packageName == ownPkg) return@mapNotNull null
            val extras = sbn.notification?.extras ?: return@mapNotNull null
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val text = (extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
                ?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val appName = getAppName(sbn.packageName)
            val hasReplyAction = sbn.notification?.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true
            NotifSnapshot(sbn.packageName, sbn.key, appName, title, text, hasReplyAction)
        }
    }

    private fun stopListeningNow() {
        // Stop recording but keep the engine alive (model stays in memory)
        cachedSttEngine?.stopListening()
        stopMicService()
    }

    /**
     * Public: immediately stop all TTS and STT, reset state to IDLE.
     * Called from [DriveModeWidgetProvider] STOP button and OFF button.
     */
    fun stopReading() {
        Timber.d("Drive Mode: stopReading() called (widget or external)")
        mainHandler.post {
            ttsEngine?.stop()
            stopListeningNow()
            pendingNotification = null
            confirmWorkflow = null
            listenState = ListenState.IDLE
            totalVoiceAttempts = 0
        }
    }

    // ── Preference helpers ────────────────────────────────────────────────

    private fun prefs() = applicationContext.getSharedPreferences(
        "${applicationContext.packageName}_preferences", Context.MODE_PRIVATE
    )

    private fun isDriveModeEnabled(): Boolean = try {
        prefs().getBoolean("drive_mode_enabled", false)
    } catch (e: Exception) { false }

    private fun isVoiceReplyEnabled(): Boolean = try {
        prefs().getBoolean("drive_mode_voice_reply", false)
    } catch (e: Exception) { false }

    // ── App metadata ────────────────────────────────────────────────────────

    private fun getAppName(packageName: String): String = when (packageName) {
        "org.thoughtcrime.securesms"   -> "Signal"
        "com.whatsapp"                 -> "WhatsApp"
        "org.telegram.messenger"       -> "Telegram"
        "com.facebook.orca"            -> "Messenger"
        "com.google.android.apps.messaging",
        "com.android.messaging"        -> "SMS"
        "com.microsoft.teams"          -> "Teams"
        "com.microsoft.office.outlook" -> "Outlook"
        "eu.faircode.email"            -> "FairMail"
        "com.fsck.k9"                  -> "K-9 Mail"
        "com.google.android.gm"       -> "Gmail"
        "ch.protonmail.android"        -> "ProtonMail"
        "com.tutao.tutanota"           -> "Tutanota"
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
        packageName == "eu.faircode.email" ||                    // FairMail
        packageName == "com.fsck.k9" ||                           // K-9 Mail / Thunderbird
        packageName == "com.google.android.gm" ||                 // Gmail
        packageName == "ch.protonmail.android" ||                  // ProtonMail
        packageName == "com.tutao.tutanota" ||                     // Tutanota
        (BuildConfig.DEBUG && packageName == "com.android.shell") // debug builds only: adb test notifications

    private fun hasRecordPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
