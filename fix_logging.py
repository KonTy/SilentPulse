#!/usr/bin/env python3
"""Add comprehensive voice-command logging across all relevant files."""
import os

BASE = 'presentation/src/main/java/com/silentpulse/messenger/feature'

def patch(filepath, patches):
    """Apply a list of (old, new) replacements to a file."""
    with open(filepath, 'r') as f:
        content = f.read()
    for old, new in patches:
        if old in content:
            content = content.replace(old, new, 1)
            label = old.strip()[:60].replace('\n', ' ')
            print(f"  ✓ {os.path.basename(filepath)}: {label}…")
        else:
            label = old.strip()[:60].replace('\n', ' ')
            print(f"  ✗ {os.path.basename(filepath)}: NOT FOUND: {label}…")
    with open(filepath, 'w') as f:
        f.write(content)


# ═══════════════════════════════════════════════════════════════════════════
# 1. VoiceAssistantService.kt — add logging for TTS, notification reader,
#    command routing, and state transitions
# ═══════════════════════════════════════════════════════════════════════════
vas = f'{BASE}/assistant/VoiceAssistantService.kt'
patch(vas, [
    # 1a. Log what TTS is speaking (truncated for privacy)
    (
        '    /** Speak after language has been detected and set. */\n'
        '    private fun speakInternal(text: String, bargeIn: Boolean, onDone: (() -> Unit)?) {\n'
        '        val utteranceId = UUID.randomUUID().toString()',
        '    /** Speak after language has been detected and set. */\n'
        '    private fun speakInternal(text: String, bargeIn: Boolean, onDone: (() -> Unit)?) {\n'
        '        val preview = if (text.length > 80) text.take(80) + "…" else text\n'
        '        Log.d(TAG, "TTS speak (bargeIn=$bargeIn): \\"$preview\\"")\n'
        '        val utteranceId = UUID.randomUUID().toString()'
    ),
    # 1b. Log TTS utterance done
    (
        '            override fun onDone(id: String?) {\n'
        '                if (id == utteranceId) {\n'
        '                    wakeWordDetector?.stopStopListening()\n'
        '                    onDone?.invoke()',
        '            override fun onDone(id: String?) {\n'
        '                if (id == utteranceId) {\n'
        '                    Log.d(TAG, "TTS utterance done")\n'
        '                    wakeWordDetector?.stopStopListening()\n'
        '                    onDone?.invoke()'
    ),
    # 1c. Log TTS utterance error
    (
        '            @Deprecated("Deprecated in Java")\n'
        '            override fun onError(id: String?) {\n'
        '                if (id == utteranceId) {\n'
        '                    wakeWordDetector?.stopStopListening()\n'
        '                    onDone?.invoke()',
        '            @Deprecated("Deprecated in Java")\n'
        '            override fun onError(id: String?) {\n'
        '                if (id == utteranceId) {\n'
        '                    Log.w(TAG, "TTS utterance ERROR")\n'
        '                    wakeWordDetector?.stopStopListening()\n'
        '                    onDone?.invoke()'
    ),
    # 1d. Log notification reading start
    (
        '    private fun startNotificationReading() {\n'
        '        notifReaderList  = notifReaderHandler.fetchNotifications()',
        '    private fun startNotificationReading() {\n'
        '        Log.d(TAG, "startNotificationReading()")\n'
        '        notifReaderList  = notifReaderHandler.fetchNotifications()'
    ),
    # 1e. Log email reading start
    (
        '    private fun startEmailReading() {\n'
        '        notifReaderList  = notifReaderHandler.fetchEmailNotifications()',
        '    private fun startEmailReading() {\n'
        '        Log.d(TAG, "startEmailReading()")\n'
        '        notifReaderList  = notifReaderHandler.fetchEmailNotifications()'
    ),
    # 1f. Log reading current notification
    (
        '    private fun readCurrentNotification() {\n'
        '        if (notifReaderIndex >= notifReaderList.size) {',
        '    private fun readCurrentNotification() {\n'
        '        Log.d(TAG, "readCurrentNotification() index=$notifReaderIndex/${notifReaderList.size}")\n'
        '        if (notifReaderIndex >= notifReaderList.size) {'
    ),
    # 1g. Log notification reader command routing
    (
        '    private fun handleNotifReaderCommand(c: String) {\n'
        '        val item = notifReaderList.getOrNull(notifReaderIndex)\n'
        '        when {',
        '    private fun handleNotifReaderCommand(c: String) {\n'
        '        Log.d(TAG, "handleNotifReaderCommand(\\"$c\\") index=$notifReaderIndex/${notifReaderList.size}")\n'
        '        val item = notifReaderList.getOrNull(notifReaderIndex)\n'
        '        when {'
    ),
    # 1h. Log stop command
    (
        '            notifReaderHandler.isStopCommand(c) -> {\n'
        '                notifReaderActive = false\n'
        '                speak("Done reading notifications.",',
        '            notifReaderHandler.isStopCommand(c) -> {\n'
        '                Log.d(TAG, "notifReader: STOP command")\n'
        '                notifReaderActive = false\n'
        '                speak("Done reading notifications.,'
    ),
    # 1i. Log skip command
    (
        '            notifReaderHandler.isSkipCommand(c) -> {\n'
        '                notifReaderIndex++\n'
        '                readCurrentNotification()',
        '            notifReaderHandler.isSkipCommand(c) -> {\n'
        '                Log.d(TAG, "notifReader: SKIP → index=${notifReaderIndex + 1}")\n'
        '                notifReaderIndex++\n'
        '                readCurrentNotification()'
    ),
    # 1j. Log dismiss command
    (
        '            notifReaderHandler.isDismissCommand(c) -> {\n'
        '                if (item != null) notifReaderHandler.dismiss(item.key)',
        '            notifReaderHandler.isDismissCommand(c) -> {\n'
        '                Log.d(TAG, "notifReader: DISMISS key=${item?.key}")\n'
        '                if (item != null) notifReaderHandler.dismiss(item.key)'
    ),
    # 1k. Log reply command
    (
        '            notifReaderHandler.isReplyCommand(c) -> {\n'
        '                if (item == null || !item.hasReplyAction) {',
        '            notifReaderHandler.isReplyCommand(c) -> {\n'
        '                Log.d(TAG, "notifReader: REPLY (hasReplyAction=${item?.hasReplyAction})")\n'
        '                if (item == null || !item.hasReplyAction) {'
    ),
    # 1l. Log repeat command
    (
        '            notifReaderHandler.isRepeatCommand(c) -> readCurrentNotification()',
        '            notifReaderHandler.isRepeatCommand(c) -> {\n'
        '                Log.d(TAG, "notifReader: REPEAT")\n'
        '                readCurrentNotification()\n'
        '            }'
    ),
    # 1m. Log unrecognized command
    (
        '            else -> {\n'
        '                val hint = if (item?.hasReplyAction == true)',
        '            else -> {\n'
        '                Log.d(TAG, "notifReader: UNRECOGNIZED command \\"$c\\"")\n'
        '                val hint = if (item?.hasReplyAction == true)'
    ),
    # 1n. Log reply text handling
    (
        '    private fun handleNotifReplyText(replyText: String) {\n'
        '        notifReaderAwaitingReply = false',
        '    private fun handleNotifReplyText(replyText: String) {\n'
        '        Log.d(TAG, "handleNotifReplyText() len=${replyText.length}")\n'
        '        notifReaderAwaitingReply = false'
    ),
    # 1o. Log maybeStartListening decision
    (
        '    private fun maybeStartListening() {\n'
        '        if (ttsReady && voskModelReady) {',
        '    private fun maybeStartListening() {\n'
        '        Log.d(TAG, "maybeStartListening() ttsReady=$ttsReady voskModelReady=$voskModelReady")\n'
        '        if (ttsReady && voskModelReady) {'
    ),
    # 1p. Log notification reading early exit (all done)
    (
        '            notifReaderActive = false\n'
        '            speak("No more notifications.",',
        '            Log.d(TAG, "notifReader: all done, no more notifications")\n'
        '            notifReaderActive = false\n'
        '            speak("No more notifications.,'
    ),
])


# ═══════════════════════════════════════════════════════════════════════════
# 2. NotificationReaderHandler.kt — add logging for command matching
# ═══════════════════════════════════════════════════════════════════════════
nrh = f'{BASE}/assistant/NotificationReaderHandler.kt'
patch(nrh, [
    # 2a. Log isReadNotificationsCommand
    (
        '    fun isReadNotificationsCommand(c: String): Boolean =\n'
        '        c.contains("read notification")',
        '    fun isReadNotificationsCommand(c: String): Boolean {\n'
        '        val result = c.contains("read notification")',
    ),
    # Fix the end of isReadNotificationsCommand — change `=` chain to `val result = ...`
    # This needs more surgical approach — let's use a different strategy.
])

# Actually, the command matchers are all expression-body functions.
# Converting them to block-body just for logging is too invasive and error-prone
# with string replacement. Instead, let's add a wrapper log in the service
# where these are CALLED. We already added logging in handleNotifReaderCommand.
# Let's add logging at the entry-point detection instead.

# Revert the bad partial patch on NRH
with open(nrh, 'r') as f:
    nrh_content = f.read()
# Undo the partial replacement if it happened
nrh_content = nrh_content.replace(
    '    fun isReadNotificationsCommand(c: String): Boolean {\n'
    '        val result = c.contains("read notification")',
    '    fun isReadNotificationsCommand(c: String): Boolean =\n'
    '        c.contains("read notification")'
)
with open(nrh, 'w') as f:
    f.write(nrh_content)
print("  ✓ NotificationReaderHandler.kt: reverted partial patch")


# ═══════════════════════════════════════════════════════════════════════════
# 3. VoiceCommandProcessor.kt — add entry and branch logging
# ═══════════════════════════════════════════════════════════════════════════
vcp = f'{BASE}/drivemode/VoiceCommandProcessor.kt'
patch(vcp, [
    # 3a. Log processCommand entry
    (
        '    fun processCommand(command: String, notificationContext: NotificationContext?) {\n'
        '        when {',
        '    fun processCommand(command: String, notificationContext: NotificationContext?) {\n'
        '        Timber.d("processCommand(\\"$command\\") context=${notificationContext?.app}/${notificationContext?.sender}")\n'
        '        when {'
    ),
    # 3b. Log read branch
    (
        '            command.contains("read", ignoreCase = true) -> {\n'
        '                notificationContext?.let { readMessage(it) }',
        '            command.contains("read", ignoreCase = true) -> {\n'
        '                Timber.d("VCP: READ command")\n'
        '                notificationContext?.let { readMessage(it) }'
    ),
    # 3c. Log reply branch
    (
        '            command.contains("reply", ignoreCase = true) || command.contains("respond", ignoreCase = true) -> {\n'
        '                notificationContext?.let { initiateReply(it) }',
        '            command.contains("reply", ignoreCase = true) || command.contains("respond", ignoreCase = true) -> {\n'
        '                Timber.d("VCP: REPLY command")\n'
        '                notificationContext?.let { initiateReply(it) }'
    ),
    # 3d. Log yes handler
    (
        '    private fun handleYesCommand(command: String) {\n'
        '        when {',
        '    private fun handleYesCommand(command: String) {\n'
        '        Timber.d("VCP: YES command — pendingOpen=${pendingOpenAppContext != null} pendingReply=${pendingReplyContext != null}")\n'
        '        when {'
    ),
    # 3e. Log no handler
    (
        '    private fun handleNoCommand() {\n'
        '        when {',
        '    private fun handleNoCommand() {\n'
        '        Timber.d("VCP: NO command — pendingOpen=${pendingOpenAppContext != null} pendingReply=${pendingReplyContext != null}")\n'
        '        when {'
    ),
    # 3f. Log context changes
    (
        '    fun setCurrentContext(context: NotificationContext) {\n'
        '        currentContext = context',
        '    fun setCurrentContext(context: NotificationContext) {\n'
        '        Timber.d("VCP: setCurrentContext(${context.app}/${context.sender})")\n'
        '        currentContext = context'
    ),
    # 3g. Log shutdown
    (
        '    fun shutdown() {\n'
        '        ttsEngine?.shutdown()',
        '    fun shutdown() {\n'
        '        Timber.d("VCP: shutdown()")\n'
        '        ttsEngine?.shutdown()'
    ),
    # 3h. Log speak
    (
        '    private fun speak(text: String) {\n'
        '        ttsEngine?.speak(text)',
        '    private fun speak(text: String) {\n'
        '        Timber.d("VCP TTS: \\"${if (text.length > 60) text.take(60) + "…" else text}\\"")\n'
        '        ttsEngine?.speak(text)'
    ),
])


# ═══════════════════════════════════════════════════════════════════════════
# 4. AndroidSttEngine.kt — add logging for stop/shutdown
# ═══════════════════════════════════════════════════════════════════════════
aste = f'{BASE}/drivemode/AndroidSttEngine.kt'
patch(aste, [
    # 4a. Log stopListening
    (
        '    override fun stopListening() {\n'
        '        // Null callbacks immediately (on caller\'s thread)',
        '    override fun stopListening() {\n'
        '        Timber.d("AndroidSTT: stopListening()")\n'
        '        // Null callbacks immediately (on caller\'s thread)'
    ),
    # 4b. Log shutdown
    (
        '    override fun shutdown() {\n'
        '        mainHandler.post {',
        '    override fun shutdown() {\n'
        '        Timber.d("AndroidSTT: shutdown()")\n'
        '        mainHandler.post {'
    ),
    # 4c. Log recognizer destroyed on fatal error
    (
        '                    try { recognizer?.destroy() } catch (_: Exception) {}\n'
        '                    recognizer = null\n'
        '                }',
        '                    Timber.w("AndroidSTT: destroying recognizer after fatal error $error")\n'
        '                    try { recognizer?.destroy() } catch (_: Exception) {}\n'
        '                    recognizer = null\n'
        '                }'
    ),
])

print("\n✅ Logging improvements applied!")
