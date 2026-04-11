#!/usr/bin/env python3
"""Add branch logging to SilentPulseNotificationListener handleCommandResult."""

FILE = 'presentation/src/main/java/com/silentpulse/messenger/feature/drivemode/SilentPulseNotificationListener.kt'

with open(FILE, 'r') as f:
    content = f.read()

patches = [
    # Log which fuzzy matchers fired
    (
        '        when {\n'
        '            matchesDismiss -> {\n'
        '                totalVoiceAttempts = 0\n'
        '                pendingNotification?.key?.let { cancelNotification(it) }',
        '        Timber.d("Drive Mode: match results: dismiss=$matchesDismiss delete=$matchesDelete reply=$matchesReply read=$matchesRead stop=$matchesStop")\n'
        '        when {\n'
        '            matchesDismiss -> {\n'
        '                Timber.d("Drive Mode: → DISMISS")\n'
        '                totalVoiceAttempts = 0\n'
        '                pendingNotification?.key?.let { cancelNotification(it) }'
    ),
    # Log delete branch
    (
        '            matchesDelete -> {\n'
        '                totalVoiceAttempts = 0\n'
        '                val sbn = pendingNotification',
        '            matchesDelete -> {\n'
        '                Timber.d("Drive Mode: → DELETE")\n'
        '                totalVoiceAttempts = 0\n'
        '                val sbn = pendingNotification'
    ),
    # Log reply branch
    (
        '            matchesReply -> {\n'
        '                totalVoiceAttempts = 0\n'
        '                speak("What would you like to say?")',
        '            matchesReply -> {\n'
        '                Timber.d("Drive Mode: → REPLY")\n'
        '                totalVoiceAttempts = 0\n'
        '                speak("What would you like to say?")'
    ),
    # Log read/repeat branch
    (
        '            matchesRead -> {\n'
        '                // Re-read the message\n'
        '                totalVoiceAttempts = 0',
        '            matchesRead -> {\n'
        '                Timber.d("Drive Mode: → READ/REPEAT")\n'
        '                // Re-read the message\n'
        '                totalVoiceAttempts = 0'
    ),
    # Log stop branch
    (
        '            matchesStop -> {\n'
        '                // Stop: leave notification in place',
        '            matchesStop -> {\n'
        '                Timber.d("Drive Mode: → STOP")\n'
        '                // Stop: leave notification in place'
    ),
    # Log listenState transitions
    (
        '        listenState = ListenState.AWAITING_COMMAND',
        '        Timber.d("Drive Mode: listenState → AWAITING_COMMAND")\n'
        '        listenState = ListenState.AWAITING_COMMAND'
    ),
    # Log retryOrGiveUp
    (
        '    private fun retryOrGiveUp(prompt: String) {',
        '    private fun retryOrGiveUp(prompt: String) {\n'
        '        Timber.d("Drive Mode: retryOrGiveUp attempt=$totalVoiceAttempts/$MAX_VOICE_ATTEMPTS")'
    ),
]

for old, new in patches:
    if old in content:
        content = content.replace(old, new, 1)
        label = old.strip()[:60].replace('\n', ' ')
        print(f"  ✓ {label}…")
    else:
        label = old.strip()[:60].replace('\n', ' ')
        print(f"  ✗ NOT FOUND: {label}…")

with open(FILE, 'w') as f:
    f.write(content)

print("\n✅ Done!")
