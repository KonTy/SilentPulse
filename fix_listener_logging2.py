#!/usr/bin/env python3
"""Add more listenState transition logs to SilentPulseNotificationListener."""

FILE = 'presentation/src/main/java/com/silentpulse/messenger/feature/drivemode/SilentPulseNotificationListener.kt'

with open(FILE, 'r') as f:
    content = f.read()

patches = [
    # Log READING state transition
    (
        '        listenState = ListenState.READING  // block duplicate notifications during TTS',
        '        Timber.d("Drive Mode: listenState → READING")\n'
        '        listenState = ListenState.READING  // block duplicate notifications during TTS'
    ),
    # Log AWAITING_REPLY_TEXT transition  
    (
        '        listenState = ListenState.AWAITING_REPLY_TEXT',
        '        Timber.d("Drive Mode: listenState → AWAITING_REPLY_TEXT")\n'
        '        listenState = ListenState.AWAITING_REPLY_TEXT'
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

print("Done!")
