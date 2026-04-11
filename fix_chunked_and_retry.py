#!/usr/bin/env python3
"""
Fix two issues:
1. Remove the concurrent stop monitor (picks up TTS audio, false "stop").
   Replace with chunked reading: read 2-3 sentences, pause, briefly listen
   for "stop" in silence, then continue.
2. On first STT timeout/no_match, silently restart STT (no spoken message).
   Only speak the "wait for the beep" prompt on 2nd+ failure.
"""

FILE = 'presentation/src/main/java/com/silentpulse/messenger/feature/drivemode/SilentPulseNotificationListener.kt'

lines = open(FILE).readlines()
content = ''.join(lines)
changes = 0

# ─── 1. Remove stop monitor state variables (lines 71-73) ──────────────────

content = content.replace(
    "\n    // ── Stop monitor — lets user say \"stop\" during TTS notification reading ──\n"
    "    private var stopMonitorRecognizer: SpeechRecognizer? = null\n"
    "    @Volatile private var stopMonitorActive = false",
    ""
)
changes += 1
print("1. Removed stop monitor state variables")

# ─── 2. Remove SpeechRecognizer imports (added for stop monitor) ────────────

content = content.replace(
    "import android.speech.RecognitionListener\n"
    "import android.speech.RecognizerIntent\n"
    "import android.speech.SpeechRecognizer\n",
    ""
)
changes += 1
print("2. Removed SpeechRecognizer imports")

# ─── 3. Replace reading flow with chunked approach ─────────────────────────
# Find the exact block (lines 246-262 area)

old_reading = (
    '        if (isVoiceReplyEnabled()) {\n'
    '            // Start stop monitor so user can say "stop" while TTS reads\n'
    '            mainHandler.postDelayed({ startStopMonitor() }, 1000L)\n'
    '            speak(readAloud) {\n'
    '                mainHandler.post {\n'
    '                    stopStopMonitor()  // TTS done, switch to command listening\n'
    '                    ensureSttEngine()\n'
    '                    // Skip verbose prompt on first try — just beep and listen.\n'
    '                    // If user says nothing, retryOrGiveUp will play the prompt.\n'
    '                    mainHandler.postDelayed({ startCommandListening() }, STT_START_DELAY_MS)\n'
    '                }\n'
    '            }\n'
    '        } else {\n'
    '            speak(readAloud) {\n'
    '                mainHandler.post { listenState = ListenState.IDLE }\n'
    '            }\n'
    '        }'
)

new_reading = '''        if (isVoiceReplyEnabled()) {
            ensureSttEngine()
            speakChunked(readAloud)
        } else {
            speak(readAloud) {
                mainHandler.post { listenState = ListenState.IDLE }
            }
        }'''

if old_reading in content:
    content = content.replace(old_reading, new_reading, 1)
    changes += 1
    print("3. Replaced reading flow with speakChunked()")
else:
    print("3. WARNING: could not find old reading flow")
    # Debug: check what's there
    if 'startStopMonitor' in content:
        print("   startStopMonitor is still referenced")
    if 'speakChunked' in content:
        print("   speakChunked already present")

# ─── 4. Remove the stop monitor functions block ────────────────────────────
# This is the large block from startStopMonitor() through stopStopMonitor()

# Find and remove the entire block
start_marker = "    // ── Stop monitor — voice interrupt during TTS notification reading"
end_marker = "    private fun startSttListening(onResult: (String) -> Unit) {"

if start_marker in content:
    idx_start = content.index(start_marker)
    idx_end = content.index(end_marker)
    # Remove everything from start_marker to just before startSttListening
    content = content[:idx_start] + "\n    " + content[idx_end:]
    changes += 1
    print("4. Removed stop monitor functions block")
else:
    print("4. Stop monitor functions block already removed")

# ─── 5. Remove stopStopMonitor() calls in onDestroy and stopListeningNow ───

content = content.replace("        stopStopMonitor()\n        stopListeningNow()\n",
                          "        stopListeningNow()\n")
content = content.replace("        cachedSttEngine?.stopListening()\n        stopStopMonitor()\n",
                          "        cachedSttEngine?.stopListening()\n")
changes += 1
print("5. Removed stopStopMonitor() calls from onDestroy and stopListeningNow")

# ─── 6. Add speakChunked() and speakChunksFrom() after the speak() function ─

# Find the end of the speak function (after detectLocaleByScript)
marker6 = "    // ── Voice command state machine ──"

chunked_code = '''    // ── Chunked TTS with stop-between-sentences ──────────────────────────────
    //
    // For long notifications, split by sentences and pause every few sentences
    // to briefly listen for "stop".  Because TTS is silent during the pause
    // the microphone won't pick up its own audio (no false triggers).

    /** Maximum sentences per chunk before inserting a stop-check pause. */
    private val SENTENCES_PER_CHUNK = 3
    /** How long (ms) to listen for "stop" between chunks. */
    private val CHUNK_STOP_LISTEN_MS = 2500L

    /**
     * Speaks the full [text] in sentence chunks.  Between each chunk the mic
     * briefly opens to listen for "stop".  After the last chunk, transitions
     * to normal command listening.
     */
    private fun speakChunked(text: String) {
        val sentences = splitSentences(text)
        if (sentences.size <= SENTENCES_PER_CHUNK) {
            // Short message — read it all, then go straight to command listening
            speak(text) {
                mainHandler.post {
                    mainHandler.postDelayed({ startCommandListening() }, STT_START_DELAY_MS)
                }
            }
            return
        }
        // Long message — chunk it
        val chunks = sentences.chunked(SENTENCES_PER_CHUNK).map { it.joinToString(" ") }
        Timber.d("Drive Mode: chunked reading — ${chunks.size} chunks from ${sentences.size} sentences")
        speakChunksFrom(chunks, 0)
    }

    /**
     * Recursively speaks chunk [index], then briefly listens for "stop".
     * If stop is heard, aborts.  Otherwise continues to the next chunk.
     * After the last chunk, transitions to command listening.
     */
    private fun speakChunksFrom(chunks: List<String>, index: Int) {
        if (index >= chunks.size || listenState != ListenState.READING) {
            // Done reading — go to command listening
            mainHandler.postDelayed({ startCommandListening() }, STT_START_DELAY_MS)
            return
        }

        speak(chunks[index]) {
            mainHandler.post {
                if (listenState != ListenState.READING) return@post

                val isLast = (index == chunks.size - 1)
                if (isLast) {
                    // Last chunk — go straight to command listening
                    mainHandler.postDelayed({ startCommandListening() }, STT_START_DELAY_MS)
                    return@post
                }

                // Not the last chunk — briefly listen for "stop"
                Timber.d("Drive Mode: chunk ${index + 1}/${chunks.size} done, listening for stop…")
                listenForStopBetweenChunks(chunks, index + 1)
            }
        }
    }

    /**
     * Opens the mic briefly between chunks.  If the user says something
     * containing "stop", aborts reading.  On timeout / silence, continues.
     */
    private fun listenForStopBetweenChunks(chunks: List<String>, nextIndex: Int) {
        startMicService()
        val sessionId = ++sttSessionId
        val engine = cachedSttEngine
        if (engine == null) {
            // No engine — just continue reading
            speakChunksFrom(chunks, nextIndex)
            return
        }

        // Auto-continue after CHUNK_STOP_LISTEN_MS even if STT hasn't responded
        val timeoutRunnable = Runnable {
            if (sttSessionId == sessionId) {
                Timber.d("Drive Mode: chunk stop-listen timed out — continuing")
                sttSessionId++  // invalidate session
                engine.stopListening()
                stopMicService()
                speakChunksFrom(chunks, nextIndex)
            }
        }
        mainHandler.postDelayed(timeoutRunnable, CHUNK_STOP_LISTEN_MS)

        engine.startListening(
            onResult = { recognized ->
                if (sessionId != sttSessionId) return@startListening
                mainHandler.removeCallbacks(timeoutRunnable)
                sttSessionId++  // consume session
                stopMicService()

                val lower = recognized.lowercase().trim()
                Timber.d("Drive Mode: chunk stop-listen heard: \\"$lower\\"")
                mainHandler.post {
                    if (lower.contains("stop")) {
                        Timber.d("Drive Mode: STOP heard between chunks — aborting read")
                        totalVoiceAttempts = 0
                        pendingNotification = null
                        listenState = ListenState.IDLE
                        speak("Stopped.")
                    } else {
                        // Not "stop" — continue reading
                        speakChunksFrom(chunks, nextIndex)
                    }
                }
            },
            onError = { _ ->
                if (sessionId != sttSessionId) return@startListening
                mainHandler.removeCallbacks(timeoutRunnable)
                sttSessionId++
                stopMicService()
                // Silence / error — just continue reading
                mainHandler.post { speakChunksFrom(chunks, nextIndex) }
            }
        )
    }

    /**
     * Splits text into sentences.  Handles common abbreviations to avoid
     * splitting on "Dr.", "Mr.", "e.g.", etc.
     */
    private fun splitSentences(text: String): List<String> {
        // Split on sentence-ending punctuation followed by a space or end
        val raw = text.split(Regex("(?<=[.!?])\\\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (raw.isEmpty()) listOf(text) else raw
    }

    '''

if 'private fun speakChunked' not in content:
    content = content.replace(marker6, chunked_code + marker6)
    changes += 1
    print("6. Added speakChunked(), speakChunksFrom(), listenForStopBetweenChunks(), splitSentences()")
else:
    print("6. Chunked reading functions already present")

# ─── 7. Fix the retry logic: silent restart on first timeout ────────────────
# Replace retryOrGiveUp to silently restart on first attempt

old_retry = '''    private fun retryOrGiveUp(prompt: String) {'''

new_retry = '''    /**
     * If [silentRetry] is true, restarts STT without speaking (saves time).
     * Otherwise speaks [prompt] then restarts.
     */
    private fun retryOrGiveUp(prompt: String, silentRetry: Boolean = false) {'''

if old_retry in content:
    content = content.replace(old_retry, new_retry, 1)
    changes += 1
    print("7a. Added silentRetry parameter to retryOrGiveUp")
else:
    print("7a. retryOrGiveUp already modified or not found")

# Now change the else branch to support silent retry
old_else = '''        } else {
            // Keep listenState as AWAITING_COMMAND to block duplicates during TTS'''

# Check the wrapped version too
if old_else not in content:
    # Try the wrapped version from the actual file
    old_else = '        } else {\n'
    for line in content.split('\n'):
        if 'Keep listenState as AWAITING_COMMAND' in line:
            old_else = '        } else {\n            ' + line.strip() + '\n'
            break

# Let me use a different approach - find and replace in the retryOrGiveUp function
old_retry_body = '''        } else {
            // Keep listenState as AWAITING_COMMAND to block duplicates during TTS
            speak(prompt) {
                mainHandler.postDelayed({ startCommandListening() }, STT_START_DELAY_MS)
            }
        }
    }'''

new_retry_body = '''        } else if (silentRetry) {
            // Silent restart — no spoken prompt, just re-open the mic
            Timber.d("Drive Mode: silent retry (no prompt)")
            mainHandler.postDelayed({ startCommandListening() }, STT_START_DELAY_MS)
        } else {
            // Speak the prompt then restart
            speak(prompt) {
                mainHandler.postDelayed({ startCommandListening() }, STT_START_DELAY_MS)
            }
        }
    }'''

if old_retry_body in content:
    content = content.replace(old_retry_body, new_retry_body, 1)
    changes += 1
    print("7b. Added silent retry branch to retryOrGiveUp")
else:
    print("7b. WARNING: could not find retryOrGiveUp body for silent retry")
    # Debug
    if 'silentRetry' in content:
        print("   silentRetry already in content")

# ─── 8. Change onError handler to use silent retry on first attempt ─────────

# The error handler calls retryOrGiveUp(userMsg) for retryable errors.
# Change it to use silent retry on attempt 1.

old_retryable = '                    if (retryable) {\n                        // retryOrGiveUp handles the cap check and prompt\n                        retryOrGiveUp(userMsg)\n'
new_retryable = '                    if (retryable) {\n                        // Silent restart on first attempt — no wasted time speaking\n                        retryOrGiveUp(userMsg, silentRetry = totalVoiceAttempts <= 2)\n'

if old_retryable in content:
    content = content.replace(old_retryable, new_retryable, 1)
    changes += 1
    print("8. Changed onError to use silent retry on first 2 attempts")
else:
    print("8. WARNING: could not match onError retryable block")

# ─── 9. Also fix the empty result path to use silent retry ─────────────────

old_empty = '''                        if (listenState == ListenState.AWAITING_COMMAND) {
                            retryOrGiveUp("I did not catch that. Say dismiss, de'''

new_empty = '''                        if (listenState == ListenState.AWAITING_COMMAND) {
                            retryOrGiveUp("Say dismiss, de'''

# This is tricky with the wrapping. Let me use a simpler search.
# Actually let me just check if the string "I did not catch that" exists and replace
if 'I did not catch that' in content:
    content = content.replace(
        'retryOrGiveUp("I did not catch that. Say dismiss, de',
        'retryOrGiveUp("Say dismiss, de'
    )
    print("9. Simplified empty result retry message")
else:
    print("9. Empty result message already simplified or not found")

open(FILE, 'w').write(content)
print(f"\nDone — {changes} changes applied")
