# SilentPulse – Copilot Instructions

## Project Overview
SilentPulse is a **privacy-first SMS/messaging app** targeting corporate and security-sensitive users.
The app handles corporate communications (email, Teams, SMS) with a strict policy that
**no data may be leaked to Google or any unauthorized third-party company**.

---

## CRITICAL: Zero-Google / Zero-Telemetry Policy

**These rules are non-negotiable and must never be violated, even if a library "makes things easier".**

### Network Security Architecture
The app **does** have `INTERNET` permission. Network access is controlled by
`network_security_config.xml` which acts as a domain-level firewall:

- **base-config trusts ZERO certificate authorities** → any TLS connection to a
  non-whitelisted domain fails at handshake ("Trust anchor not found")
- **Only explicitly whitelisted open-source/privacy-respecting domains** can connect
- **All Google domains are blocked** — `*.google.com`, `*.googleapis.com`,
  `*.gstatic.com`, `*.firebase.com`, etc. will fail TLS

#### Currently Whitelisted Domains
| Domain | Purpose |
|--------|---------|
| `open-meteo.com` | Weather API (open-source, no API key) |
| `wttr.in` | Weather service (open-source) |
| `api.duckduckgo.com` | Instant answers API (no API key) |
| `en.wikipedia.org` | Knowledge lookups (no API key) |
| `nominatim.openstreetmap.org` | Geocoding (OpenStreetMap, no API key) |
| `router.project-osrm.org` | Routing/drive time (OSRM, open-source) |
| `query1.finance.yahoo.com` | Stock prices (unofficial API) |
| `query2.finance.yahoo.com` | Stock prices (unofficial API) |
| `api.search.brave.com` | AI search summarizer (requires API key) |

To add a new domain: edit `presentation/src/main/res/xml/network_security_config.xml`
and add a `<domain-config>` section with `<certificates src="system" />` trust anchors.

### Absolutely Forbidden
- **No Firebase** of any kind: no Crashlytics, no Analytics, no FCM, no Remote Config, no Performance, no App Distribution
- **No Google Play Services** network calls (ML Kit must run in **fully bundled/embedded** mode, not "thin" mode that downloads models at runtime via Play Services)
- **No Google Analytics, Google Tag Manager, or any analytics SDK**
- **No Sentry, Datadog, Amplitude, Mixpanel, Segment, New Relic, or any crash/event reporting service**
- **No advertising SDKs** of any kind
- **No cloud-based TTS or STT** — all speech must use Android's on-device engine only
- **No connecting to any Google domain** from within the app process (enforced by network_security_config.xml)
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

### Approved Libraries & Services
- `com.google.mlkit:language-id` — **ONLY when configured with `LanguageIdentificationOptions` using the bundled/embedded model** (not the thin GMS-based client). Must not require Play Services at runtime.
- Android `TextToSpeech` (on-device engine)
- Android `SpeechRecognizer` (on-device — `EXTRA_PREFER_OFFLINE=true` set in `AudioSttEngine`)
- Realm (local database)
- Timber (local logcat + file only — never ship logs off-device)
- OkHttp / URL connections **only to whitelisted domains** (enforced by network_security_config.xml)

---

## Privacy Architecture Rules

1. **Messages, contacts, and logs stay on the device.** Never send message content or contact data off-device.
2. **Network access is domain-whitelisted.** The app connects only to open-source, privacy-respecting APIs listed in `network_security_config.xml`. All Google domains are blocked at TLS level.
3. **Log files** written by `FileLoggingTree` are stored in the app's private directory only and are never uploaded.
4. **Debug builds**: full Timber logging to logcat + file is acceptable.
5. **Release builds**: Timber `DebugTree` must NOT be planted. Only `FileLoggingTree` (local file, never uploaded).
6. **ML Kit language identification** must be initialized with the bundled model option. Never use the default GMS thin client.
7. **STT privacy**: `AudioSttEngine` sets `EXTRA_PREFER_OFFLINE=true` to force on-device SODA recognition. Note: `SpeechRecognizer` runs in the system process (`com.google.android.as`) which is outside our network_security_config scope — the offline flag is the actual privacy control there.

---

## Drive Mode – Voice Processing Rules

- TTS must use `android.speech.tts.TextToSpeech` with `Locale`-based language selection — no cloud TTS.
- STT must use `android.speech.SpeechRecognizer` — audio is processed on-device by Google's embedded model.
  Note: on stock Android, `SpeechRecognizer` may route through Google's servers depending on the device.
  Future: migrate to Vosk (already a dependency) for fully air-gapped STT.
- Voice reply sends text via `RemoteInput` inline reply — no cloud intermediary.
- Voice assistant queries (weather, navigation, general questions) use only whitelisted open-source APIs
  (enforced by network_security_config.xml) — never Google.

### Android 14+ Navigation Launch Guardrails

- Background navigation launches from `VoiceAssistantService` must go through `PendingIntent.getActivity(...)` plus `PendingIntent.send(...)`.
- **Never** delay the actual navigation launch until an `onSpeak(...)` / TTS completion callback. Launch first, then speak confirmation. Android background-activity privileges can be lost by the time the callback runs.
- When using BAL on Android 14+, set **creator** opt-in during PendingIntent creation with `setPendingIntentCreatorBackgroundActivityStartMode(...)` and set **sender** opt-in only at send time with `setPendingIntentBackgroundActivityStartMode(...)`.
- Do not set sender BAL mode while creating a `PendingIntent`; that crashes on modern Android.
- Treat `SYSTEM_ALERT_WINDOW` as a required runtime dependency for background navigation launches on Android 14+. Check `Settings.canDrawOverlays(...)` before trying to launch, and if it is missing, refresh the assistant notification and direct the user to the overlay action instead of attempting a blocked launch.
- Keep BAL / launch diagnostics behind `BuildConfig.DEBUG` so debug builds are richly diagnosable without increasing release logging.
- Preserve the foreground assistant notification action that opens `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` when overlay permission is missing.
- Preserve `VoiceAssistantService` TTS initialization (`tts = TextToSpeech(this, this)`) and keep `maybeStartListening()` gated on both `ttsReady` and `voskModelReady`.

---

## Dependency Rules

Before adding ANY new dependency:
1. Verify it does **not connect to Google**, Firebase, or any analytics/telemetry endpoint.
2. Verify it does **not require Play Services** or Google account sign-in.
3. Verify it does **not phone home** for licensing, telemetry, or model updates.
4. If it is a Google/ML Kit library, ensure it uses the **bundled/embedded variant**, not the GMS thin client.
5. Never add a dependency that pulls in `com.google.firebase`, `com.google.android.gms`, or `com.google.android.datatransport` transitively.
6. If a new dependency requires network access to a new domain, add that domain to `network_security_config.xml` — the domain must be open-source / privacy-respecting and must **not** be a Google domain.

---

## Build Variants

- `noAnalytics` (default) — **no Firebase, no analytics, no crash reporting**. This is the production variant.
- `withAnalytics` — legacy flavor kept for reference only. Should not be distributed.
- Always build and test with `noAnalytics`.

---

## Manifest Rules

- `android.permission.INTERNET` is present and required for voice-assistant queries
  (weather, navigation, stocks, general knowledge via whitelisted APIs).
- **All network privacy is enforced by `network_security_config.xml`**, not by
  removing the INTERNET permission.
- Never add a Google domain to the network security config whitelist.
---

## Code Style for Privacy

- Never log message content at `INFO` or higher in release builds.
- Never log phone numbers, contact names, or message bodies to any persistent store.
- `BuildConfig.DEBUG` guards must wrap all verbose drive mode logging.
- The `com.android.shell` entry in `isMessagingApp()` is gated on `BuildConfig.DEBUG` — keep it that way.
- `DebugTestReceiver` is gated on `BuildConfig.DEBUG` — keep it that way.

---

## Deployment & CI Checklist

**After every push to master, check the CI pipeline immediately:**

1. Open [Actions → Build and Release](https://github.com/KonTy/SilentPulse/actions/workflows/release.yml)
2. Wait for the run to complete (~7–8 minutes).
3. If it fails, click the failed job → expand each step to find the first non-zero exit.
4. Fix the root cause, push, and re-verify the next run passes before deploying to a device.

### Common failure modes to know

| Symptom | Root cause | Fix |
|---|---|---|
| `packageNoAnalyticsRelease FAILED` with no keystore secret | Wrong keystore path | Workflow now uses `${HOME}/.android/debug.keystore`; a `keytool` pre-step guarantees it exists |
| APK glob `*.apk` matches nothing in release step | Release build failed silently | Check the `assembleNoAnalyticsRelease` step above it |
| Node.js 20 action warning becomes error (after Jun 2026) | Outdated action runtime | `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24: true` is set in the workflow env |

### Signing secrets (for production releases)

Set these in **Settings → Secrets and variables → Actions** on the repo:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` output |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias inside the keystore |
| `KEY_PASSWORD` | Key password |

If secrets are absent the workflow falls back to a debug keystore so builds always succeed.
