#!/usr/bin/env python3
"""Fix remaining missing returns and add email routing in routeCommand."""

FILE = 'presentation/src/main/java/com/silentpulse/messenger/feature/assistant/VoiceAssistantService.kt'

with open(FILE, 'r') as f:
    content = f.read()

# Fix: Add returns after sections that still need them
# We search for the unique closing pattern of each section

fixes = [
    # After "what apps" section
    (
        'speak(text) { resumeWakeWord() }\n        }\n        // ── 3. Built-in: help',
        'speak(text) { resumeWakeWord() }\n            return\n        }\n        // ── 3. Built-in: help'
    ),
    # After help section
    (
        'resumeWakeWord()\n            }\n        }\n        // ── 4a. Built-in:',
        'resumeWakeWord()\n            }\n            return\n        }\n        // ── 4a. Built-in:'
    ),
    # After "give me commands" section
    (
        '}\n        }\n        // ── 6. Try cross-app routing',
        '}\n            return\n        }\n        // ── 6. Try cross-app routing'
    ),
    # After cross-app routing section
    (
        '}\n        }\n        // ── 6b. Stock price',
        '}\n            return\n        }\n        // ── 6b. Stock price'
    ),
    # After stock price section
    (
        '}\n        }\n        // ── 7. DuckDuckGo',
        '}\n            return\n        }\n        // ── 7. DuckDuckGo'
    ),
    # After DuckDuckGo section
    (
        '}\n        }\n        // ── 8. No app matched',
        '}\n            return\n        }\n        // ── 8. No app matched'
    ),
]

for old, new in fixes:
    if old in content:
        content = content.replace(old, new, 1)
        label = [x for x in new.split('\n') if '// ──' in x][0].strip()[:50]
        print(f"  ✓ {label}")
    else:
        label = [x for x in old.split('\n') if '// ──' in x][0].strip()[:50]
        print(f"  ✗ NOT FOUND: {label}")

# Add email routing after notification reading section
email_block = """        // ── 4f. Read unread emails only ─────────────────────────────────────
        if (notifReaderHandler.isReadEmailCommand(c)) {
            startEmailReading()
            return
        }
"""
marker = '            return\n        }\n        // ── 4. Built-in: "what apps'
if marker in content:
    if 'isReadEmailCommand' not in content:
        content = content.replace(
            marker,
            '            return\n        }\n' + email_block + '        // ── 4. Built-in: "what apps'
        )
        print("  ✓ Added email routing")
    else:
        print("  ✓ Email routing already present")
else:
    print("  ✗ Could not find email insertion point")

with open(FILE, 'w') as f:
    f.write(content)

# Verify
with open(FILE, 'r') as f:
    lines = f.readlines()

depth = 0
start = None
for i, line in enumerate(lines, 1):
    if 'private fun routeCommand(command: String)' in line:
        start = i
        depth = 0
    if start is not None:
        for ch in line:
            if ch == '{': depth += 1
            elif ch == '}': depth -= 1
        if depth == 0 and i > start:
            print(f"\nrouteCommand closes at line {i}")
            break

print(f"Total lines: {len(lines)}")
