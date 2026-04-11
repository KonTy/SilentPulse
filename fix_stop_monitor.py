#!/usr/bin/env python3
"""
Add stop-during-reading monitor to SilentPulseNotificationListener.

When TTS reads a notification aloud, the user can say "stop" to interrupt.
A separate SpeechRecognizer runs during TTS reading and listens for "stop".
Also skips the verbose command prompt on the first listen attempt.
"""

FILE = 'presentation/src/main/java/com/silentpulse/messenger/feature/drivemode/SilentPulseNotificationListener.kt'

content = open(FILE).read()
changes = 0

# ─── 1. Add imports for SpeechRecognizer ────────────────────────────────────

old_import = 'import android.os.Looper'
new_import = '''import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer'''

if 'import android.speech.SpeechRecognizer' not in content:
    content = content.replace(old_import, new_import, 1)
    changes += 1
    print('1. Added SpeechRecognizer imports')
else:
    print('1. SpeechRecognizer imports already present')

# ─── 2. Add stop monitor state variables ────────────────────────────────────

marker = 'private var sttSessionId = 0'
if 'stopMonitorActive' not in content:
    new_vars = marker + '''

    // ── Stop monitor — lets user say "stop" during TTS notification reading ──
    private var stopMonitorRecognizer: SpeechRecognizer? = null
    @Volatile private var stopMonitorActive = false'''
    content = content.replace(marker, new_vars, 1)
    changes += 1
    print('2. Added stop monitor state variables')
else:
    print('2. Stop monitor state variables already present')

# ─── 3. Add stop monitor functions before startSttListening ─────────────────

marker3 = '    private fun startSttListening(onResult: (String) -> Unit) {'
if 'private fun startStopMonitor()' not in content:
    stop_monitor_code = '''    // ── Stop monitor — voice interrupt during TTS notification reading ────────

    /**
     * Starts a lightweight SpeechRecognizer that runs while TTS reads
     * the notification body.  Only acts on the word "stop".
     * Uses a SEPARATE recognizer so the cached STT engine is untouched.
     */
    private fun startStopMonitor() {
        if (!hasRecordPermission()) return
        if (stopMonitorActive) return
        if (listenState != ListenState.READING) return

        mainHandler.post {
            try {
                val sr = SpeechRecognizer.createSpeechRecognizer(applicationContext)
                stopMonitorRecognizer = sr
                stopMonitorActive = true

                val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra("android.speech.extra.PREFER_OFFLINE", true)
                    // Keep session alive as long as possible (one beep per read)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 120_000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 60_000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 30_000L)
                }

                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) {
                        Timber.d("Drive Mode stop monitor: MIC HOT — say stop to interrupt")
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onPartialResults(partialResults: android.os.Bundle?) {
                        if (!stopMonitorActive) return
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.lowercase()?.trim() ?: return
                        Timber.d("Drive Mode stop monitor partial: \"$partial\"")
                        if (partial.contains("stop") || partial.contains("computer")) {
                            Timber.d("Drive Mode: STOP detected during reading (partial)")
                            handleStopDuringReading()
                        }
                    }

                    override fun onResults(results: android.os.Bundle?) {
                        if (!stopMonitorActive) return
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.lowercase()?.trim() ?: ""
                        Timber.d("Drive Mode stop monitor result: \"$text\"")
                        if (text.contains("stop") || text.contains("computer")) {
                            Timber.d("Drive Mode: STOP detected during reading")
                            handleStopDuringReading()
                        }
                        // Don't restart on non-stop results — avoids multiple beeps
                        // The monitor has done its job; TTS onDone will take over
                        else {
                            stopStopMonitor()
                        }
                    }

                    override fun onError(error: Int) {
                        Timber.d("Drive Mode stop monitor error: $error")
                        // Don't restart — avoids multiple beeps
                        stopStopMonitor()
                    }

                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })

                Timber.d("Drive Mode: starting stop monitor (user can say stop to interrupt)")
                sr.startListening(intent)
            } catch (e: Exception) {
                Timber.e(e, "Drive Mode: failed to start stop monitor")
                stopMonitorActive = false
            }
        }
    }

    /** Handles "stop" detected by the stop monitor during TTS reading. */
    private fun handleStopDuringReading() {
        mainHandler.post {
            stopStopMonitor()
            ttsEngine?.stop()  // Immediately halt TTS
            Timber.d("Drive Mode: TTS interrupted by stop command")
            pendingNotification = null
            listenState = ListenState.IDLE
            totalVoiceAttempts = 0
            speak("Stopped. The notification is still there for you to read later.")
        }
    }

    /** Tears down the stop monitor recognizer cleanly. */
    private fun stopStopMonitor() {
        stopMonitorActive = false
        val sr = stopMonitorRecognizer
        stopMonitorRecognizer = null
        if (sr != null) {
            mainHandler.post {
                try {
                    sr.cancel()
                    sr.destroy()
                } catch (_: Exception) {}
                Timber.d("Drive Mode: stop monitor destroyed")
            }
        }
    }

    ''' + marker3
    content = content.replace(marker3, stop_monitor_code, 1)
    changes += 1
    print('3. Added stop monitor functions')
else:
    print('3. Stop monitor functions already present')

# ─── 4. Add stopStopMonitor() to onDestroy ──────────────────────────────────

old_destroy = '''        sInstance = null
        stopListeningNow()'''
new_destroy = '''        sInstance = null
        stopStopMonitor()
        stopListeningNow()'''

if 'stopStopMonitor()' not in content.split('onDestroy')[1].split('}')[0] if 'onDestroy' in content else True:
    content = content.replace(old_destroy, new_destroy, 1)
    changes += 1
    print('4. Added stopStopMonitor() to onDestroy')
else:
    print('4. stopStopMonitor() already in onDestroy')

# ─── 5. Modify the notification reading flow ────────────────────────────────
# Old flow:
#   speak(readAloud) {
#       mainHandler.post {
#           ensureSttEngine()
#           speak("Say dismiss, delete, reply, repeat, or stop.") {
#               mainHandler.postDelayed({ startCommandListening() }, STT_START_DELAY_MS)
#           }
#       }
#   }
#
# New flow:
#   // Start stop monitor 1s after TTS begins to let it get going
#   mainHandler.postDelayed({ startStopMonitor() }, 1000L)
#   speak(readAloud) {
#       mainHandler.post {
#           stopStopMonitor()  // TTS done, stop monitoring
#           ensureSttEngine()
#           // First attempt: skip verbose prompt, just beep and listen
#           mainHandler.postDelayed({ startCommandListening() }, STT_START_DELAY_MS)
#       }
#   }

# The exact text to find (with the corrupted wrapping):
old_flow = '''speak(readAloud) {
                mainHandler.post {
                    // Pre-load STT engine while TTS speaks (model loads in ~3s 
background)                                                                                 ensureSttEngine()
            speak("Say dismiss, delete, reply, repeat, or stop.") {
                        // Delay before starting microphone to let speaker audio
 settle                                                                                                 mainHandler.postDelayed({ startCommandListening() }, STT
_START_DELAY_MS)                                                                                    }
                }
            }'''

new_flow = '''// Start stop monitor so user can say "stop" while TTS reads
            mainHandler.postDelayed({ startStopMonitor() }, 1000L)
            speak(readAloud) {
                mainHandler.post {
                    stopStopMonitor()  // TTS done, switch to command listening
                    ensureSttEngine()
                    // Skip verbose prompt on first try — just beep and listen.
                    // If user says nothing, retryOrGiveUp will play the prompt.
                    mainHandler.postDelayed({ startCommandListening() }, STT_START_DELAY_MS)
                }
            }'''

if old_flow in content:
    content = content.replace(old_flow, new_flow, 1)
    changes += 1
    print('5. Modified notification reading flow (stop monitor + skip prompt)')
else:
    print('5. WARNING: Could not find old reading flow text!')
    # Try to find partial matches for debugging
    import re
    if 'Say dismiss, delete, reply, repeat, or stop' in content:
        print('   Found prompt text in file — format mismatch')
    else:
        print('   Prompt text not found in file')

# ─── 6. Add stopStopMonitor() to stopListeningNow() for safety ──────────────

old_stopnow = '''    private fun stopListeningNow() {
        // Stop recording but keep the engine alive (model stays in memory)
        cachedSttEngine?.stopListening()
        stopMicService()
    }'''
new_stopnow = '''    private fun stopListeningNow() {
        // Stop recording but keep the engine alive (model stays in memory)
        cachedSttEngine?.stopListening()
        stopStopMonitor()
        stopMicService()
    }'''

if old_stopnow in content:
    content = content.replace(old_stopnow, new_stopnow, 1)
    changes += 1
    print('6. Added stopStopMonitor() to stopListeningNow()')
else:
    # Check if already modified
    if 'stopStopMonitor()' in content.split('stopListeningNow')[1].split('}')[0] if 'stopListeningNow' in content else False:
        print('6. stopStopMonitor() already in stopListeningNow()')
    else:
        print('6. WARNING: Could not find stopListeningNow exact text')

open(FILE, 'w').write(content)
print(f'\nDone — {changes} changes applied')
