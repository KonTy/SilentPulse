# SilentPulse Offline Voice Assistant — Integration Spec

> **Audience:** Any developer building an app that integrates with the SilentPulse
> voice assistant ecosystem. Feed this entire document to your LLM coding engine
> when implementing voice command support.

---

## 1. Overview

SilentPulse is a **100% offline, privacy-first, zero-LLM voice assistant ecosystem**.

- **SilentPulse** = the "Ears and Mouth" (microphone, STT, TTS, command routing)
- **Your app** = the "Brain" (domain logic, fuzzy matching, database, responses)

SilentPulse records audio, runs on-device STT, extracts your app's name from the
spoken command, strips boilerplate, and broadcasts the **raw command string** to
your app. Your app parses it, executes the action, and broadcasts a spoken response
back. SilentPulse reads it aloud.

**SilentPulse knows NOTHING about your app's commands.** It never will. Your app
owns all parsing, fuzzy matching, disambiguation, and domain logic. SilentPulse
is a dumb pipe with a microphone.

---

## 2. How Users Speak Commands

All of these are equivalent and route to the same app with the same raw command:

| User says | Target app | Raw command your app receives |
|---|---|---|
| "Computer, tell Microcore to log my weight at 220" | Microcore | `log my weight at 220` |
| "Computer, Microcore, log my weight at 220 pounds" | Microcore | `log my weight at 220 pounds` |
| "Computer, log my weight at 220 in Microcore" | Microcore | `log my weight at 220` |
| "Computer, ask Microcore how many calories I have left" | Microcore | `how many calories i have left` |
| "Computer, Microcore, start a meditation session" | Microcore | `start a meditation session` |
| "Computer, tell Microcore to log bicep size 15 inches" | Microcore | `log bicep size 15 inches` |

SilentPulse handles:
- Wake word detection ("Computer")
- App name extraction (fuzzy — "micro core" and "microcore" both match)
- Stripping boilerplate words ("tell", "ask", "to", "in", etc.)
- Multi-turn session management (follow-up questions)
- TTS playback of your app's responses

Your app handles:
- **Everything else** — parsing, number extraction, fuzzy matching, DB operations, response text

---

## 3. Communication Protocol (Android Broadcasts)

All communication uses standard Android broadcast intents. No deep links, no
Activities (unless your app explicitly wants to open UI). Commands execute
silently in the background — perfect for driving.

### 3.1 Action Constants

| Constant | Value | Direction |
|---|---|---|
| `ACTION_ASSISTANT_CAPABLE` | `com.silentpulse.action.ASSISTANT_CAPABLE` | Discovery (your manifest) |
| `ACTION_EXECUTE_COMMAND` | `com.silentpulse.action.EXECUTE_COMMAND` | SilentPulse → Your app |
| `ACTION_TTS_REPLY` | `com.silentpulse.action.TTS_REPLY` | Your app → SilentPulse |
| `ACTION_REQUEST_SCHEMA` | `com.silentpulse.action.REQUEST_SCHEMA` | SilentPulse → Your app |
| `ACTION_REPORT_SCHEMA` | `com.silentpulse.action.REPORT_SCHEMA` | Your app → SilentPulse |

### 3.2 Extra Constants

| Constant | Type | Description |
|---|---|---|
| `EXTRA_TRANSCRIPT` | String | The raw command text (boilerplate stripped) |
| `EXTRA_SESSION_ID` | String | UUID identifying this conversation turn |
| `EXTRA_SPOKEN_TEXT` | String | Text for SilentPulse to speak via TTS |
| `EXTRA_REQUIRE_FOLLOWUP` | Boolean | If true, SilentPulse re-listens and routes the next utterance back to your app with the same SESSION_ID |
| `EXTRA_SCHEMA_JSON` | String | JSON array of your app's command descriptions |

---

## 4. Step-by-Step: Making Your App Assistant-Capable

### Step 1: Declare in AndroidManifest.xml

```xml
<!-- Allow SilentPulse to discover your app -->
<receiver
    android:name=".voice.VoiceCommandReceiver"
    android:exported="true">
    <intent-filter>
        <!-- Discovery: SilentPulse queries PackageManager for this -->
        <action android:name="com.silentpulse.action.ASSISTANT_CAPABLE" />
        <!-- Command execution -->
        <action android:name="com.silentpulse.action.EXECUTE_COMMAND" />
        <!-- Schema request -->
        <action android:name="com.silentpulse.action.REQUEST_SCHEMA" />
    </intent-filter>
</receiver>
```

### Step 2: Implement VoiceCommandReceiver

```kotlin
class VoiceCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.silentpulse.action.EXECUTE_COMMAND" -> {
                val transcript = intent.getStringExtra("EXTRA_TRANSCRIPT") ?: return
                val sessionId = intent.getStringExtra("EXTRA_SESSION_ID") ?: return
                handleCommand(context, transcript, sessionId)
            }
            "com.silentpulse.action.REQUEST_SCHEMA" -> {
                reportSchema(context)
            }
        }
    }

    private fun handleCommand(context: Context, transcript: String, sessionId: String) {
        // YOUR DOMAIN LOGIC HERE — parse the raw command string
        // Example: VoiceCommandParser.parse(transcript) → action + params
        // Then execute the action and reply:

        val reply = Intent("com.silentpulse.action.TTS_REPLY").apply {
            putExtra("EXTRA_SPOKEN_TEXT", "Weight logged as 220 pounds.")
            putExtra("EXTRA_REQUIRE_FOLLOWUP", false)
            putExtra("EXTRA_SESSION_ID", sessionId)
        }
        context.sendBroadcast(reply)
    }

    private fun reportSchema(context: Context) {
        val schema = """["Log a metric", "Start a workout", "Start meditation",
            "Get status of a metric", "Set a goal", "Log food"]"""
        val reply = Intent("com.silentpulse.action.REPORT_SCHEMA").apply {
            putExtra("EXTRA_SCHEMA_JSON", schema)
        }
        context.sendBroadcast(reply)
    }
}
```

### Step 3: Build Your Command Parser

This is YOUR code — any approach works. Here's a recommended pattern:

```kotlin
object VoiceCommandParser {

    data class ParsedCommand(
        val action: String,         // "log_metric", "start_workout", "query_metric"
        val params: Map<String, String>, // {"metric": "weight", "value": "220", "unit": "pounds"}
        val confidence: Float       // 0.0–1.0
    )

    fun parse(transcript: String): ParsedCommand {
        val lower = transcript.lowercase()

        // 1. Number extraction
        val numberMatch = Regex("""(\d+(?:\.\d+)?)\s*(lbs?|pounds?|kg|inches?|cm|mg|dl|%|bpm)?""")
            .find(lower)
        val value = numberMatch?.groupValues?.get(1)
        val unit = numberMatch?.groupValues?.get(2)

        // 2. Fuzzy match against your known metrics/commands
        val metricNames = listOf("weight", "blood sugar", "blood pressure systolic",
            "blood pressure diastolic", "bicep size", "waist", "body fat",
            "calories", "steps", "water", "heart rate")

        val bestMatch = metricNames.minByOrNull { levenshtein(extractMetricPhrase(lower), it) }
        val distance = levenshtein(extractMetricPhrase(lower), bestMatch ?: "")
        val confidence = 1.0f - (distance.toFloat() / (bestMatch?.length ?: 1).coerceAtLeast(1))

        // ... build and return ParsedCommand
    }
}
```

---

## 5. Multi-Turn Conversations (The Ping-Pong Dialog)

This is how disambiguation and confirmation work. Your app controls the entire
conversation flow — SilentPulse just relays.

### Example: Ambiguous command

```
User:  "Computer, Microcore, log blood at 110"

  → SilentPulse sends to Microcore:
    EXECUTE_COMMAND
    EXTRA_TRANSCRIPT = "log blood at 110"
    EXTRA_SESSION_ID = "abc-123"

Microcore: "blood" is ambiguous (systolic? diastolic? blood sugar?)
  → Microcore saves pending: {session="abc-123", value=110, candidates=[...]}
  → Microcore broadcasts:
    TTS_REPLY
    EXTRA_SPOKEN_TEXT = "Did you mean blood sugar, systolic, or diastolic?"
    EXTRA_REQUIRE_FOLLOWUP = true      ← THIS IS THE KEY
    EXTRA_SESSION_ID = "abc-123"

SilentPulse: speaks the question, immediately re-listens

User:  "blood sugar"

  → SilentPulse sees active session "abc-123"
  → Routes directly back to Microcore (no wake word needed):
    EXECUTE_COMMAND
    EXTRA_TRANSCRIPT = "blood sugar"
    EXTRA_SESSION_ID = "abc-123"       ← SAME session

Microcore: resolves pending command + follow-up → logs blood sugar at 110
  → Microcore broadcasts:
    TTS_REPLY
    EXTRA_SPOKEN_TEXT = "Logged blood sugar at 110."
    EXTRA_REQUIRE_FOLLOWUP = false     ← conversation done
    EXTRA_SESSION_ID = "abc-123"

SilentPulse: speaks confirmation, closes session
```

### Implementing the PendingCommandCache

Your app needs a simple cache to hold unresolved commands during follow-ups:

```kotlin
object PendingCommandCache {
    data class PendingCommand(
        val sessionId: String,
        val action: String,
        val partialParams: Map<String, String>,
        val candidates: List<String>,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val cache = mutableMapOf<String, PendingCommand>()
    private const val TIMEOUT_MS = 60_000L  // 60 seconds

    fun put(sessionId: String, command: PendingCommand) {
        evictExpired()
        cache[sessionId] = command
    }

    fun get(sessionId: String): PendingCommand? {
        evictExpired()
        return cache[sessionId]
    }

    fun remove(sessionId: String) { cache.remove(sessionId) }

    private fun evictExpired() {
        val now = System.currentTimeMillis()
        cache.entries.removeAll { now - it.value.createdAt > TIMEOUT_MS }
    }
}
```

Then in your receiver's `handleCommand`:

```kotlin
private fun handleCommand(context: Context, transcript: String, sessionId: String) {
    // Check if this is a follow-up to a pending command
    val pending = PendingCommandCache.get(sessionId)
    if (pending != null) {
        handleFollowUp(context, transcript, sessionId, pending)
        return
    }

    // New command — parse it
    val parsed = VoiceCommandParser.parse(transcript)

    when {
        parsed.confidence > 0.90f -> {
            // High confidence — execute immediately
            executeAction(parsed)
            reply(context, sessionId, "Logged ${parsed.params["metric"]} at ${parsed.params["value"]}.", followUp = false)
        }
        parsed.confidence > 0.65f -> {
            // Medium confidence — ask for confirmation
            PendingCommandCache.put(sessionId, PendingCommand(
                sessionId = sessionId,
                action = parsed.action,
                partialParams = parsed.params,
                candidates = listOf(parsed.params["metric"] ?: "")
            ))
            reply(context, sessionId,
                "Did you mean ${parsed.params["metric"]}? Say yes or no.",
                followUp = true)
        }
        else -> {
            // Low confidence — give up
            reply(context, sessionId, "I didn't understand that command. Please try again.", followUp = false)
        }
    }
}

private fun handleFollowUp(context: Context, transcript: String, sessionId: String, pending: PendingCommand) {
    val lower = transcript.lowercase()
    when {
        lower.contains("yes") -> {
            PendingCommandCache.remove(sessionId)
            executeAction(ParsedCommand(pending.action, pending.partialParams, 1.0f))
            reply(context, sessionId, "Done.", followUp = false)
        }
        lower.contains("no") || lower.contains("cancel") -> {
            PendingCommandCache.remove(sessionId)
            reply(context, sessionId, "Cancelled.", followUp = false)
        }
        else -> {
            // User gave a specific answer (e.g. "blood sugar")
            // Resolve the ambiguity with the new info
            val resolved = resolveAmbiguity(pending, transcript)
            PendingCommandCache.remove(sessionId)
            executeAction(resolved)
            reply(context, sessionId, "Logged ${resolved.params["metric"]} at ${resolved.params["value"]}.", followUp = false)
        }
    }
}

private fun reply(context: Context, sessionId: String, text: String, followUp: Boolean) {
    context.sendBroadcast(Intent("com.silentpulse.action.TTS_REPLY").apply {
        putExtra("EXTRA_SPOKEN_TEXT", text)
        putExtra("EXTRA_REQUIRE_FOLLOWUP", followUp)
        putExtra("EXTRA_SESSION_ID", sessionId)
    })
}
```

---

## 6. The 3-Tier Confidence Flow

All confidence scoring and fuzzy matching lives in YOUR app. SilentPulse doesn't care.

| Confidence | Your app's action | REQUIRE_FOLLOWUP |
|---|---|---|
| **> 90%** (High) | Execute immediately, reply with confirmation | `false` |
| **65–90%** (Medium) | Save to PendingCommandCache, ask user to confirm | `true` |
| **< 65%** (Low) | Reply "I didn't understand" | `false` |

### Recommended fuzzy matching algorithms:
- **Levenshtein distance** — for short strings (metric names, workout names)
- **Metaphone / Double Metaphone** — for phonetic similarity ("systolic" vs "cistolic")
- **Contains/startsWith** — simple but effective for common phrases

---

## 7. App Discovery

When a user says "Computer, what apps can you talk to?", SilentPulse queries
the PackageManager for all apps declaring `ASSISTANT_CAPABLE` and reads their labels.

When a user says "Computer, give me commands in Microcore", SilentPulse sends
`REQUEST_SCHEMA` to your app. You reply with `REPORT_SCHEMA` containing a JSON
array of human-readable command descriptions.

### Schema response format:

```json
[
    "Log a metric (weight, blood sugar, blood pressure, bicep size, body fat, etc.)",
    "Start a workout (StrongLifts, Running, Cycling, etc.)",
    "Start a meditation session",
    "Get status of a metric (calories remaining, latest weight, etc.)",
    "Set a goal",
    "Log food or water intake"
]
```

SilentPulse reads these aloud. The user can then say the command naturally.

---

## 8. Session Lifecycle

```
┌─────────────────────────────────────────────────────────┐
│  User speaks: "Computer, Microcore, log blood at 110"   │
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│  SilentPulse: extract app name, strip boilerplate       │
│  → Opens session (SESSION_ID = abc-123)                 │
│  → Broadcasts EXECUTE_COMMAND to Microcore              │
│     EXTRA_TRANSCRIPT = "log blood at 110"               │
│     EXTRA_SESSION_ID = "abc-123"                        │
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│  Microcore: parses → ambiguous                          │
│  → Saves to PendingCommandCache                         │
│  → Broadcasts TTS_REPLY                                 │
│     EXTRA_SPOKEN_TEXT = "systolic, diastolic, or sugar?" │
│     EXTRA_REQUIRE_FOLLOWUP = true                       │
│     EXTRA_SESSION_ID = "abc-123"                        │
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│  SilentPulse: speaks question, re-listens               │
│  User says: "blood sugar" (no wake word needed)         │
│  → Sees active session abc-123                          │
│  → Routes back to Microcore automatically               │
│     EXTRA_TRANSCRIPT = "blood sugar"                    │
│     EXTRA_SESSION_ID = "abc-123"                        │
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│  Microcore: resolves pending + follow-up                │
│  → Logs blood sugar at 110                              │
│  → Broadcasts TTS_REPLY                                 │
│     EXTRA_SPOKEN_TEXT = "Logged blood sugar at 110."    │
│     EXTRA_REQUIRE_FOLLOWUP = false                      │
│     EXTRA_SESSION_ID = "abc-123"                        │
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│  SilentPulse: speaks confirmation, closes session       │
│  → Returns to wake-word listening                       │
└─────────────────────────────────────────────────────────┘
```

Sessions auto-expire after **60 seconds** of inactivity.

---

## 9. Complete Receiver Template (Copy-Paste Ready)

```kotlin
package com.yourapp.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class VoiceCommandReceiver : BroadcastReceiver() {

    companion object {
        // Actions
        const val ACTION_EXECUTE  = "com.silentpulse.action.EXECUTE_COMMAND"
        const val ACTION_REPLY    = "com.silentpulse.action.TTS_REPLY"
        const val ACTION_REQ_SCHEMA  = "com.silentpulse.action.REQUEST_SCHEMA"
        const val ACTION_RPT_SCHEMA  = "com.silentpulse.action.REPORT_SCHEMA"

        // Extras
        const val TRANSCRIPT      = "EXTRA_TRANSCRIPT"
        const val SESSION_ID      = "EXTRA_SESSION_ID"
        const val SPOKEN_TEXT     = "EXTRA_SPOKEN_TEXT"
        const val REQUIRE_FOLLOWUP = "EXTRA_REQUIRE_FOLLOWUP"
        const val SCHEMA_JSON     = "EXTRA_SCHEMA_JSON"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_EXECUTE -> {
                val text = intent.getStringExtra(TRANSCRIPT) ?: return
                val session = intent.getStringExtra(SESSION_ID) ?: return
                processCommand(context, text, session)
            }
            ACTION_REQ_SCHEMA -> {
                sendSchema(context)
            }
        }
    }

    private fun processCommand(ctx: Context, text: String, sessionId: String) {
        // ── 1. Check for pending follow-up ──
        val pending = PendingCommandCache.get(sessionId)
        if (pending != null) {
            resolveFollowUp(ctx, text, sessionId, pending)
            return
        }

        // ── 2. Parse the new command ──
        // ... YOUR parsing logic here ...

        // ── 3. Reply based on confidence ──
        // High confidence → execute + reply(followUp=false)
        // Medium confidence → cache + reply(followUp=true)
        // Low confidence → reply "didn't understand" (followUp=false)
    }

    private fun resolveFollowUp(ctx: Context, text: String, sessionId: String, pending: Any) {
        // ... resolve ambiguity with user's answer ...
        PendingCommandCache.remove(sessionId)
        reply(ctx, sessionId, "Done.", followUp = false)
    }

    private fun sendSchema(ctx: Context) {
        ctx.sendBroadcast(Intent(ACTION_RPT_SCHEMA).apply {
            putExtra(SCHEMA_JSON, """["Log a metric", "Start workout", "Query status"]""")
        })
    }

    private fun reply(ctx: Context, sessionId: String, text: String, followUp: Boolean) {
        ctx.sendBroadcast(Intent(ACTION_REPLY).apply {
            putExtra(SPOKEN_TEXT, text)
            putExtra(REQUIRE_FOLLOWUP, followUp)
            putExtra(SESSION_ID, sessionId)
        })
    }
}
```

### AndroidManifest.xml entry:

```xml
<receiver
    android:name=".voice.VoiceCommandReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="com.silentpulse.action.ASSISTANT_CAPABLE" />
        <action android:name="com.silentpulse.action.EXECUTE_COMMAND" />
        <action android:name="com.silentpulse.action.REQUEST_SCHEMA" />
    </intent-filter>
</receiver>
```

---

## 10. Privacy & Security

- **All processing is on-device.** SilentPulse has no INTERNET permission (removed
  at manifest level, kernel-enforced). Audio never leaves the hardware.
- **STT engine:** Android's on-device SpeechRecognizer (Google's Soda engine).
  Even though it's a Google component, the INTERNET block prevents any network
  traffic. Audio is processed locally by the downloaded language pack.
- **Broadcasts are app-to-app** on the same device. No cloud intermediary.
- **Session data** (pending commands) lives in memory only and expires after 60s.

---

## 11. Known Limitations

1. **INTERNET permission:** If your app needs INTERNET for other features, that's
   fine — but the voice command flow itself should never require network access.
2. **Work Profile:** Broadcasts may not cross the work/personal profile boundary
   depending on MDM policy.
3. **Background execution limits:** On Android 14+, your BroadcastReceiver has
   limited execution time. For long-running operations, enqueue work to a Service
   or WorkManager from the receiver.
4. **Wake word accuracy:** "Computer" must be spoken clearly. Android STT may
   occasionally miss it or false-trigger on similar words.

---

## 12. Checklist for New Apps

- [ ] Add `VoiceCommandReceiver` with intent filters in manifest
- [ ] Handle `EXECUTE_COMMAND` → parse transcript → reply via `TTS_REPLY`
- [ ] Handle `REQUEST_SCHEMA` → reply via `REPORT_SCHEMA` with JSON
- [ ] Implement fuzzy matching for your domain (metric names, workout names, etc.)
- [ ] Implement `PendingCommandCache` for multi-turn disambiguation
- [ ] Test: `adb shell am broadcast -a com.silentpulse.action.EXECUTE_COMMAND --es EXTRA_TRANSCRIPT "log weight 220" --es EXTRA_SESSION_ID "test-1" -n com.yourapp/.voice.VoiceCommandReceiver`
- [ ] Always include `EXTRA_SESSION_ID` in your `TTS_REPLY` broadcasts

---

*Created: April 2026. Last updated: April 7, 2026.*
*Keep this document updated as new ecosystem apps are added.*