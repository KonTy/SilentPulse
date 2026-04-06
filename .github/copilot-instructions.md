# SilentPulse – Copilot Instructions

## Project Overview
SilentPulse is a **100% offline, privacy-first SMS/messaging app** targeting corporate and security-sensitive users.
The app is used to handle corporate communications (email, Teams, SMS) in environments where cloud services are
strictly prohibited by company policy.

---

## CRITICAL: Zero-Telemetry / Zero-Cloud Policy

**These rules are non-negotiable and must never be violated, even if a library "makes things easier".**

### Absolutely Forbidden
- **No Firebase** of any kind: no Crashlytics, no Analytics, no FCM, no Remote Config, no Performance, no App Distribution
- **No Google Play Services** network calls (ML Kit must run in **fully bundled/embedded** mode, not "thin" mode that downloads models at runtime via Play Services)
- **No Google Analytics, Google Tag Manager, or any analytics SDK**
- **No Sentry, Datadog, Amplitude, Mixpanel, Segment, New Relic, or any crash/event reporting service**
- **No advertising SDKs** of any kind
- **No cloud-based TTS or STT** — all speech must use Android's on-device engine only
- **No network requests from within the app** except MMS transport (which uses the carrier network, not the internet)
- **No `INTERNET` permission** in the manifest (verify before every build)
- **No remote logging, log shipping, or log aggregation**
- **No A/B testing or feature flag services**
- **No referral tracking or attribution SDKs**

### Currently Present — Must Be Removed
The following exist in the codebase and MUST be cleaned up in a dedicated privacy pass:
- `com.google.firebase:firebase-crashlytics` (withAnalytics flavor only — noAnalytics flavor is the default)
- `com.google.gms:google-services` plugin
- `com.google.firebase:firebase-crashlytics-gradle` classpath
- `CrashlyticsTree.kt` — replace with file-only logging in all flavors
- `AnalyticsManager` interface and all implementations — should be no-op stubs only
- `ReferralManager` — should be a no-op stub

### Approved Libraries (Offline / On-Device Only)
- `com.google.mlkit:language-id` — **ONLY when configured with `LanguageIdentificationOptions` using the bundled/embedded model** (not the thin GMS-based client). Must not require Play Services at runtime.
- Android `TextToSpeech` (on-device engine)
- Android `SpeechRecognizer` (on-device Google STT engine — audio processed on-device by default)
- Realm (local database)
- Timber (local logcat + file only — never ship logs off-device)

---

## Privacy Architecture Rules

1. **All data stays on the device.** Messages, contacts, logs — nothing leaves the hardware.
2. **No `INTERNET` permission.** If a new dependency requires it, reject the dependency.
3. **Log files** written by `FileLoggingTree` are stored in the app's private directory only and are never uploaded.
4. **Debug builds**: full Timber logging to logcat + file is acceptable.
5. **Release builds**: Timber `DebugTree` must NOT be planted. Only `FileLoggingTree` (local file, never uploaded).
6. **ML Kit language identification** must be initialized with the bundled model option. Never use the default GMS thin client.

---

## Drive Mode – Voice Processing Rules

- TTS must use `android.speech.tts.TextToSpeech` with `Locale`-based language selection — no cloud TTS.
- STT must use `android.speech.SpeechRecognizer` — audio is processed on-device by Google's embedded model.
  Note: on stock Android, `SpeechRecognizer` may route through Google's servers depending on the device.
  Future: migrate to Vosk (already a dependency) for fully air-gapped STT.
- Voice reply sends text via `RemoteInput` inline reply — no cloud intermediary.

---

## Dependency Rules

Before adding ANY new dependency:
1. Verify it has **no network calls** in its default configuration.
2. Verify it does **not require Play Services** or Google account sign-in.
3. Verify it does **not phone home** for licensing, telemetry, or model updates.
4. If it is a Google/ML Kit library, ensure it uses the **bundled/embedded variant**, not the GMS thin client.
5. Never add a dependency that pulls in `com.google.firebase`, `com.google.android.gms`, or `com.google.android.datatransport` transitively.

---

## Build Variants

- `noAnalytics` (default) — **no Firebase, no analytics, no crash reporting**. This is the production variant.
- `withAnalytics` — legacy flavor kept for reference only. Should not be distributed.
- Always build and test with `noAnalytics`.

---

## Manifest Rules

- `android.permission.INTERNET` must **never** appear in the manifest.
- Verify with: `grep -r "INTERNET" presentation/src/main/AndroidManifest.xml`
- If any transitive dependency injects `INTERNET`, it must be removed via manifest merger:
  ```xml
  <uses-permission android:name="android.permission.INTERNET"
      tools:node="remove" />
  ```

---

## Code Style for Privacy

- Never log message content at `INFO` or higher in release builds.
- Never log phone numbers, contact names, or message bodies to any persistent store.
- `BuildConfig.DEBUG` guards must wrap all verbose drive mode logging.
- The `com.android.shell` entry in `isMessagingApp()` is gated on `BuildConfig.DEBUG` — keep it that way.
- `DebugTestReceiver` is gated on `BuildConfig.DEBUG` — keep it that way.
