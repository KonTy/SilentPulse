#!/usr/bin/env bash
# Voice Feature: Text-to-Speech for notifications (Drive Mode)
# =============================================================================
# Feature: "Drive Mode" - reads all incoming notifications aloud
#   - Uses Android TTS engine (fully offline, nothing leaves phone)
#   - Optional: Piper TTS for higher quality (still offline, downloads model)
#   - Privacy: zero network calls EVER for TTS
# =============================================================================
# set -e removed: aider exits non-zero on "no changes" which would abort the phase
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"
SCRIPTS="$REPO/scripts/aider"

echo "→ Phase 4: Voice TTS - Drive Mode feature..."

# ── Step 1: Add TTS dependencies to build.gradle ─────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/build.gradle \
  --message "$(cat << 'MSG'
Add these dependencies to presentation/build.gradle:
1. implementation 'androidx.media:media:1.7.0' - for MediaSession/audio focus
2. implementation 'com.github.rhasspy:piper-android:1.2.4' - offline Piper TTS (via JitPack)
   If unavailable, use: implementation 'io.github.nicktindall:piper-tts-android:0.4.0'
3. implementation 'androidx.work:work-runtime-ktx:2.9.1' - for background TTS work

Also add to android block:
  buildFeatures { 
    buildConfig = true
  }
MSG
)"

# ── Step 2: Create Drive Mode preferences ────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  domain/src/main/java/com/moez/QKSMS/util/Preferences.kt \
  --message "$(cat << 'MSG'
Add new preference keys to Preferences.kt for Drive Mode:
  val driveModeEnabled = rxPrefs.getBoolean("drive_mode_enabled", false)
  val driveModeReadSms = rxPrefs.getBoolean("drive_mode_read_sms", true)
  val driveModeReadAllNotifications = rxPrefs.getBoolean("drive_mode_read_all_notif", false)
  val driveModeTtsEngine = rxPrefs.getString("drive_mode_tts_engine", "android") // "android" or "piper"
  val driveModeVoiceReplyEnabled = rxPrefs.getBoolean("drive_mode_voice_reply", false)
  val driveModeReplyTimeoutSecs = rxPrefs.getInteger("drive_mode_reply_timeout", 30)
  val driveModeAutoToggleOnCarplay = rxPrefs.getBoolean("drive_mode_auto_carplay", true)
MSG
)"

# ── Step 3: Create TTS Engine abstraction ────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  --message "$(cat << 'MSG'
Create these new files in presentation/src/main/java/com/moez/QKSMS/feature/drivemode/:

1. TtsEngine.kt - Interface:
```kotlin
interface TtsEngine {
    fun speak(text: String, onDone: () -> Unit = {})
    fun stop()
    fun shutdown()
    val isReady: Boolean
}
```

2. AndroidTtsEngine.kt - Uses Android's built-in TextToSpeech (PRIVACY: offline only):
```kotlin
// Uses TextToSpeech(context, onInit) 
// IMPORTANT: setLanguage(Locale.getDefault())
// IMPORTANT: never call any network APIs
// onInit: check TextToSpeech.SUCCESS
// speak() uses TextToSpeech.speak() with QUEUE_ADD
// Uses UtteranceProgressListener to call onDone
```

3. PiperTtsEngine.kt - Stub for Piper TTS (higher quality, still offline):
```kotlin
// Stub implementation - falls back to AndroidTtsEngine if Piper not initialized
// Real Piper init involves downloading a voice model (onnx) to internal storage
// Include TODO comments for full Piper integration
```

4. TtsEngineFactory.kt:
```kotlin
// Creates the right TTS engine based on Preferences.driveModeTtsEngine
// "android" -> AndroidTtsEngine
// "piper" -> PiperTtsEngine (with AndroidTtsEngine fallback)
```

These files MUST NOT make any network calls. All TTS stays on-device.
MSG
)"

# ── Step 4: Create Drive Mode Service ────────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  --message "$(cat << 'MSG'
Create presentation/src/main/java/com/moez/QKSMS/feature/drivemode/DriveModeService.kt:

This is a Foreground Service that:
1. Shows a persistent notification: "Drive Mode Active - SilentPulse is reading your messages"
2. Listens to incoming SMS via BroadcastReceiver for new messages
3. When a message arrives: uses TtsEngine to read aloud: "Message from [sender]: [body]"
4. Audio focus: requests AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK so music ducks
5. Stops reading if headphones are disconnected (BroadcastReceiver for ACTION_HEADSET_PLUG)
6. Exposes start()/stop() companion object methods
7. Reads from Preferences to check driveModeEnabled, driveModeReadSms

Key Android APIs to use:
- android.speech.tts.TextToSpeech
- android.media.AudioManager.requestAudioFocus()
- AudioFocusRequest.Builder (API 26+)
- Service.startForeground() with FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

PRIVACY: No network calls, no data leaves phone.
class DriveModeService : LifecycleService()
MSG
)"

# ── Step 5: Create Drive Mode settings UI ────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/java/com/moez/QKSMS/feature/settings/SettingsController.kt \
  presentation/src/main/res/layout/settings_controller.xml \
  presentation/src/main/res/values/strings.xml \
  --message "$(cat << 'MSG'
Add a "Drive Mode" section to the Settings screen:

In SettingsController.kt - add a section with:
1. A master toggle: "Drive Mode" (driveModeEnabled preference)
2. Sub-option: "Read SMS aloud" (driveModeReadSms) - visible when drive mode enabled
3. Sub-option: "Read all notifications" (driveModeReadAllNotifications)
4. Sub-option: "Enable voice replies" (driveModeVoiceReplyEnabled)  
5. Sub-option: "Reply timeout" (driveModeReplyTimeoutSecs) - shows "X seconds"
6. Sub-option: "TTS Engine" - radio between "Android (Offline)" and "Piper (High Quality, Offline)"
7. Info text: "🔒 All audio stays on your device. Nothing is sent to the cloud."

Add appropriate string resources to strings.xml.
Add PreferenceView items to the settings layout.
Wire the toggle to start/stop DriveModeService.
MSG
)"

# ── Step 6: Register in Manifest ─────────────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/AndroidManifest.xml \
  --message "$(cat << 'MSG'
Add to AndroidManifest.xml:
1. Permission: android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK
2. Register DriveModeService:
   <service android:name=".feature.drivemode.DriveModeService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />
3. Permission for reading notifications (will use NotificationListenerService in Phase 5):
   android.permission.BIND_NOTIFICATION_LISTENER_SERVICE
MSG
)"

echo "✓ Phase 4 COMPLETE - Drive Mode TTS feature scaffolded"
