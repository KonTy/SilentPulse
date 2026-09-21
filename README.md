# SilentPulse

**Privacy-first SMS & messaging app with an offline voice assistant for hands-free drive mode.**

Forked from [QKSMS](https://github.com/moezbhatti/qksms) and rebuilt to keep private message storage and speech processing local.

---

## What is SilentPulse?

SilentPulse is a drop-in replacement for the Android stock SMS app with an on-device, hands-free voice assistant. Optional weather, navigation ETA, stock prices, and AI questions use explicitly permitted online services.

Android speech is restricted to the on-device recognizer and installed offline TTS voices, with no cloud fallback. SilentPulse cannot firewall a separate Google/system process or guarantee that it never makes unrelated network requests; that requires device-level controls.

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

Phase 2 — When Vosk hears the wake word, it releases the mic and hands off to the selected offline STT engine. Android recognition requires the **API 31+ on-device recognizer**, not merely `EXTRA_PREFER_OFFLINE`; unavailable offline recognition is reported rather than falling back to the generic/cloud-capable recognizer. Vosk and Whisper remain local alternatives.

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

Android TTS must have an installed voice for the requested language that does not require a network connection; a missing or network-only voice is rejected rather than used as a fallback. The bundled Kokoro/Sherpa backend is temporarily blocked because its native error diagnostics can expose input text even with debugging disabled. Its saved models/preferences remain intact, and it is not silently replaced by another engine.

### Cross-app voice broadcast protocol

Only approved companion apps can be voice targets. SilentPulse:

1. Discovers only **Grafium (`com.grafium.app`)** and **Microcore (`com.microcore.microcore`)**, requiring a signing identity matching SilentPulse
2. Strips the app name from the command and dispatches an `EXECUTE_COMMAND` broadcast
3. The target app replies with a package-scoped `TTS_REPLY` broadcast; SilentPulse requires a fresh, one-use request nonce, approved recipient identity, and pending request before speaking
4. **One command, one reply, then back to the wake word.** Companion follow-up hints never activate another listening turn or implicitly route the next utterance to that app

Example: *"Computer, Microcore, log weight 220 pounds"* — SilentPulse routes "log weight 220 pounds" to the Microcore health app, which logs the measurement and replies *"Logged weight at 220 pounds."*

Microcore requires voice-bridge protocol 2: `EXTRA_SESSION_ID` identifies each one-shot request, and a separate fresh `EXTRA_REPLY_NONCE` is echoed in the reply. Its internal protocol remains compatible, but SilentPulse does not continue companion conversations. Incomplete commands must be repeated as a new, complete wake-word command. The receiver requires the signature-level `com.silentpulse.messenger.permission.VOICE_COMMAND` permission. Older global-broadcast Microcore bridges are rejected before private commands are sent. Grafium's existing package-scoped protocol uses a fresh nonce in `EXTRA_SESSION_ID`; its signed manifest supplies local command-help text instead of accepting unauthenticated legacy schema replies.

Private app-directed commands are not forwarded to online AI when a companion is unavailable. Cancel/stop, timeout, speech failure, and service shutdown revoke pending reply capabilities. These controls authorize SilentPulse's integrations; they do not audit every behavior of the approved companion apps.

---

## Privacy Architecture

> **Core principle: private content must not be forwarded to online providers without the user's explicit request.**

### Network security

`network_security_config.xml` limits the app's framework-managed TLS trust:

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

This is not a device-wide egress firewall. It does not govern other applications, Android speech/font/backup services, or arbitrary native networking. Online AI questions and public-city weather requests are intentional network operations; the provider necessarily sees the request and connection metadata.

### Backup, fonts, and diagnostics

SilentPulse disables automatic cloud backup and device-to-device extraction of its app data. An update does not delete any existing remote backup or change Android's separate system SMS-backup settings.

The UI uses platform fonts and does not request downloadable Google fonts or emoji metadata. Debug builds retain content-free diagnostics; release app diagnostics are disabled. Messages, contact details, transcripts, authentication URLs, and request capabilities must not appear in diagnostic logs.

AI conversation cookies/session behavior is unchanged by these privacy fixes. A configurable conversation inactivity window is a separate, deferred design discussion.

### What is explicitly absent

- No Firebase of any kind (no Crashlytics, Analytics, FCM, Remote Config, Performance)
- No app-initiated Google font downloads or Play Services telemetry SDKs
- No crash or event reporting (Sentry, Datadog, Amplitude, Mixpanel, Segment…)
- No advertising SDKs
- No cloud TTS or cloud STT — all speech processing is on-device only
- No log shipping — debug diagnostics stay local, and release diagnostics are disabled
- No A/B testing or feature flag services
- No referral or attribution tracking

### Build variants

| Variant | Description |
|---|---|
| `noAnalytics` *(default)* | No Firebase, no analytics, no crash reporting. This is the only variant that should be distributed. |
| `withAnalytics` | Legacy flavor kept for historical reference — do not distribute. |

---

## Home-screen controls

The four-icon control widget requests a **2-column, 1-row** footprint for the notification reader, next notification, voice assistant, and stop-speaking controls. Tight horizontal padding keeps all four icons visible at **2x1**, while horizontal resizing also supports **3x1** and **4x1** for larger touch targets. Actual grid sizing depends on your launcher.

After updating, long-press an existing widget and use its side handles to shrink its width, or its top/bottom handles to reduce its height to one row. If your launcher keeps the old minimum size or does not show resize handles, remove and re-add the widget.

### World clocks

Add **SilentPulse World Clock** from your launcher's widget picker, search for a city or IANA time zone, choose white or black text for your wallpaper, and tap **Save clock**. Its background is fully transparent.

Each clock requests **2 columns by 1 row**, so two fit side by side on a four-column home screen. Add as many independent instances as your launcher allows, with a different city on each. Tap the time or city to change its city or text color; long-press to resize it. Actual grid dimensions depend on your launcher.

Timekeeping works entirely offline using Android's time-zone database and automatically follows daylight-saving changes and the system's 12/24-hour format. The launcher updates the time without a background service or periodic alarms.

A small, transparent weather icon sits **between the time and city**, with no temperature text in the compact layout. Sunny/clear-night, partly cloudy, cloudy, fog, rain, sleet, snow and storm icons are bundled vector drawings, not downloaded images.

Weather uses [Open-Meteo](https://open-meteo.com/) (no API key; [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)). Only the selected city and its geocoded coordinates are requested; no device location, messages or contacts are used. Cities are matched to the clock's time zone rather than silently using a different location. Predefined country/region shortcuts resolve to and display a named representative city, such as **Japan → Tokyo**. The picker prioritizes exact city matches and removes duplicate region shortcuts; real city names such as Seattle remain distinct. Conditions are cached per city and refreshed about hourly using WorkManager; Android can delay background work. Tap the weather icon to refresh manually. A question-mark cloud means weather is pending or unavailable, not a guessed forecast. UTC and unresolved city aliases still work as clocks but may have no weather. Removing the last clock cancels refresh work and clears the separate weather cache.

---

## Building

Requires Java 17 and NDK `28.2.13676358`.

The Gradle wrappers disable Realm's build-time analytics. Set `REALM_DISABLE_ANALYTICS=true` in the environment when building directly through an IDE or a system Gradle installation too.

```bash
# Debug APK — sideload via ADB for testing
./gradlew :presentation:assembleNoAnalyticsDebug

# Release APK — uses production keystore if secrets are set, otherwise debug key
./gradlew :presentation:assembleNoAnalyticsRelease
```

### Installing via ADB

Before sideloading, check the built APK's 16 KB compatibility:

```bash
python3 scripts/check-apk-alignment.py path/to/built.apk
```

This checks every packaged `arm64-v8a` and `x86_64` library's ELF load segments and, for uncompressed libraries, its ZIP alignment. CI runs the same check on debug and release APKs before publishing, so incompatible prebuilt dependencies cannot silently ship. Rebuilding our own native code alone does not fix a misaligned third-party library.

Realm `10.19.0` and Vosk `0.3.75` (with its transitive JNA `5.18.1` AAR) supply compatible native libraries. The Realm update from `10.18.0` retains database file format v23 and requires no app schema change.

```bash
adb install -r -t path/to/built.apk
```

Use the existing signing key for in-place updates. If Android reports a signature mismatch, stop rather than uninstalling or clearing app data.

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
