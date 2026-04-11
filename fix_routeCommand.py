#!/usr/bin/env python3
"""Fix routeCommand in VoiceAssistantService.kt:
1. Add missing `return` after each routing section
2. Remove extra `}` after notification reading
3. Add email routing section
"""
import re

FILE = 'presentation/src/main/java/com/silentpulse/messenger/feature/assistant/VoiceAssistantService.kt'

with open(FILE, 'r') as f:
    lines = f.readlines()

# Find routeCommand start and end
route_start = None
route_end = None
depth = 0
for i, line in enumerate(lines):
    if 'private fun routeCommand(command: String)' in line:
        route_start = i
        depth = 0
    if route_start is not None:
        for ch in line:
            if ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
        if depth == 0 and i > route_start:
            route_end = i
            break

print(f"routeCommand: lines {route_start+1}..{route_end+1}")

# Extract just the function body
func_lines = lines[route_start:route_end+1]
func_text = ''.join(func_lines)

# --- Fix 1: Add return after notifReaderActive intercept ---
# The intercept block doesn't return, so the rest of routeCommand still runs.
func_text = func_text.replace(
    '            else handleNotifReaderCommand(c)\n        }',
    '            else handleNotifReaderCommand(c)\n            return\n        }'
)

# --- Fix 2: Add missing returns after each section's closing } ---
# Pattern: closing `}` of an if-block followed by a comment starting the next section.
# We add `return` before each section-divider comment.

# Sections that need return (the closing } before next // ── comment):
sections_needing_return = [
    # After active session
    ('// Wait for TTS_REPLY broadcast from the app\n            }\n        }\n        // ── 2.',
     '// Wait for TTS_REPLY broadcast from the app\n            }\n            return\n        }\n        // ── 2.'),

    # After weather
    ('            }\n        }\n        // ── 3. Built-in: stop navigation',
     '            }\n            return\n        }\n        // ── 3. Built-in: stop navigation'),

    # After stop navigation
    ('}\n        }\n        // ── 4. Built-in: navigation / directions',
     '}\n            return\n        }\n        // ── 4. Built-in: navigation / directions'),

    # After navigation  
    ('}\n        }\n        // ── 4. Built-in: drive time',
     '}\n            return\n        }\n        // ── 4. Built-in: drive time'),

    # After drive time
    ('}\n        }\n        // ── 4b. Resume',
     '}\n            return\n        }\n        // ── 4b. Resume'),

    # After resume media
    ("speak(\"Resuming.\") { resumeWakeWord() }\n        }\n        // ── 4c.",
     "speak(\"Resuming.\") { resumeWakeWord() }\n            return\n        }\n        // ── 4c."),

    # After audiobook
    ('}\n        }\n        // ── 4d. Music playback',
     '}\n            return\n        }\n        // ── 4d. Music playback'),

    # After music playback
    ('}\n        }\n        // ── 4e. Read notifications',
     '}\n            return\n        }\n        // ── 4e. Read notifications'),

    # After notification reading - add return AND remove extra }
    ('startNotificationReading()\n        }\n        }',
     'startNotificationReading()\n            return\n        }'),

    # After "what apps"
    ('speak(text) { resumeWakeWord() }\n        }\n        // ── 3. Built-in: help',
     'speak(text) { resumeWakeWord() }\n            return\n        }\n        // ── 3. Built-in: help'),

    # After help
    ('resumeWakeWord()\n            }\n        }\n        // ── 4a.',
     'resumeWakeWord()\n            }\n            return\n        }\n        // ── 4a.'),

    # After "give me commands" / schema request
    ("}\n        }\n        // ── 6. Try cross-app routing",
     "}\n            return\n        }\n        // ── 6. Try cross-app routing"),

    # After cross-app routing
    ("}\n        }\n        // ── 6b. Stock price",
     "}\n            return\n        }\n        // ── 6b. Stock price"),

    # After stock price
    ("}\n        }\n        // ── 7. DuckDuckGo",
     "}\n            return\n        }\n        // ── 7. DuckDuckGo"),

    # After DuckDuckGo
    ("}\n        }\n        // ── 8. No app matched",
     "}\n            return\n        }\n        // ── 8. No app matched"),
]

for old, new in sections_needing_return:
    if old in func_text:
        func_text = func_text.replace(old, new, 1)
        # Extract a label from the new text to show which one matched
        label = new.split('// ──')[1].strip()[:40] if '// ──' in new else new[:40]
        print(f"  ✓ Added return before: {label}")
    else:
        label = old.split('// ──')[1].strip()[:40] if '// ──' in old else old[:40]
        print(f"  ✗ NOT FOUND: {label}")

# --- Fix 3: Add email routing after notification reading ---
# Insert email routing between the (now-fixed) notification reading and "what apps"
email_routing = '''
        // ── 4f. Read unread emails only ─────────────────────────────────────
        if (notifReaderHandler.isReadEmailCommand(c)) {
            startEmailReading()
            return
        }
'''
notif_end = '            return\n        }\n        // ── 4. Built-in: "what apps'
if notif_end in func_text:
    # Check we haven't already added it
    if 'isReadEmailCommand' not in func_text:
        func_text = func_text.replace(
            notif_end,
            '            return\n        }' + email_routing + '        // ── 4. Built-in: "what apps'
        )
        print("  ✓ Added email routing section")
    else:
        print("  ✓ Email routing already present")
else:
    print("  ✗ Could not find insertion point for email routing")

# Reassemble
lines[route_start:route_end+1] = [func_text]

with open(FILE, 'w') as f:
    f.writelines(lines)

# Verify brace count
with open(FILE, 'r') as f:
    all_text = f.read()
    all_lines = all_text.split('\n')

depth = 0
for i, line in enumerate(all_lines, 1):
    if 'private fun routeCommand(command: String)' in line:
        depth = 0
        start = i
    if i >= start if 'start' in dir() else False:
        for ch in line:
            if ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
        if depth == 0 and i > start:
            print(f"\nrouteCommand now closes at line {i} (was {route_end+1})")
            break

print("\nDone! Total lines:", len(all_lines))
