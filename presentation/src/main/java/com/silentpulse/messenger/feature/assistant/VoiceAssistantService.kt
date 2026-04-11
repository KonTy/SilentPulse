package com.silentpulse.messenger.feature.assistant

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
// Language detection via Unicode script ranges — zero deps, fully offline
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import timber.log.Timber
import com.silentpulse.messenger.feature.drivemode.AndroidSttEngine
import com.silentpulse.messenger.feature.drivemode.NotifSnapshot
import com.silentpulse.messenger.feature.drivemode.SttEngine
import com.silentpulse.messenger.feature.drivemode.WidgetPrefs
import java.util.Locale
import java.util.UUID

private const val TAG = "VoiceAssistantSvc"

/**
 * Offline voice assistant — the "ears and mouth" of the SilentPulse ecosystem.
 *
 * ## Two-phase listening:
 *   **Phase 1 — Vosk keyword spotter (silent, low-CPU):**
 *   [VoskWakeWordDetector] runs Vosk with a grammar constrained to just
 *   `["computer", "[unk]"]`.  It reads raw PCM via AudioRecord — zero beeps,
 *   zero SpeechRecognizer restarts, minimal battery drain.
 *
 *   **Phase 2 — STT (one-shot):**
 *   When Vosk hears "Computer", it releases the mic and hands off to Android's
 *   [SpeechRecognizer] for a single free-form recognition pass.  The recognizer
 *   plays its natural beep — **this is the "ready" signal the user hears**.
 *   After the result (or error), STT shuts down and we return to Phase 1.
 *
 * This eliminates the infinite beep loop caused by restarting SpeechRecognizer
 * every 5 seconds as a fake always-on detector.
 *
 * All processing is fully on-device.  INTERNET permission is removed — kernel
 * blocks all outbound sockets.
 */
class VoiceAssistantService : Service(), TextToSpeech.OnInitListener {

    private var sttEngine: SttEngine? = null
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    @Volatile private var isListening = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var commandRouter: CommandRouter
    private lateinit var weatherHandler: WeatherCommandHandler
    private lateinit var navigationHandler: NavigationCommandHandler
    private lateinit var driveTimeHandler: DriveTimeHandler
    private lateinit var generalQueryHandler: GeneralQueryHandler
    private lateinit var webAiSearchScraper: WebAiSearchScraper
    private lateinit var stockQueryHandler: StockQueryHandler
    private lateinit var musicHandler: MusicCommandHandler
    private lateinit var notifReaderHandler: NotificationReaderHandler
    private lateinit var timeHandler: TimeHandler

    // ── Notification reading state ─────────────────────────────────────────
    private var notifReaderActive           = false
    private var notifReaderAwaitingReply    = false
    private var notifReaderList: List<NotifSnapshot> = emptyList()
    private var notifReaderIndex            = 0

    /**
     * Counts how many consecutive STT attempts returned an empty command
     * (user said "Computer" with no follow-up) or a no_match error.
     * Reset to 0 on any real command or when returning to wake-word detection.
     * Capped at MAX_STT_RETRIES — then we go back to wake word and stop prompting.
     */
    private var sttRetryCount = 0

    private val sessionManager = SessionManager()
    private var wakeWordDetector: VoskWakeWordDetector? = null
    private var voskModelReady = false

    companion object {
        const val WAKE_WORD = "computer"

        /** Max consecutive empty-command / no-match retries before giving up. */
        private const val MAX_STT_RETRIES = 2

        /** Delay before retrying after a fatal STT error. */
        private const val ERROR_RETRY_DELAY_MS = 2_000L
    }

    // ── Broadcast receiver for TTS replies from target apps ───────────────────

    private val ttsReplyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CommandRouter.ACTION_TTS_REPLY) {
                val spokenText = intent.getStringExtra(CommandRouter.EXTRA_SPOKEN_TEXT) ?: return
                val requireFollowup = intent.getBooleanExtra(CommandRouter.EXTRA_REQUIRE_FOLLOWUP, false)
                val sessionId = intent.getStringExtra(CommandRouter.EXTRA_SESSION_ID)
                if (android.util.Log.isLoggable("SP_XAPP", android.util.Log.DEBUG)) {
                    android.util.Log.d("SP_XAPP",
                        "[TTS_REPLY\u200b] text=\"$spokenText\" requireFollowup=$requireFollowup session=$sessionId")
                }

                if (requireFollowup) {
                    sessionManager.touch()
                    Log.d(TAG, "TTS reply (follow-up required, session=$sessionId): \"$spokenText\"")
                } else {
                    if (sessionId != null) sessionManager.close(sessionId)
                    Log.d(TAG, "TTS reply (final, session=$sessionId): \"$spokenText\"")
                }

                speak(spokenText) {
                    if (requireFollowup) {
                        // For follow-ups, go directly to STT (skip wake word)
                        startSttOneShot()
                    } else {
                        // Conversation done — back to wake word detection
                        resumeWakeWord()
                    }
                }
            }
        }
    }
    // ── Broadcast receiver for schema replies from target apps ────────────────
    private val schemaReplyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CommandRouter.ACTION_REPORT_SCHEMA) {
                val schemaJson = intent.getStringExtra(CommandRouter.EXTRA_SCHEMA_JSON) ?: return
                Log.d(TAG, "Schema reply: $schemaJson")
                speak("Available commands: $schemaJson") { resumeWakeWord() }
            }
        }
    }
    // ── Broadcast receiver for widget "Next notification" button ──────────────
    private val nextNotifReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WidgetPrefs.ACTION_NEXT_NOTIFICATION) return
            Log.d(TAG, "nextNotifReceiver: advance notif reader")
            if (notifReaderActive && notifReaderList.isNotEmpty()) {
                notifReaderIndex++
                readCurrentNotification()
            }
        }
    }
    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        tts = TextToSpeech(this, this)
        commandRouter = CommandRouter(applicationContext)
        commandRouter.refreshApps()
        weatherHandler = WeatherCommandHandler(applicationContext)
        navigationHandler = NavigationCommandHandler(applicationContext)
        driveTimeHandler = DriveTimeHandler(applicationContext)
        generalQueryHandler = GeneralQueryHandler(applicationContext)
        webAiSearchScraper  = WebAiSearchScraper(applicationContext)
        stockQueryHandler  = StockQueryHandler(applicationContext)
        musicHandler       = MusicCommandHandler(applicationContext)
        notifReaderHandler = NotificationReaderHandler(applicationContext)
        timeHandler        = TimeHandler()
        val filter = IntentFilter().apply {
            addAction(CommandRouter.ACTION_TTS_REPLY)
            addAction(CommandRouter.ACTION_REPORT_SCHEMA)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, ttsReplyReceiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        androidx.core.content.ContextCompat.registerReceiver(
            this, schemaReplyReceiver, IntentFilter(CommandRouter.ACTION_REPORT_SCHEMA),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        androidx.core.content.ContextCompat.registerReceiver(
            this, nextNotifReceiver,
            IntentFilter(WidgetPrefs.ACTION_NEXT_NOTIFICATION),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        initSttEngine()
        initVoskModel()
    }
    override fun onInit(status: Int) {
        Log.d(TAG, "TTS onInit status=$status (SUCCESS=0)")
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            ttsReady = true
            Log.d(TAG, "TTS ready")
            maybeStartListening()
        } else {
            Log.e(TAG, "TTS init FAILED")
            Timber.e("TTS Initialization failed")
        }
    }
    /**
     * Start listening only when both TTS *and* Vosk model are ready.
     * Whichever finishes last triggers the actual start.
     */
    private fun maybeStartListening() {
        Log.d(TAG, "maybeStartListening() ttsReady=$ttsReady voskModelReady=$voskModelReady")
        if (ttsReady && voskModelReady) {
            speak("Voice assistant ready.") { startWakeWordDetection() }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand()")
        val sp = applicationContext.getSharedPreferences(
            "${applicationContext.packageName}_preferences", Context.MODE_PRIVATE
        )
        if (!sp.getBoolean("drive_mode_wake_word", false)) {
            Log.d(TAG, "Wake word disabled — stopping self")
            stopSelf()
            return START_NOT_STICKY
        }
        val channelId = com.silentpulse.messenger.common.util.NotificationManagerImpl.DEFAULT_CHANNEL_ID
        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("SilentPulse Assistant")
            .setContentText("Listening for wake word\u2026")
            .setSmallIcon(com.silentpulse.messenger.R.drawable.ic_assistant_black_24dp)
            .setOngoing(true)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(777, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(777, notification)
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        super.onDestroy()
        isListening = false
        wakeWordDetector?.destroy()
        wakeWordDetector = null
        sttEngine?.stopListening()
        sttEngine?.shutdown()
        tts.stop()
        tts.shutdown()
        sessionManager.close()
        try { unregisterReceiver(ttsReplyReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(schemaReplyReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(nextNotifReceiver) } catch (_: Exception) {}
        webAiSearchScraper.destroy()
    }
    // ── Vosk model init ───────────────────────────────────────────────────────
    private fun initVoskModel() {
        wakeWordDetector = VoskWakeWordDetector(this)
        wakeWordDetector!!.init(
            onModelReady = {
                Log.d(TAG, "Vosk model ready")
                voskModelReady = true
                maybeStartListening()
            },
            onModelError = { error ->
                Log.e(TAG, "Vosk model failed: $error")
                speak("Wake word model failed to load. $error") {}
            }
        )
    }
    // ── Phase 1: Vosk keyword spotter (silent, no beeps, low CPU) ───────────
    private fun startWakeWordDetection() {
        Log.d(TAG, "Starting Vosk wake word detection")
        wakeWordDetector?.start(
            onWakeWordDetected = {
                Log.d(TAG, "WAKE WORD \"$WAKE_WORD\" detected — switching to STT")
                // Vosk already paused itself and released the mic.
                // Start SpeechRecognizer — its beep = "I'm ready" signal.
                startSttOneShot()
            },
            onListening = {
                Log.d(TAG, "Vosk listening for \"$WAKE_WORD\"")
            },
            onErr = { error ->
                Log.e(TAG, "Vosk error: $error")
                speak("Wake word error: $error") {
                    // Try to restart after a delay
                    mainHandler.postDelayed({ startWakeWordDetection() }, ERROR_RETRY_DELAY_MS)
                }
            }
        )
    }
    private fun resumeWakeWord() {
        Log.d(TAG, "Resuming Vosk wake word detection")
        isListening = false
        sttRetryCount = 0
        wakeWordDetector?.resume()
    }
    // ── Phase 2: One-shot SpeechRecognizer (single beep, then done) ─────────
    private fun initSttEngine() {
        Log.d(TAG, "Creating AndroidSttEngine (on-device, INTERNET blocked)")
        sttEngine = AndroidSttEngine(this)
    }
    private fun startSttOneShot(commandPrefix: String? = null) {
        if (isListening) return
        val engine = sttEngine ?: run {
            Log.w(TAG, "startSttOneShot() ABORTED — no STT engine")
            speak("Speech recognition unavailable.") { resumeWakeWord() }
            return
        }
        isListening = true
        if (commandPrefix != null) {
            Log.d(TAG, "STT one-shot — listening for command (prefix=\"$commandPrefix\")")
        } else {
            Log.d(TAG, "STT one-shot — listening for command")
        }
        engine.startListening(
            onResult = { transcript ->
                isListening = false
                // Stop the engine immediately — kills the internal continuation
                // restart so it can't fire a spurious no_match error callback.
                engine.stopListening()
                Log.d(TAG, "STT transcript: \"$transcript\"")
                // Strip any leading "computer" in case the user said it again
                // or it bled over from the wake word.
                val command = transcript
                    .lowercase(Locale.getDefault())
                    .removePrefix(WAKE_WORD)
                    .trim()
                    .removeSuffix(".")
                    .trim()
                if (command.isNotEmpty()) {
                    sttRetryCount = 0
                    val fullCommand = if (commandPrefix != null) {
                        "$commandPrefix $command"
                    } else {
                        command
                    }
                    routeCommand(fullCommand)
                } else {
                    // User only said "computer" again or something unintelligible.
                    // Allow up to MAX_STT_RETRIES re-prompts then give up.
                    sttRetryCount++
                    if (sttRetryCount < MAX_STT_RETRIES) {
                        speak("I'm listening.") { startSttOneShot() }
                    } else {
                        speak("Going back to wake word.") { resumeWakeWord() }
                    }
                }
            },
            onError = { errorCode ->
                isListening = false
                if (errorCode == "no_match" || errorCode == "speech_timeout") {
                    sttRetryCount++
                    if (sttRetryCount < MAX_STT_RETRIES) {
                        Log.d(TAG, "STT $errorCode — retry $sttRetryCount/$MAX_STT_RETRIES")
                        speak("I didn't catch that. Try again.") { startSttOneShot() }
                    } else {
                        Log.d(TAG, "STT $errorCode — max retries reached, resuming wake word")
                        speak("I didn't catch that. Say Computer to try again.") {
                            resumeWakeWord()
                        }
                    }
                } else {
                    Log.e(TAG, "STT error: $errorCode — telling user, retry after delay")
                    speak("Speech recognition error.") {
                        mainHandler.postDelayed({ resumeWakeWord() }, ERROR_RETRY_DELAY_MS)
                    }
                }
            }
        )
    }
    // ── Command routing ───────────────────────────────────────────────────────
    private fun routeCommand(command: String) {
        Log.d(TAG, "routeCommand(\"$command\")")
        if (android.util.Log.isLoggable("SP_ROUTE", android.util.Log.VERBOSE)) {
            android.util.Log.v("SP_ROUTE", "[ROUTE] transcript=\"$command\" knownApps=${commandRouter.getAppNames()}")
        }
        val c = command.lowercase(Locale.getDefault())
        // ── 0. Notification reading mode intercept ────────────────
        if (notifReaderActive) {
            if (notifReaderAwaitingReply) handleNotifReplyText(command)
            else handleNotifReaderCommand(c)
            return
        }
        // ── 1. Active follow-up session? Route back to same app ──────────────
        val activeSession = sessionManager.getActive()
        if (activeSession != null) {
            Log.d("SP_SESSION",
                    "[FOLLOW-UP\u200b] re-routing to ${activeSession.appLabel} (${activeSession.targetPackage}) session=${activeSession.sessionId}")
            speak("Sending to ${activeSession.appLabel}.") {
                commandRouter.dispatch(
                    CommandRouter.RouteResult(
                        targetPackage = activeSession.targetPackage,
                        appLabel = activeSession.appLabel,
                        rawCommand = command
                    ),
                    activeSession.sessionId
                )
                // Wait for TTS_REPLY broadcast from the app
            }
            return
        }
        // ── 2. Built-in: time queries ──────────────────────────────────
        if (timeHandler.isTimeCommand(c)) {
            Log.d(TAG, "Time command detected")
            val response = timeHandler.getTimeResponse(command)
            speak(response) { resumeWakeWord() }
            return
        }
        // ── 3. Built-in: weather queries ─────────────────────────────────
        if (weatherHandler.isWeatherCommand(c)) {
            Log.d(TAG, "Weather command detected")
            val isCorridorQuery = Regex("(?:along|on)\\s+i-?\\d+").containsMatchIn(c)
            if (isCorridorQuery) {
                speak("Fetching corridor weather. Stand by.") {
                    weatherHandler.fetchAndSpeak(
                        command = c,
                        onResult  = { finalText -> speak(finalText) { resumeWakeWord() } },
                        onSegment = { cityText  -> speakQueued(cityText) }
                    )
                }
            } else {
                speak("Checking the weather. One moment.") {
                    weatherHandler.fetchAndSpeak(
                        command  = c,
                        onResult = { weatherText -> speak(weatherText) { resumeWakeWord() } }
                    )
                }
            }
            return
        }
        // ── 3. Built-in: stop navigation ────────────────────────────
        if (navigationHandler.isStopNavigationCommand(c)) {
            Log.d(TAG, "Stop navigation command detected")
            navigationHandler.stopNavigation(command) { text, onDone ->
                speak(text) { onDone?.invoke(); resumeWakeWord() }
            }
            return
        }
        // ── 4. Built-in: navigation / directions ───────────────────────
        if (navigationHandler.isNavigationCommand(c)) {
            Log.d(TAG, "Navigation command detected")
            navigationHandler.handleNavigation(command) { text, onDone ->
                speak(text) { onDone?.invoke(); resumeWakeWord() }
            }
            return
        }
        // ── 4. Built-in: drive time queries ────────────────────────────────
        if (driveTimeHandler.isDriveTimeCommand(c)) {
            Log.d(TAG, "Drive time command detected")
            speak("Calculating drive time. One moment.") {
                driveTimeHandler.fetchAndSpeak(c) { result ->
                    speak(result) { resumeWakeWord() }
                }
            }
            return
        }
        // ── 4b. Resume any media session ───────────────────────────────────────────
        if (musicHandler.isResumeCommand(c)) {
            musicHandler.resumeMedia()
            speak("Resuming.") { resumeWakeWord() }
            return
        }
        // ── 4c. Audiobook via Voice app ──────────────────────────────────────────────
        if (musicHandler.isBookCommand(c)) {
            speak("Opening your audiobook.") {
                musicHandler.handleBook(command) { result -> speak(result) { resumeWakeWord() } }
            }
            return
        }
        // ── 4d. Music playback via VLC ────────────────────────────────────────────────
        if (musicHandler.isMusicCommand(c)) {
            speak("Searching your music library.") {
                musicHandler.handlePlay(command) { result -> speak(result) { resumeWakeWord() } }
            }
            return
        }
        // ── 4e. Read notifications ───────────────────────────────────────────────────────
        if (notifReaderHandler.isReadNotificationsCommand(c)) {
            startNotificationReading()
            return
        }
        // ── 4f. Read unread emails only ─────────────────────────────────────
        if (notifReaderHandler.isReadEmailCommand(c)) {
            startEmailReading()
            return
        }
        // ── 4. Built-in: "what apps can you talk to?" ───────────────────────
        if (c.contains("what apps") || c.contains("which apps") || c.contains("who can you talk to")) {
            commandRouter.refreshApps()
            val names = commandRouter.getAppNames()
            val text = if (names.isEmpty()) {
                "I don't see any assistant-capable apps installed."
            } else {
                "I can talk to: ${names.joinToString(", ")}."
            }
            speak(text) { resumeWakeWord() }
            return
        }
        // ── 3. Built-in: help ────────────────────────────────────────────────
        if (c.contains("what can you do") || c.contains("help") || c.contains("available commands")) {
            commandRouter.refreshApps()
            val names = commandRouter.getAppNames()
            val appList = if (names.isNotEmpty()) {
                " I can also talk to ${names.joinToString(", ")}. Say the app name in your command."
            } else ""
            speak("Here is what I can do. Navigation: say navigate to, or directions to, followed by a destination. Say stop navigation to end. Weather: say weather in a city, or what is the weather. Drive time: say how long to, followed by a destination. Music: say play music followed by a song or artist. Say resume to continue paused media. Audiobooks: say listen to book or open voice. Notifications and Email: say read my notifications, or read my email. Then say skip, reply, repeat, dismiss, or stop. Stock prices: say price of gold, bitcoin, or any ticker. General questions: ask any what, who, where, when, or how question. Apps: say open or close followed by an app name.$appList") {
                resumeWakeWord()
            }
            return
        }
        // ── 4a. Built-in: "close <app>" / "quit <app>" / "kill <app>" ──────────
        if (c.contains("close ") || c.contains("quit ") || c.contains("kill ")) {
            val appName = c
                .replace(Regex("^.*(close|quit|kill)\\s+"), "")
                .trim()
            if (appName.isNotEmpty()) {
                closeApp(appName)
                return
            }
        }
        // ── 4. Built-in: "open <app>" / "launch <app>" ───────────────────────
        if (c.contains("open ") || c.contains("launch ") || c.contains("start ")) {
            val appName = c
                .replace(Regex("^.*(open|launch|start)\\s+"), "")
                .trim()
            // Intercept "open/launch google maps" — launch Google Maps directly
            if (appName.contains("google maps") || appName == "google map") {
                val pm = packageManager
                val googleMapsPkg = "com.google.android.apps.maps"
                val mapsInstalled = try { pm.getPackageInfo(googleMapsPkg, 0); true }
                    catch (_: Exception) { false }
                if (mapsInstalled) {
                    val intent = pm.getLaunchIntentForPackage(googleMapsPkg)?.apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    speak("Opening Google Maps.") {
                        try {
                            if (intent != null) {
                                val pi = android.app.PendingIntent.getActivity(
                                    this@VoiceAssistantService, 10, intent,
                                    android.app.PendingIntent.FLAG_IMMUTABLE or
                                    android.app.PendingIntent.FLAG_CANCEL_CURRENT
                                )
                                val opts = android.app.ActivityOptions.makeBasic().apply {
                                    setPendingIntentBackgroundActivityStartMode(
                                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                                    )
                                }
                                pi.send(this@VoiceAssistantService, 0, null, null, null, null, opts.toBundle())
                            }
                        } catch (e: Exception) { Log.e(TAG, "Google Maps open failed", e) }
                        resumeWakeWord()
                    }
                } else {
                    speak("Google Maps is not installed.") { resumeWakeWord() }
                }
                return
            }
            if (appName.isNotEmpty()) {
                launchApp(appName)
                return
            }
        }
        // ── 5. Built-in: "give me commands in <app>" / "what can <app> do?" ─
        if (c.contains("commands in") || c.contains("what can") && c.contains("do")) {
            commandRouter.refreshApps()
            val routeResult = commandRouter.route(command)
            if (routeResult != null) {
                commandRouter.requestSchema(routeResult.targetPackage)
                speak("Asking ${routeResult.appLabel} for its commands.") { /* wait for schema reply */ }
            } else {
                speak("I'm not sure which app you mean.") { resumeWakeWord() }
            }
            return
        }
        // ── 6. Try cross-app routing via CommandRouter ───────────────────────
        val routeResult = commandRouter.route(command)
        if (routeResult != null) {
            val sessionId = sessionManager.open(routeResult.targetPackage, routeResult.appLabel)
            speak("Sending to ${routeResult.appLabel}.") {
                commandRouter.dispatch(routeResult, sessionId)
                Log.d("SP_XAPP",
                    "[DISPATCH\u200b] ➡ ${routeResult.appLabel} (${routeResult.targetPackage}) cmd=\"${routeResult.rawCommand}\" session=$sessionId")
                // Wait for TTS_REPLY broadcast from the app
            }
            return
        }
        // ── 6b. Stock price query ───────────────────────────────────────────────
        if (stockQueryHandler.isStockQuery(c)) {
            Log.d(TAG, "Stock query detected")
            speak("Looking up the stock price.") {
                stockQueryHandler.fetchPrice(command) { answer ->
                    speak(answer) { resumeWakeWord() }
                }
            }
            return
        }
        // ── 7. General knowledge queries ──────────────────────────────────────
        // "bing <query>" → route explicitly to Bing WebView
        if (c.startsWith("bing ")) {
            val bingQuery = command.drop(5).trim()
            if (bingQuery.isNotEmpty()) {
                Log.d(TAG, "Bing explicit query: \"$bingQuery\"")
                speak("Asking Bing.") {
                    webAiSearchScraper.search(bingQuery, WebAiSearchScraper.Source.BING) { answer ->
                        if (answer != null) {
                            speak(answer) { resumeWakeWord() }
                        } else {
                            generalQueryHandler.fetchAndSpeak(bingQuery) { a ->
                                speak(a) { resumeWakeWord() }
                            }
                        }
                    }
                }
                return
            }
        }
        // Default general questions → Brave Search only (fast, no Bing fallback)
        // Anything that didn't match above — try Brave AI, fall back to DDG/Wikipedia
        if (c.contains("?") || c.startsWith("what") || c.startsWith("who") ||
            c.startsWith("where") || c.startsWith("when") || c.startsWith("why") ||
            c.startsWith("how") || c.startsWith("is ") || c.startsWith("are ") ||
            c.startsWith("does ") || c.startsWith("can ") || c.startsWith("tell me")
        ) {
            Log.d(TAG, "General query — trying Brave AI")
            speak("Let me look that up.") {
                webAiSearchScraper.search(command, WebAiSearchScraper.Source.BRAVE) { aiAnswer ->
                    if (aiAnswer != null) {
                        speak(aiAnswer) { resumeWakeWord() }
                    } else {
                        Log.d(TAG, "Brave returned nothing — falling back to DuckDuckGo")
                        generalQueryHandler.fetchAndSpeak(command) { answer ->
                            speak(answer) { resumeWakeWord() }
                        }
                    }
                }
            }
            return
        }
        // ── 8. Just an app name with no command — re-listen ─────────────────
        val appMatch = commandRouter.findAppByName(c)
        if (appMatch != null) {
            Log.d(TAG, "App name only (\"${appMatch.label}\") — re-listening for command")
            speak("What would you like ${appMatch.label} to do?") {
                startSttOneShot(commandPrefix = appMatch.labelLower)
            }
            return
        }

        // ── 9. No app matched — tell the user ────────────────────────────────
        Log.d(TAG, "No app matched for: \"$command\"")
        speak("Sorry, I don't know how to handle that. Say open and an app name, or try an assistant command.") {
            resumeWakeWord()
        }
    }
    // ── Notification reading ───────────────────────────────────────────────
    private fun startNotificationReading() {
        Log.d(TAG, "startNotificationReading()")
        notifReaderList  = notifReaderHandler.fetchNotifications()
        notifReaderIndex = 0
        if (notifReaderList.isEmpty()) {
            speak("You have no notifications.") { resumeWakeWord() }
            return
        }
        notifReaderActive = true
        val count = notifReaderList.size
        val countSuffix = if (count > 1) "s" else ""
        speak("You have $count notification$countSuffix.", bargeIn = false) { readCurrentNotification() }
    }

    private fun startEmailReading() {
        Log.d(TAG, "startEmailReading()")
        notifReaderList  = notifReaderHandler.fetchEmailNotifications()
        notifReaderIndex = 0
        if (notifReaderList.isEmpty()) {
            speak("You have no unread emails.") { resumeWakeWord() }
            return
        }
        notifReaderActive = true
        val count = notifReaderList.size
        val countSuffix = if (count > 1) "s" else ""
        speak("You have $count unread email$countSuffix.", bargeIn = false) { readCurrentNotification() }
    }

    private fun readCurrentNotification() {
        Log.d(TAG, "readCurrentNotification() index=$notifReaderIndex/${notifReaderList.size}")
        if (notifReaderIndex >= notifReaderList.size) {
            Log.d(TAG, "notifReader: all done, no more notifications")
            notifReaderActive = false
            speak("No more notifications.", bargeIn = false) { resumeWakeWord() }
            return
        }
        val item   = notifReaderList[notifReaderIndex]
        val text   = notifReaderHandler.formatForSpeech(item, notifReaderIndex + 1, notifReaderList.size)
        val prompt = notifReaderHandler.promptForCommands(item)
        speak("$text. $prompt", bargeIn = false) { startSttOneShot() }
    }
    private fun handleNotifReaderCommand(c: String) {
        Log.d(TAG, "handleNotifReaderCommand(\"$c\") index=$notifReaderIndex/${notifReaderList.size}")
        val item = notifReaderList.getOrNull(notifReaderIndex)
        when {
            notifReaderHandler.isDismissCommand(c) -> {
                Log.d(TAG, "notifReader: DELETE key=${item?.key}")
                if (item != null) notifReaderHandler.dismiss(item.key)
                notifReaderIndex++
                speak("Deleted.", bargeIn = false) { readCurrentNotification() }
            }
            notifReaderHandler.isReplyCommand(c) -> {
                Log.d(TAG, "notifReader: REPLY (hasReplyAction=${item?.hasReplyAction})")
                if (item == null || !item.hasReplyAction) {
                    speak("This notification doesn't support replies. Say delete or repeat.", bargeIn = false) { startSttOneShot() }
                } else {
                    notifReaderAwaitingReply = true
                    speak("What would you like to say?", bargeIn = false) { startSttOneShot() }
                }
            }
            notifReaderHandler.isRepeatCommand(c) -> {
                Log.d(TAG, "notifReader: REPEAT")
                readCurrentNotification()
            }
            else -> {
                Log.d(TAG, "notifReader: UNRECOGNIZED command \"$c\"")
                val hint = if (item?.hasReplyAction == true)
                    "Say reply, delete, or repeat."
                else "Say delete or repeat."
                speak(hint, bargeIn = false) { startSttOneShot() }
            }
        }
    }
    private fun handleNotifReplyText(replyText: String) {
        Log.d(TAG, "handleNotifReplyText() len=${replyText.length}")
        notifReaderAwaitingReply = false
        val item = notifReaderList.getOrNull(notifReaderIndex)
        if (item == null) { notifReaderActive = false; resumeWakeWord(); return }
        val sent = notifReaderHandler.sendReply(item.key, replyText)
        notifReaderIndex++
        val replyMsg = if (sent) "Sent: $replyText." else "Couldn't send the reply. Moving on."
        speak(replyMsg, bargeIn = false) { readCurrentNotification() }
    }
    // ── App launcher ─────────────────────────────────────────────────────────
    /**
     * Fuzzy-match an app name from the user's speech against installed apps
     * and launch it.  Uses Levenshtein distance for tolerance.
     */
    private fun launchApp(spokenName: String) {
        val pm = packageManager
        val launchableApps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        data class Match(val label: String, val packageName: String, val distance: Int)
        val target = spokenName.lowercase(Locale.getDefault())
        val matches = launchableApps.mapNotNull { ri ->
            val label = ri.loadLabel(pm).toString()
            val labelLower = label.lowercase(Locale.getDefault())
            // Exact substring match gets distance 0
            val dist = if (labelLower.contains(target) || target.contains(labelLower)) {
                0
            } else {
                levenshtein(target, labelLower)
            }
            // Only consider reasonable matches (distance <= 40% of target length)
            if (dist <= (target.length * 0.4).toInt().coerceAtLeast(3)) {
                Match(label, ri.activityInfo.packageName, dist)
            } else null
        }.sortedBy { it.distance }
        val best = matches.firstOrNull()
        if (best != null) {
            val launchIntent = pm.getLaunchIntentForPackage(best.packageName)
            if (launchIntent != null) {
                Log.d(TAG, "Launching app: ${best.label} (${best.packageName})")
                speak("Opening ${best.label}.") {
                    startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    resumeWakeWord()
                }
            } else {
                speak("Found ${best.label} but can't launch it.") { resumeWakeWord() }
            }
        } else {
            speak("I couldn't find an app called $spokenName.") { resumeWakeWord() }
        }
    }
    /**
     * Fuzzy-match an app by name, dismiss it to the home screen, then kill it.
     * killBackgroundProcesses only works on background processes, so we first
     * send the device home (which backgrounds the target app) and then kill it
     * after a short delay.  For navigation apps we also fire the notification
     * stop action so turn-by-turn guidance stops cleanly.
     */
    private fun closeApp(spokenName: String) {
        val pm = packageManager
        val launchableApps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        data class Match(val label: String, val packageName: String, val distance: Int)
        val target = spokenName.lowercase(Locale.getDefault())
        val matches = launchableApps.mapNotNull { ri ->
            val label = ri.loadLabel(pm).toString()
            val labelLower = label.lowercase(Locale.getDefault())
            val dist = if (labelLower.contains(target) || target.contains(labelLower)) 0
                       else levenshtein(target, labelLower)
            if (dist <= (target.length * 0.4).toInt().coerceAtLeast(3))
                Match(label, ri.activityInfo.packageName, dist)
            else null
        }.sortedBy { it.distance }
        val best = matches.firstOrNull()
        if (best == null) {
            speak("I couldn't find an app called $spokenName.") { resumeWakeWord() }
            return
        }
        Log.d(TAG, "Closing app: ${best.label} (${best.packageName})")
        val navPackages = setOf(
            "com.google.android.apps.maps",
            "net.osmand", "net.osmand.plus", "net.osmand.dev",
            "app.organicmaps", "app.organicmaps.debug"
        )
        val isNavApp = best.packageName in navPackages
        speak("Closing ${best.label}.") {
            if (isNavApp) {
                // Fire the notification Stop action — ends turn-by-turn guidance.
                com.silentpulse.messenger.feature.drivemode.SilentPulseNotificationListener
                    .fireStopNavAction(best.packageName)
            }
            // Go home — pushes whatever is foreground to the background.
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { startActivity(homeIntent) } catch (_: Exception) {}
            // Attempt background kill for all apps after the home transition.
            // Works for most apps; silently no-ops for system-protected ones (e.g. Google Maps).
            android.os.Handler(mainLooper).postDelayed({
                val am = getSystemService(android.app.ActivityManager::class.java)
                am?.killBackgroundProcesses(best.packageName)
                Log.d(TAG, "killBackgroundProcesses attempted for ${best.packageName}")
            }, 800)
            resumeWakeWord()
        }
    }
    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }
    // ── TTS helper ────────────────────────────────────────────────────────────
    /**
     * Speak [text] and optionally run [onDone] when the utterance finishes.
     *
     * While TTS is playing, a lightweight Vosk stop-listener runs concurrently
     * on the mic.  If the user says **"stop"** or **"computer stop"**, TTS is
     * killed immediately, the notification reader (if active) is cancelled, and
     * the assistant returns to wake-word mode without calling [onDone].
     */
    private fun speak(text: String, bargeIn: Boolean = true, onDone: (() -> Unit)? = null) {
        if (!ttsReady) {
            onDone?.invoke()
            return
        }
        // Detect language via Unicode script ranges — zero external deps
        val detectedLocale = detectLocaleByScript(text)
        if (detectedLocale != null) {
            val available = tts.isLanguageAvailable(detectedLocale)
            if (available >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = detectedLocale
                Log.d(TAG, "TTS language set to ${detectedLocale.toLanguageTag()}")
            } else {
                Log.w(TAG, "TTS locale ${detectedLocale.toLanguageTag()} not available, keeping English")
                tts.language = Locale.US
            }
        } else {
            tts.language = Locale.US
        }
        speakInternal(text, bargeIn, onDone)
    }
    /**
     * Detect the primary language from Unicode script ranges.
     * Returns a [Locale] for the dominant non-Latin script, or null if Latin/ASCII
     * (which stays English).  Zero external dependencies — fully offline.
     */
    private fun detectLocaleByScript(text: String): Locale? {
        var cyrillic = 0; var arabic = 0; var cjk = 0; var hangul = 0
        var kana = 0; var devanagari = 0; var thai = 0; var hebrew = 0
        var greek = 0; var total = 0
        for (c in text) {
            if (c.isWhitespace() || c.isDigit()) continue
            total++
            when {
                c in 'Ѐ'..'ӿ' || c in 'Ԁ'..'ԯ' -> cyrillic++
                c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿' -> arabic++
                c in '一'..'鿿' || c in '㐀'..'䶿' || c in '豈'..'﫿' -> cjk++
                c in '가'..'힯' || c in 'ᄀ'..'ᇿ' -> hangul++
                c in '぀'..'ゟ' || c in '゠'..'ヿ' -> kana++
                c in 'ऀ'..'ॿ' -> devanagari++
                c in '฀'..'๿' -> thai++
                c in '֐'..'׿' || c in 'יִ'..'ﭏ' -> hebrew++
                c in 'Ͱ'..'Ͽ' -> greek++
            }
        }
        if (total == 0) return null
        val threshold = total * 0.3 // 30% of non-whitespace chars
        return when {
            cyrillic > threshold    -> Locale("ru")
            arabic > threshold      -> Locale("ar")
            cjk > threshold && kana > 0 -> Locale("ja")  // CJK + kana = Japanese
            cjk > threshold         -> Locale.SIMPLIFIED_CHINESE
            hangul > threshold      -> Locale.KOREAN
            kana > threshold        -> Locale("ja")
            devanagari > threshold  -> Locale("hi")
            thai > threshold        -> Locale("th")
            hebrew > threshold      -> Locale("he")
            greek > threshold       -> Locale("el")
            else                    -> null // Latin or mixed — keep English
        }
    }
    /** Speak after language has been detected and set. */
    private fun speakInternal(text: String, bargeIn: Boolean, onDone: (() -> Unit)?) {
        val preview = if (text.length > 80) text.take(80) + "…" else text
        Log.d(TAG, "TTS speak (bargeIn=$bargeIn): \"$preview\"")
        val utteranceId = UUID.randomUUID().toString()
        // Barge-in: run Vosk stop-listener while TTS is speaking
        if (bargeIn) {
            wakeWordDetector?.startStopListening {
                Log.d(TAG, "Barge-in: user said STOP — killing TTS")
                mainHandler.post {
                    tts.stop()
                    wakeWordDetector?.stopStopListening()
                    if (notifReaderActive) {
                        notifReaderActive = false
                        notifReaderAwaitingReply = false
                    }
                    speak("Stopped.", bargeIn = false) { resumeWakeWord() }
                }
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    Log.d(TAG, "TTS utterance done")
                    wakeWordDetector?.stopStopListening()
                    onDone?.invoke()
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId) {
                    Log.w(TAG, "TTS utterance ERROR")
                    wakeWordDetector?.stopStopListening()
                    onDone?.invoke()
                }
            }
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }
    /**
     * Enqueue a TTS utterance after whatever is already playing (QUEUE_ADD).
     * Use this for corridor weather so each city is spoken in sequence
     * without interrupting the previous one.
     */
    private fun speakQueued(text: String) {
        if (!ttsReady) return
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }
}
