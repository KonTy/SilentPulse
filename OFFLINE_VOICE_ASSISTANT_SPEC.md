# SilentPulse Offline Voice Assistant Ecosystem Specification

## 1. Overview
This document outlines the architecture for a **100% offline, privacy-first, zero-LLM voice assistant ecosystem**. 

In this ecosystem, **SilentPulse** acts as the central "Ears and Mouth" (recording audio, running offline STT via Whisper/Vosk, and reading responses via Android TTS). Other apps, such as **Microcore** (a fitness and metric tracker), act as "Brains" that handle domain-specific logic, fuzzy matching, and database execution.

The apps communicate entirely via Android Broadcast Intents without deep-linking UI interruptions (unless explicitly requested), allowing voice commands to execute seamlessly in the background while driving.

---

## 2. Core Interaction Loop
1. **Wake Word:** Vosk listens for the wake word (e.g., "SilentPulse").
2. **Transcription:** Whisper transcribes the spoken command to text (e.g., "Tell Microcore to log my weight as 220 pounds").
3. **Routing:** SilentPulse extracts the target app name ("Microcore"), removes the boilerplate, and broadcasts the raw string: `"log metric weight 220 pounds"`.
4. **Execution:** Microcore receives the string, parses it using Regex and Fuzzy Matching, logs the data, and broadcasts a message back to SilentPulse.
5. **Feedback:** SilentPulse reads the response aloud: *"Logging weight as 220 pounds."*

---

## 3. Communication Protocol (Android Broadcasts)

### 3.1 App Discovery
Apps expose their support for the assistant via the `AndroidManifest.xml`.
* **Intent Filter Action:** `com.silentpulse.action.ASSISTANT_CAPABLE`
* **Query:** SilentPulse can be asked: *"What apps can you talk to?"* It queries the PackageManager for this intent filter and reads the app labels.

### 3.2 Command Schema / Querying
Apps must be able to list what they can do.
* **Request Intent:** `com.silentpulse.action.REQUEST_SCHEMA`
* **Response Intent:** `com.silentpulse.action.REPORT_SCHEMA`
* **Example Interaction:**
  * *User:* "Give me commands in Microcore."
  * *Microcore Replies with JSON:* `["Log a metric", "Start a workout", "Get status of metric"]`
  * *User:* "Get status of what?"
  * *Microcore Replies with params:* `["Current fast", "Calories remaining"]`

### 3.3 Command Execution & State Machine (Ping-Pong)
All commands passed to an app include a `SESSION_ID`.

* **SilentPulse -> Target App (Execution):**
  * `Action:` `com.silentpulse.action.EXECUTE_COMMAND`
  * `Extras:` `EXTRA_TRANSCRIPT`, `EXTRA_SESSION_ID`

* **Target App -> SilentPulse (Reply & TTS):**
  * `Action:` `com.silentpulse.action.TTS_REPLY`
  * `Extras:` 
    * `EXTRA_SPOKEN_TEXT` (String to read)
    * `EXTRA_REQUIRE_FOLLOWUP` (Boolean: if true, SilentPulse immediately starts listening again for a Yes/No or parameter).

---

## 4. Domain Logic: Fuzzy Matching & Confidence Tiers
To handle Speech-to-Text typos (e.g., "Start strong lift" instead of "StrongLifts 5x5"), the logic resides in the **target app (Microcore)** using algorithms like Levenshtein Distance or Metaphone.

### The 3-Tier Execution Flow:
1. **High Confidence (Match > 90%):**
   * *User:* "Log weight 220."
   * *Microcore Action:* Saves to DB. Broadcasts response (`EXTRA_REQUIRE_FOLLOWUP = false`).
   * *SilentPulse:* Reads "Weight logged as 220."
2. **Medium Confidence / Ambiguity (Match 65% - 89%):**
   * *User:* "Start strong lift."
   * *Microcore Action:* Finds "StrongLifts 5x5". Pauses execution. Saves pending action to memory using the `SESSION_ID`.
   * *Microcore -> SilentPulse:* Broadcasts response: "I found StrongLifts 5x5. Should I start this?" (`EXTRA_REQUIRE_FOLLOWUP = true`).
   * *SilentPulse:* Reads prompt, immediately listens.
   * *User:* "Yes."
   * *SilentPulse -> Microcore:* Sends new intent: `EXECUTE_COMMAND` containing text "Yes" and same `SESSION_ID`.
   * *Microcore:* Completes the workout logging.
3. **Low Confidence (Match < 65%):**
   * *Microcore -> SilentPulse:* Broadcasts "I didn't recognize that command. Please try again." (`EXTRA_REQUIRE_FOLLOWUP = false`).

---

## 5. Known Limitations
1. **Replying vs. Initiating Messages (e.g., Signal):**
   * App can intercept notifications and *reply* invisibly in the background using `RemoteInput`.
   * App *cannot* initiate new stealth SMS/Signal messages in the background due to Android security restrictions. Initiating a new message requires popping open an `ACTION_SEND` Intent.
2. **Work Profile Notifications:**
   * Reading work profile notifications depends entirely on the MDM policy. If the notification text is hidden on the lock screen ("Content Hidden"), the `NotificationListenerService` cannot read it.

---

## 6. Instructions for LLM Coding Engines

### When implementing SilentPulse (The Hub):
1. Create a `VoiceAssistantService` that handles Vosk wake word implementation and binds to the Whisper engine.
2. Maintain a `SessionManager` that tracks active multi-turn conversations.
3. Listen for `com.silentpulse.action.TTS_REPLY`. Play the audio using native `TextToSpeech` and immediately restart Whisper STT if `EXTRA_REQUIRE_FOLLOWUP` is true.
4. Ensure `PackageManager` logic properly caches discovered assistant-capable apps to avoid lookup delays.

### When implementing Target Apps (e.g., Microcore):
1. Implement a `VoiceCommandReceiver` extending `BroadcastReceiver`.
2. Expose the `intent-filter` `com.silentpulse.action.ASSISTANT_CAPABLE` in the manifest.
3. Build a pure Kotlin/Java NLP parser:
   * **Number extraction:** Regex `([0-9]+(?:\.[0-9]+)?)\s*(kg|lbs|cm|%)?`
   * **String matching:** Iterate over internal dictionaries using Levenshtein distance.
4. Maintain a `PendingCommandCache` to hold unresolved commands for up to 60 seconds while waiting on a Yes/No follow-up.

---
*Created: April 2026. Keep this document updated as new ecosystem apps are added.*