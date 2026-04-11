# SilentPulse

**Privacy-first SMS & messaging app with an offline voice assistant for hands-free drive mode.**

Forked from [QKSMS](https://github.com/moezbhatti/qksms) and rebuilt for corporate and security-sensitive environments where **no data may leave the device to any third party**.

---

## What is SilentPulse?

SilentPulse is a drop-in replacement for the Android stock SMS app. On top of that it ships a fully **offline, always-on voice assistant** you activate hands-free while driving. Every feature works without internet — except a small set of explicitly whitelisted privacy-respecting APIs (weather, navigation ETA, stock prices, and optional AI search). **Google is never contacted.**

---

## Voice Assistant — Drive Mode

### Wake word

Say **"Computer"** at any time while the assistant is running. The Vosk on-device keyword spotter hears it silently (zero beeps, minimal CPU). The mic then hands off to Android's on-device Speech Recognizer and plays a single beep so you know it is ready.

### Supported voice commands

| Category | Example phrases |
|---|---|
| **Time** | "What time is it" · "What time is it in Tokyo" · "Time in New York" |
| **Weather** | "What's the weather" · "Weather in Berlin" · "Weather on the corridor" |
| **Navigation** | "Navigate to 123 Main Street" · "Directions to the airport" · "Stop navigation" |
| **Drive time** | "How long to downtown" · "Drive time to Chicago" |
| **Notifications** | "Read my notifications" · "Read my email" — then: Skip / Reply / Repeat / Dismiss / Stop |
| **Music** | "Play music" · "Play [artist/song]" · "Resume" |
| **Audiobooks** | "Listen to book" · "Open voice" |
| **Stock prices** | "Price of bitcoin" · "Price of AAPL" · "Price of gold" |
| **General questions** | "What is the capital of France?" · "Who invented the telephone?" |
| **App control** | "Open Maps" · "Close Spotify" |
| **Cross-app logging** | "Computer, Microcore, log weight 220 pounds" |
| **Help** | "Help" · "What can you do?" |

### Architecture — two-phase listening

Phase 1 — **Vosk keyword spotter** runs continuously, constrained to the grammar `["computer", "[unk]"]`. It reads raw PCM via AudioRecord — zero beeps, zero SpeechRecognizer restarts, minimal battery drain.

Phase 2 — When Vosk hears "Computer" it releases the mic and hands off to **Android SpeechRecognizer** (`EXTRA_PREFER_OFFLINE=true`) for a single free-form utterance. The recognizer plays its natural beep — this is the "ready" signal the user hears.

The transcript then flows through a routing chain:

| Handler | Backend |
|---|---|
| `TimeHandler` | IANA timezone DB — fully offline, 150+ city aliases, Levenshtein fuzzy match |
| `WeatherCommandHandler` | open-meteo.com / wttr.in — open source, no API key |
| `NavigationCommandHandler` | OSRM for ETA; launches OsmAnd / Maps for turn-by-turn |
| `DriveTimeHandler` | router.project-osrm.org |
| `NotificationReaderHandler` | Reads notification shade; inline reply via RemoteInput |
| `MusicCommandHandler` | MediaBrowser / MediaController, local library |
| `StockQueryHandler` | Yahoo Finance unofficial API |
| `GeneralQueryHandler` | DuckDuckGo instant answers → Wikipedia → Brave fallback |
| `BraveSearchHandler` | Optional AI search summariser (user-supplied API key) |
| `CommandRouter` | Cross-app broadcast dispatch with Levenshtein fuzzy app-name matching |

All TTS uses Android's on-device `TextToSpeech` engine with automatic language/locale detection. No cloud speech of any kind.

### Cross-app voice broadcast protocol

Third-party apps can register as voice targets. SilentPulse:

1. Discovers registered apps via `PackageManager` (`ASSISTANT_CAPABLE` intent filter)
2. Strips the app name from the command and dispatches an `EXECUTE_COMMAND` broadcast
3. The target app replies with a `TTS_REPLY` broadcast; SilentPulse speaks it aloud
4. Supports multi-turn disambiguation — if the target replies with candidates, SilentPulse asks the user to clarify, then re-dispatches with the resolved intent

Example: *"Computer, Microcore, log weight 220 pounds"* — SilentPulse routes "log weight 220 pounds" to the Microcore health app, which logs the measurement and replies *"Logged weight at 220 pounds."*

---

## Privacy Architecture

> **Core principle: if it touches Google, it is blocked.**

### Network security

`network_security_config.xml` acts as a domain-level TLS firewall:

- `base-config` trusts **zero** certificate authorities — any unlisted domain fails at TLS handshake
- Only explicitly whitelisted privacy-respecting domains can connect:

| Domain | Purpose |
|---|---|
| `open-meteo.com` | Weather (open source, no key) |
| `wttr.in` | Weather fallback (open source, no key) |
| `api.duckduckgo.com` | General instant answers (no key) |
| `en.wikipedia.org` | Knowledge lookups (no key) |
| `nominatim.openstreetmap.org` | Geocoding (OpenStreetMap, no key) |
| `router.project-osrm.org` | Drive-time routing (OSRM open source) |
| `query1/2.finance.yahoo.com` | Stock prices (unofficial) |
| `api.search.brave.com` | AI search summariser (optional — user-supplied key) |

### What is explicitly absent

- No Firebase of any kind (no Crashlytics, Analytics, FCM, Remote Config, Performance)
- No Google Play Services network calls — ML Kit runs in fully bundled/embedded mode only
- No crash or event reporting (Sentry, Datadog, Amplitude, Mixpanel, Segment…)
- No advertising SDKs
- No cloud TTS or cloud STT — all speech processing is on-device only
- No log shipping — Timber logs go to a local file only, never off-device
- No A/B testing or feature flag services
- No referral or attribution tracking

### Build variants

| Variant | Description |
|---|---|
| `noAnalytics` *(default)* | No Firebase, no analytics, no crash reporting. This is the only variant that should be distributed. |
| `withAnalytics` | Legacy flavor kept for historical reference — do not distribute. |

---

## Building

Requires Java 17 and NDK `28.2.13676358`.

```bash
# Debug APK — sideload via ADB for testing
./gradlew :presentation:assembleNoAnalyticsDebug

# Release APK — uses production keystore if secrets are set, otherwise debug key
./gradlew :presentation:assembleNoAnalyticsRelease
```

### Installing via ADB

```bash
adb install presentation/build/outputs/apk/noAnalytics/debug/presentation-noAnalytics-debug.apk
```

---

## CI / CD

Every push to `master` runs [Build and Release](https://github.com/KonTy/SilentPulse/actions/workflows/release.yml):

1. Installs NDK `28.2.13676358`
2. Builds both `noAnalytics-debug` and `noAnalytics-release` APKs
3. Falls back to a freshly generated debug keystore when production signing secrets are absent
4. Publishes a GitHub pre-release with both APKs attached

**Always check the Actions tab after pushing.** If a run fails, expand the failed step. Common failure modes are documented in `.github/copilot-instructions.md`.

### Production signing secrets

Set in **Settings → Secrets → Actions** on the repository:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | Store password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

---

## Project structure

```
android-smsmms/       MMS library (fork of klinker41/android-smsmms)
common/               Shared utilities
data/                 Realm database, repositories
domain/               Use-cases / business logic
presentation/         UI + Voice Assistant
  └─ feature/
       ├─ assistant/
       │    ├─ VoiceAssistantService.kt       Foreground service — wake-word → route → TTS
       │    ├─ VoskWakeWordDetector.kt        Phase 1: always-on Vosk keyword spotter
       │    ├─ CommandRouter.kt              Cross-app broadcast dispatch
       │    ├─ TimeHandler.kt               Offline timezone resolution (150+ cities)
       │    ├─ WeatherCommandHandler.kt     Weather (open-meteo / wttr.in)
       │    ├─ NavigationCommandHandler.kt  Turn-by-turn + ETA
       │    ├─ DriveTimeHandler.kt          OSRM drive-time queries
       │    ├─ NotificationReaderHandler.kt Hands-free notification reading + reply
       │    ├─ StockQueryHandler.kt         Real-time stock / crypto prices
       │    ├─ GeneralQueryHandler.kt       DuckDuckGo + Wikipedia + Brave fallback
       │    ├─ BraveSearchHandler.kt        Optional AI search summariser
       │    └─ MusicCommandHandler.kt       Local media playback control
       └─ drivemode/
            ├─ DriveModeService.kt          Core drive-mode notification reader
            ├─ AndroidSttEngine.kt          On-device SpeechRecognizer wrapper
            └─ VoiceActivityDetector.kt     VAD for push-to-listen
```

---

## License

Released under the **GNU General Public License v3.0 (GPLv3)** — see `LICENSE`.

Original QKSMS by [Moez Bhatti](https://github.com/moezbhatti). SilentPulse additions copyright their respective authors.
