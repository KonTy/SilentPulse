#!/usr/bin/env python3
"""
Fix two issues:
1. speakChunked incorrectly splits "Message from X via SMS. The text..."
   into sentences, making the header a standalone chunk that reads without
   the body.  Fix: don't chunk at all, just read the full text, then listen.
   The chunked-with-stop-between approach adds beeps mid-reading which is
   confusing.  Instead: just read everything, then prompt and listen.
   
2. Silent retry wastes attempts — Samsung STT returns no_match when user
   IS speaking.  Remove silent retry; always speak the prompt so the user
   knows exactly when to speak (after the prompt finishes + beep).
"""

FILE = 'presentation/src/main/java/com/silentpulse/messenger/feature/drivemode/SilentPulseNotificationListener.kt'

content = open(FILE).read()
changes = 0

# ─── 1. Replace speakChunked call with simple speak + prompt ────────────────

old_flow = '''        if (isVoiceReplyEnabled()) {
            ensureSttEngine()
            speakChunked(readAloud)
        } else {'''

new_flow = '''        if (isVoiceReplyEnabled()) {
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
        } else {'''

if old_flow in content:
    content = content.replace(old_flow, new_flow, 1)
    changes += 1
    print("1. Restored simple speak + prompt flow (no chunking)")
else:
    print("1. WARNING: could not find old flow")

# ─── 2. Remove all chunked reading functions and constants ──────────────────

# Remove the constants
for const in ['SENTENCES_PER_CHUNK', 'CHUNK_STOP_LISTEN_MS']:
    lines = content.split('\n')
    new_lines = []
    for line in lines:
        if const in line and ('private val' in line or 'private const' in line):
            continue
        new_lines.append(line)
    content = '\n'.join(new_lines)

# Remove the chunked reading section (from comment to splitSentences end)
start_marker = '    // ── Chunked TTS with stop-between-sentences'
end_marker = '        // ── Voice command state machine'

if start_marker in content:
    idx_start = content.index(start_marker)
    idx_end = content.index(end_marker)
    # Keep everything before the marker and from the end marker onward
    content = content[:idx_start] + '    ' + content[idx_end:]
    changes += 1
    print("2. Removed chunked reading functions")
else:
    print("2. Chunked reading functions already removed")

# ─── 3. Remove silentRetry from retryOrGiveUp — always speak the prompt ─────

# Fix the function signature
content = content.replace(
    'private fun retryOrGiveUp(prompt: String, silentRetry: Boolean = false) {',
    'private fun retryOrGiveUp(prompt: String) {'
)

# Remove the silentRetry branch
old_retry = '''        } else if (silentRetry) {
            // Silent restart — no spoken prompt, just re-open the mic
            Timber.d("Drive Mode: silent retry (no prompt)")
            mainHandler.postDelayed({ startCommandListening() }, STT_START_DELAY'''

# The line might be wrapped, so do a more targeted search
if 'silentRetry' in content:
    lines = content.split('\n')
    new_lines = []
    skip = False
    for i, line in enumerate(lines):
        if 'else if (silentRetry)' in line:
            skip = True
            continue
        if skip:
            if 'Silent restart' in line or 'silent retry' in line or 'Timber.d("Drive Mode: silent retry' in line:
                continue
            if 'mainHandler.postDelayed({ startCommandListening() }' in line and skip:
                skip = False
                continue
            if line.strip() == '' and skip:
                continue
            skip = False
        new_lines.append(line)
    content = '\n'.join(new_lines)
    changes += 1
    print("3. Removed silentRetry logic")
else:
    print("3. silentRetry already removed")

# Remove the silentRetry call site in onError
content = content.replace(
    'retryOrGiveUp(userMsg, silentRetry = totalVoiceAttempts <= 2)',
    'retryOrGiveUp(userMsg)'
)
# Also remove the doc comment about silentRetry if present
content = content.replace(
    '    /**\n     * If [silentRetry] is true, restarts STT without speaking (saves time).\n     * Otherwise speaks [prompt] then restarts.\n     */\n',
    ''
)
print("3b. Cleaned up silentRetry references")

# ─── 4. Restore meaningful error messages ───────────────────────────────────
# Change the error messages back to be helpful 

# First attempt message
old_msg1 = 'if (totalVoiceAttempts <= 1)'
# Check if this pattern exists followed by the beep message
if old_msg1 in content and 'Wait for the beep' in content:
    # Replace the whole when branch for speech_timeout/no_match
    # Find and replace the complex if/else
    content = content.replace(
        '''                        "speech_timeout", "no_match" ->
                            if (totalVoiceAttempts <= 1)
                                "I did not hear anything. Wait for the beep, the''',
        '''                        "speech_timeout", "no_match" ->
                            "I did not hear anything. Say dismiss, delete, reply, repeat, or stop. Speak after the''',
    )
    # Now remove the else branch
    content = content.replace(
        '''                            else
                                "I still did not hear anything. Wait for the bee''',
        ''
    )
    changes += 1
    print("4. WARNING: Complex message replacement — check manually")
else:
    print("4. Error messages don't match expected pattern, skipping")

open(FILE, 'w').write(content)
print(f"\nDone — {changes} changes applied")
print("\nNOTE: Step 4 needs manual verification — the error message wrapping may be off")
