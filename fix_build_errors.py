#!/usr/bin/env python3
"""Fix remaining build errors in VoiceAssistantService.kt."""

FILE = 'presentation/src/main/java/com/silentpulse/messenger/feature/assistant/VoiceAssistantService.kt'

with open(FILE, 'r') as f:
    content = f.read()

# ── Fix 1: startSttOneShot — add commandPrefix param and fix null-safety ──
old_sig = '    private fun startSttOneShot() {\n        if (isListening) return\n        val engine = sttEngine ?: run {\n            Log.w(TAG, "startSttOneShot() ABORTED — no STT engine")\n            speak("Speech recognition unavailable.") { resumeWakeWord() }\n        }\n        isListening = true\n        Log.d(TAG, "STT one-shot — listening for command")'

new_sig = '''    private fun startSttOneShot(commandPrefix: String? = null) {
        if (isListening) return
        val engine = sttEngine ?: run {
            Log.w(TAG, "startSttOneShot() ABORTED — no STT engine")
            speak("Speech recognition unavailable.") { resumeWakeWord() }
            return
        }
        isListening = true
        if (commandPrefix != null) {
            Log.d(TAG, "STT one-shot — listening for command (prefix=\\"$commandPrefix\\")")
        } else {
            Log.d(TAG, "STT one-shot — listening for command")
        }'''

if old_sig in content:
    content = content.replace(old_sig, new_sig, 1)
    print("✓ Fix 1: startSttOneShot signature + null safety")
else:
    print("✗ Fix 1: NOT FOUND")

# ── Fix 2: Restore commandPrefix handling in startSttOneShot result ──
old_route = '                if (command.isNotEmpty()) {\n                    routeCommand(command)\n                } else {'
new_route = '''                if (command.isNotEmpty()) {
                    val fullCommand = if (commandPrefix != null) {
                        "$commandPrefix $command"
                    } else {
                        command
                    }
                    routeCommand(fullCommand)
                } else {'''

if old_route in content:
    content = content.replace(old_route, new_route, 1)
    print("✓ Fix 2: commandPrefix routing restored")
else:
    print("✗ Fix 2: NOT FOUND")

# ── Fix 3: Add "just an app name" section before "No app matched" ──
old_no_match = '        // ── 8. No app matched — tell the user'
new_app_name = '''        // ── 8. Just an app name with no command — re-listen ─────────────────
        val appMatch = commandRouter.findAppByName(c)
        if (appMatch != null) {
            Log.d(TAG, "App name only (\\"${appMatch.label}\\") — re-listening for command")
            speak("What would you like ${appMatch.label} to do?") {
                startSttOneShot(commandPrefix = appMatch.labelLower)
            }
            return
        }

        // ── 9. No app matched — tell the user'''

if old_no_match in content:
    content = content.replace(old_no_match, new_app_name, 1)
    print("✓ Fix 3: App-name-only section restored")
else:
    print("✗ Fix 3: NOT FOUND")

with open(FILE, 'w') as f:
    f.write(content)

print(f"Done! Total lines: {content.count(chr(10)) + 1}")
