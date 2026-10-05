# Unified messenger and SilentPulse migration

Status: draft requirements and feasibility notes, not shipped functionality.
Updated: 2026-10-01.

This document captures the proposed new client. Tauri 2 with a Rust application
core is the preferred stack, subject to platform and performance validation.
This does not authorize a deployment or mean that a Matrix server has already
been provisioned. Existing SilentPulse functionality and data must remain intact
during migration.

## 1. Product direction and navigation

- One application for messaging and the existing SilentPulse utilities.
- Discord-inspired navigation, but the first level selects a **transport**, not
  a Discord server: SMS, Matrix, Signal, and Telegram.
- Desktop: provider tabs in a left-side rail, followed by the selected provider's
  account/conversation navigation and the conversation pane.
- Phone: provider tabs across the bottom, with conversations inside each tab.
- Matrix can contain multiple accounts, homeservers, Spaces, and rooms; these
  must not be confused with the top-level provider tabs.
- Each provider retains its own identity, login, keys, conversation state, unread
  counts, and delivery semantics. Switching tabs must not restart its connection.
- Show transport and actual encryption status in the conversation and composer.
  Never silently fall back from an encrypted transport to SMS or a cloud chat.
- Unconfigured providers must not start SDKs, connections, or background jobs.
  Mom's onboarding should expose only the configured family experience.
- Family location belongs with the family's Matrix experience. Assistant,
  stocks, clocks, and settings remain application features; their exact placement
  is a subsequent UI decision.

## 2. Transport scope and constraints

| Transport | Proposed implementation | Important boundary |
|---|---|---|
| Matrix | Maintained Matrix SDK, with Rust SDK a candidate | Standard Matrix compatibility; encrypted private family rooms and media. Other rooms may not be encrypted. |
| Android SMS/MMS | Retain native Android adapters and default-SMS role | Carrier messages are not E2EE. Windows/Linux have no phone SMS provider; a phone relay would be a separate, explicitly approved feature. |
| Signal | Optional feasibility prototype before a product commitment | A custom client remains unofficial. Normal registration/linking does not establish Signal approval or a stable third-party service contract. |
| Telegram | Optional official Telegram API/TDLib integration | Supported third-party-client route, but ordinary cloud chats, groups, and channels do not meet the server-blind E2EE requirement. |

Use direct, device-local protocol engines rather than a server bridge that
decrypts messages. Matrix, Signal, Telegram, and SMS conversations are not
automatically bridged, copied, or merged across networks.

### Matrix

- Support standard login as well as simplified family enrollment.
- Registration is controlled; family rooms are encrypted and invite-only.
- Decide separately whether the family homeserver federates. Federation allows
  outside rooms, but does not make private rooms public. Another participating
  homeserver receives room metadata and encrypted events.
- An isolated family account and an external federated account may coexist in
  the client. No data is copied between them automatically.
- Enrollment: single-use invitation, client-generated keys, device-bound
  verification, approval, then access to rooms/keys. A link alone is not proof
  of the recipient's identity.
- Preserve normal OIDC state/PKCE checks. Do not treat an unsolicited emailed
  callback as a completed client-initiated login.
- Room membership is account-level. Requiring approval of every later device
  needs an explicit authentication and key-sharing policy, not just room invites.
- Include verification, device revocation, encrypted recovery, and replacement
  phone onboarding. The server must not receive recovery/decryption secrets.
- Calls and screen sharing need an explicit MatrixRTC/media implementation and
  self-hosted supporting services; an SDK does not supply the complete call UI.

### Signal

- Investigate primary-client and linked-device approaches without modifying a
  real account during research. Re-registration can displace its primary client.
- Verify groups, media, device lifecycle, recovery, calls, and Google-free
  operation independently; messaging success is not feature parity.
- Review service terms, branding, and GPL/AGPL obligations before distribution.
- Treat compatibility maintenance and upstream service changes as a recurring
  cost. Do not make family messaging depend on this optional integration.

### Telegram

- Use TDLib/Telegram's client API, not the Bot API, for a user's normal account.
- Register our own application `api_id`/`api_hash`; users authenticate their own
  accounts through supported flows. Do not commit credentials or log auth data.
- Explain that ordinary cloud-chat content is stored by Telegram and is not
  E2EE. Secret Chats are E2EE, one-to-one, and device-specific; they do not turn
  Telegram groups into encrypted family rooms or provide ordinary cloud sync.
- Family-private location and Matrix messages must never be copied to Telegram.
  Enabling Telegram requires an explicit provider/privacy decision; it is not a
  replacement for the private family service.
- Comply with client-feature, branding, and sponsored-message requirements.
  Channel access requires support for official sponsored messages. Do not
  promise ad-free channel access by stripping them or add advertising SDKs.
- Keep Telegram-derived content out of AI scraping, summarization, indexing,
  model training, and other AI processing by default. Telegram's content/AI
  terms are a release gate; the app user's consent alone does not satisfy a
  requirement for consent from all relevant users.
- Audit TDLib networking, local storage, logging, dependencies, and background
  delivery against the zero-Google/zero-telemetry policy before enabling it.
  Official client API support does not by itself establish policy compliance.

## 3. Family location using Matrix

**A separate custom location server is not required for the initial design.**
Matrix can supply accounts, room membership, encrypted message transport, sync,
and delivery to the family's devices. Phones still collect location and clients
render maps; Matrix is not a GPS provider, map tile service, or geocoder.

### Existing SilentPulse foundation

`LocationSharingActivity.kt` currently displays a local OpenStreetMap view and
uses Android `LocationManager`. Its peer list is explicitly a future slice.
`LocationSharingSettingsActivity.kt` stores a sharing toggle and offers battery
and OEM-autostart setup; this is not an implemented peer-sharing transport.

The foreground map currently requests location at a 5-second/5-meter threshold
from available non-passive providers. Do not carry that behavior into an
always-on background tracker. Build a shared, adaptive location controller.

### Proposed encrypted delivery

- Begin with explicit one-time location sharing and time-limited live sessions.
  Any ongoing family-presence mode requires a separate, clear opt-in.
- Share only to a selected, encrypted, invite-only audience. Use rooms with
  matching membership rather than a client-side recipient filter inside a
  larger family room: everyone with the room keys can decrypt its events.
- Encrypt coordinates, accuracy, measurement time, session identifier, sequence,
  and expiry using the Matrix SDK's normal room encryption. Verify recipient
  devices under the family policy before sharing keys.
- Do not put coordinates or sensitive descriptions in ordinary room state,
  presence, typing events, push payloads, logs, or unencrypted map thumbnails.
- Stable Matrix `m.room.message` with `msgtype: m.location` supports a static
  location. Use its encrypted form in family rooms.
- Live-location formats MSC3489 and MSC3672 are still open proposals as checked
  on 2026-09-30. Reuse compatible implementations where suitable, but do not
  promise universal live-map interoperability or ephemeral delivery.
- A versioned application event inside an encrypted room is a fallback for our
  clients and needs no custom homeserver protocol. Other Matrix clients may
  not render it. Select the format after SDK/interoperability prototyping.
- Reuse the account's Matrix sync connection. Updates feed a map/latest-location
  view instead of producing a visible chat bubble and notification per point.
  Validate silent-update push rules with the chosen encrypted event format;
  filtering only after a push wakes the phone does not save that wakeup.

### Consent, freshness, and retention

- Show who can see a share, its precision, its duration, and a clear Stop action.
  OS location permission is not consent to send location to another person.
- Support approximate location as well as precise location, and show accuracy
  and age. "Last seen 20 minutes ago" must not appear as a current live position.
- Stop acquisition and future publication locally on Stop, expiry, permission
  revocation, logout, or a known audience change; cancel queued session updates.
  Reconfirm the audience when membership changes. Reconnect must not replay an
  expired route backlog or automatically resume an expired share.
- Bound the offline queue and coalesce superseded location updates. Establish
  ordering, freshness, clock-skew handling, and retry/idempotency rules.
- On removal of a recipient, stop sharing and use the SDK's membership-aware
  encryption-session rotation before resuming to the new approved audience.
- Configure restricted history visibility and key sharing for new devices and
  members. A new member must not automatically receive earlier location keys.
- Matrix timeline events are normally persistent. A live-session timeout means
  "stop publishing/displaying as live," not "cryptographically erase history."
  Retention, redaction, server backups, client caches, and already-decrypted
  recipient copies require separate treatment. Do not promise recall.
- Map tile and geocoding requests can reveal the viewed area to their provider.
  Prefer offline/self-hosted maps where feasible, cache within provider terms,
  and make external map access explicit. Matrix E2EE does not conceal it.
- On Android, use native location APIs without a Play Services dependency.
  Platform network-location providers may themselves use external services;
  strict offline operation needs a verified provider/device configuration.
- Server-side geofencing cannot inspect E2EE coordinates. If later required,
  compute arrival/departure events on an authorized device.

## 4. Assistant and reusable web integration

- Preserve on-device wake word, STT, TTS, drive-mode reading/reply, and the
  explicitly authorized companion-app integrations.
- Preserve optional, explicitly selected online AI/WebView functionality as
  a separate provider capability, not an always-running browser per transport.
- Extract shared web-content/session handling behind reviewed provider adapters:
  approved origins, redirect/subresource controls, isolated sessions, bounded
  requests, cancellation, timeouts, and explicit authentication/error states.
- Prefer supported APIs where available. Do not bypass authentication, access
  controls, CAPTCHAs, paywalls, or service terms to scrape content.
- Keep messages, contacts, locations, health commands, and private transcripts
  out of external AI requests. Public online questions are distinct from local
  command execution. The Telegram-specific content restriction also applies.
- Treat extracted page content as untrusted data, not authority to send a
  message, reveal credentials, alter settings, or invoke an assistant command.
- No microphone, WebView network activity, polling timer, or wake lock should
  remain because a screen was visited. Listening must be explicitly enabled;
  stop/timeout must tear down active work without deleting saved preferences.
- Validate the desktop equivalents of native Android capabilities rather than
  assuming Android notification listeners and background services are portable.

## 5. Performance and battery are release requirements

This is a first-class architecture constraint, not post-release polish.
Installing several transports must not multiply idle work unnecessarily.

### Required runtime behavior

| Area | Requirement |
|---|---|
| Provider lifecycle | Zero provider-attributable jobs/connections for a disabled, unconfigured provider. Hide/show a tab without duplicate sync loops or lost sessions. |
| Notifications | Google-free delivery; no FCM fallback. Evaluate self-hosted UnifiedPush for Matrix. Signal/Telegram need their own validated delivery paths; one distributor does not automatically cover every protocol. |
| Background networking | Prefer supported push/long-lived sync over frequent polling. Bound retries, use backoff/jitter, and avoid reconnect storms in poor connectivity. |
| Location | A shared native controller; adaptive precision, movement thresholds, bounded acquisition, deduplication, and lower stationary/low-battery activity. No continuous high-accuracy GPS by default. |
| Live-share UX | Offer deliberate battery/freshness tradeoffs. Do not secretly turn off an active share to save power; report degradation, pauses, and stale data. |
| Assistant | Separate idle, wake-word, recognition, and speech states. Stop unused engines; benchmark opt-in continuous listening separately from ordinary idle messaging. |
| WebView/AI | Create lazily, bound concurrency, and stop active work when no request requires it. No hidden always-on scraper or browser per tab. |
| Media/maps/widgets | Bound caches; paginate histories; decode thumbnails off the UI thread; avoid background video/map rendering; coalesce stock/weather refreshes. |
| UI | Virtualized conversation lists, asynchronous database/crypto/media work, and bounded retained screens. Large histories must not block input. |
| OS integration | Native background components when required. Explain battery/autostart settings only for affected enabled features; no blanket "disable all optimization" onboarding. |

Doze, force-stop, OEM process killing, and unavailable networks prevent absolute
delivery guarantees. UI must expose degraded delivery rather than report a
healthy connection when the app cannot maintain it. Privacy is not relaxed to
improve latency.

### Measurement and acceptance plan

Establish numerical budgets **before committing to the production architecture
and before feature sign-off**. They are currently unmeasured/open, not claimed
performance figures.
Measure the existing app for overlapping workflows and prototype new transports
on representative hardware; do not invent a universal battery percentage.

| Scenario | Required evidence |
|---|---|
| 8-hour screen-off idle | Compare baseline, Matrix only, each additional provider, and all enabled; record energy, CPU time, wakeups, wake-lock duration, network bytes, and delivery latency. |
| Location sharing | Separate stationary, walking/driving, map-visible, screen-off, approximate, and precise modes; record energy, GPS-active time, update count, accuracy, and freshness. |
| Assistant | Compare disabled, wake-word listening, active recognition, TTS, and WebView requests; verify cancellation releases resources. |
| UI and storage | Cold/warm start p50/p95, tab-switch latency, frame deadlines/jank, peak and steady memory, and large-history pagination/search. |
| Calls | Audio/video/screen-share energy, temperature, reconnect behavior, and message delivery while a call is active. |
| Adverse lifecycle | No network, poor signal, Doze, battery saver, permission revocation, logout, stop/expiry, process death, and restart; check for runaway retries and leaked jobs. |
| China connectivity | Actual home Wi-Fi and mobile networks, screen-off notifications, audio/video, reconnects, and TURN fallback; no guaranteed reachability claim. |

Use controlled, repeatable comparisons: same device, build mode, signal, screen
state, workload, and battery/thermal conditions. Report multiple runs and
measurement uncertainty. Keep diagnostics local and content-free; no production
telemetry or location/message traces.

For each supported reference device, record an approved budget for idle energy,
active-share energy/freshness, notification latency, startup, frame time, memory,
and background traffic. Performance sign-off remains blocked until those budgets
are agreed and met. UI smoothness must not be purchased by excessive background
CPU, and battery targets must not hide missed messages or stale locations.

## 6. Migration and platform guardrails

- Android, Linux, and Windows are the primary native targets. Tauri 2 with Rust
  is preferred; the frontend framework and exact plugin boundaries remain open.
- Preserve Android-native SMS/MMS, widgets, microphone/location services,
  notification access, inline reply, permissions, and navigation launch rules.
  A WebView alone is not their replacement.
- Inventory all current features before migration sign-off, including message
  history/attachments, contacts, themes/OLED/fonts, notifications, widgets,
  stocks/news, world clocks/weather, assistant/drive mode, companion commands,
  location settings, SMS backup, and portable settings backup.
- Migrate incrementally with verified backups, reversible imports, and
  explicit feature-parity checks. No uninstall, database wipe, or destructive
  replacement of the existing app to simplify migration.
- Preserve signing/update continuity where applicable. New package/signing
  identities require an explicit data-transfer plan.
- Android distribution needs a signed APK and maintainable update channel.
  Test Chinese ROM background restrictions; do not assume every Huawei device
  runs Android APKs. HarmonyOS-only support needs a separate decision. iPhone
  access must respect the no-paid-membership constraint below.
- No Google services, telemetry, remote diagnostics, or cloud speech fallback.
  Provider integrations are opt-in, separately reviewed network capabilities.
- Enforce egress policy at actual native SDK and WebView transport boundaries;
  Android `network_security_config.xml` is not a universal native firewall.
- Resolve licensing compatibility for reused SilentPulse GPL code and any
  AGPL components before choosing the new application's distribution license.

### Preferred Tauri/Rust architecture

- Share application logic in Rust and the interface through Tauri's web frontend.
  Tauri uses operating-system WebViews rather than bundling its own browser.
- Keep protocol engines and native credentials outside untrusted web content.
  Use narrowly scoped IPC capabilities; scraped/remote AI pages must not inherit
  the trusted application's filesystem, messaging, or command permissions.
- Reuse maintained implementations rather than rewriting every dependency in
  Rust. Matrix Rust SDK is a candidate; TDLib remains its own native library.
- Retain Kotlin/Java Android services through native adapters/plugins for SMS,
  widgets, location, audio, notification access, and background execution.
  A Rust core and shared UI do not remove platform-specific lifecycle work.
- Native iOS is technically a Tauri target, with Swift plugins where needed,
  but building/signing requires Apple's tooling and native distribution remains
  subject to Apple's rules. Tauri does not bypass them.
- Rust and smaller distribution size do not establish lower energy use or a
  faster UI than existing native Android code. Benchmark the complete app,
  including WebView/IPC overhead, network connections, GPS, and speech engines.

### Shared application and website feasibility

Proposed direction: one source repository, shared responsive UI and application
rules, packaged through Tauri or served as an HTTPS website/PWA. This means shared
source with platform adapters, not identical binaries or identical capabilities.

- Keep portable Rust logic independent of Tauri, native filesystem APIs, and
  platform runtimes so it can compile natively or to WebAssembly. The frontend
  calls an application interface backed by native IPC or a browser/WASM adapter.
- Share navigation, conversation presentation, formatting, validation, and
  location-session rules. Isolate storage, authentication callbacks, networking,
  notifications, media/calls, and device capabilities behind tested interfaces.
- The Matrix Rust SDK has JavaScript-target and IndexedDB configurations, making
  a native/WASM core worth prototyping. This is not evidence that every SDK/UI,
  call, persistence, or background feature already works unchanged in browsers.
- A verified browser option is `matrix-js-sdk` with `matrix-sdk-crypto-wasm`,
  using the maintained Rust encryption implementation. The crypto WASM binding
  is not the entire Matrix client. Using different browser/native SDK adapters
  is a fallback that shares the UI and rules but not all protocol orchestration.
- Use persistent, recoverable crypto stores and a single coordinated owner per
  device/store. Do not run competing sync/crypto writers in multiple browser
  tabs or in both a native background service and its foreground WebView.
- Put expensive work off the UI thread where the SDK/runtime supports it.
  Paginate, bound media decoding, lazy-load optional engines, and measure WASM
  download/initialization, IPC overhead, and memory as well as steady-state speed.
- A worker is not an always-running mobile background service. Browser
  suspension and OS permissions still limit push, calls, location, and audio.
- AI WebView scraping is not portable unchanged: a website cannot inspect
  arbitrary third-party page DOMs/cookies due to same-origin restrictions.
  Use an authorized browser-compatible API or mark the feature unavailable;
  do not move private sessions or content to a server-side scraping proxy.
- Browser E2EE protects data in transit and at the homeserver, but trusts the
  code delivered by the web origin. A compromised web deployment can replace
  that code and steal data during use. Separate app hosting from the homeserver,
  minimize dependencies, use a strict CSP, and protect builds/deployments;
  these controls do not make a compromised origin harmless. Packaged apps
  should bundle their UI and authenticate updates rather than load the live
  website as privileged application code.
- Before committing to an SDK arrangement, prove login, encrypted messages and
  media, device verification, key recovery, reload/restart, multi-tab/store
  ownership, and background resume in a packaged app and Safari/PWA. Apply the
  same protocol behavior tests and the platform-specific performance budgets.

### iPhone access without a paid developer membership

Product constraint: do not require the owner to pay an Apple Developer Program
membership to distribute this free family application.

| Route | Feasibility and limitation |
|---|---|
| Free Xcode Personal Team signing | Suitable for development, not a low-maintenance family rollout. Apple documents 7-day provisioning, up to 3 registered devices, and up to 3 installed apps per device. Reprovisioning/reinstallation is required. |
| App Store or TestFlight for our native app | Normally requires paid developer membership, even for a free app. TestFlight builds also expire after 90 days. Not the baseline under the current constraint. |
| Regional alternative distribution | Apple's current user documentation lists eligible users in the EU, Japan, and Brazil, with different capabilities and account/physical-location requirements. Not unrestricted worldwide IPA installation. EU direct website distribution still requires developer-program participation and authorization. |
| Our own home-screen web app/PWA | No Apple developer membership is needed for distribution or Web Push. Requires a real browser-compatible implementation and has important native-feature limits. |
| Existing compatible Matrix iOS client | No publishing membership needed from us. Can access the same server and encrypted rooms; custom onboarding, live-map events, and feature parity need compatibility checks. |

Apple's standard developer membership is 99 USD per year (or local equivalent).
Fee waivers are for qualifying organizations, not automatically for an
individual publishing a free/open-source family app. Do not base production
distribution on temporary development signing or promise permanent sideloading.

A PWA is a candidate, not a chosen replacement for native iOS:

- It can reuse UI components, but Tauri's native Rust commands, SDK bindings,
  services, and secure-storage plugins are not available in a browser. Design
  platform interfaces and validate a maintained browser/WASM messaging and
  encryption implementation. Keep decryption on the client, not a web bridge.
- Prototype family Matrix chat/media first. Do not promise that native Signal,
  Telegram, assistant, or operating-system integrations work unchanged on web.
- On iOS/iPadOS 16.4 and later, a home-screen web app can request Web Push
  permission following user interaction, without developer-program membership.
  Delivery still uses Apple Push Notification service. Apple infrastructure is
  a separate privacy decision; never put plaintext messages or locations in
  push payloads, and document the remaining delivery metadata.
- Do not promise background live-location publishing, continuous wake-word
  listening, access to phone SMS, or native-equivalent incoming-call behavior.
  A visible web app can request location; background suspension changes the
  capability. Verify calls and notifications on real family devices/networks.
- Protect the web origin and update path, isolate external content, and provide
  encrypted key recovery. Browser data deletion/eviction must not silently
  destroy the only copy of a user's decryption keys.
- Keep the standard Matrix account usable in an existing iOS client if the web
  experience cannot meet the family's reliability requirements. Native iOS can
  be reconsidered only if distribution constraints change.

## 7. Open decisions and implementation gates

- Validate the preferred Tauri/Rust stack, choose its frontend framework, and
  establish shared/native component boundaries using prototypes and benchmarks.
- Prove native/WASM Matrix SDK coverage versus the maintained JavaScript/Rust
  crypto fallback; shared source must not obscure platform capability gaps.
- iPhone route: existing Matrix client versus a limited PWA; Apple push/privacy
  decision and feature/reliability limits. No paid publishing is assumed.
- Family-server federation policy and approved-device/key-recovery policy.
- Exact live-location format, update profiles, retention, and map provider.
- Numeric performance/battery budgets on the supported reference devices.
- Signal feasibility and service-policy review; Telegram privacy, client terms,
  sponsored messages, and AI-content restrictions.
- Server hosting, China-network reachability, and Google-free notification paths.
- Complete feature inventory and data-preserving migration acceptance criteria.

## Sources

- [Android SMS provider and default-SMS requirements](https://developer.android.com/reference/android/provider/Telephony)
- [Matrix static location message schema](https://github.com/matrix-org/matrix-spec/blob/main/data/event-schemas/schema/m.room.message%24m.location.yaml)
- [MSC3489: persistent live-location streams (open proposal)](https://github.com/matrix-org/matrix-spec-proposals/pull/3489)
- [MSC3672: ephemeral live-location streams (open proposal)](https://github.com/matrix-org/matrix-spec-proposals/pull/3672)
- [Signal library support and licensing](https://github.com/signalapp/libsignal)
- [Signal service terms](https://signal.org/legal/)
- [Telegram client API and TDLib](https://core.telegram.org/api)
- [TDLib platform support](https://core.telegram.org/tdlib)
- [Telegram application registration](https://core.telegram.org/api/obtaining_api_id)
- [Telegram API terms, including sponsored messages](https://core.telegram.org/api/terms)
- [Telegram cloud and Secret Chat privacy](https://telegram.org/privacy#3-3-your-messages)
- [Telegram content licensing and AI restrictions](https://telegram.org/tos/content-licensing)
- [Tauri process model and operating-system WebViews](https://v2.tauri.app/concept/process-model/)
- [Tauri static frontend and WASM support](https://v2.tauri.app/start/frontend/)
- [Tauri native mobile plugins](https://v2.tauri.app/develop/plugins/develop-mobile/)
- [Tauri Apple distribution and build requirements](https://v2.tauri.app/distribute/app-store/)
- [Apple Personal Team limits](https://developer.apple.com/help/account/basics/about-your-developer-account)
- [Apple developer-program fee waivers](https://developer.apple.com/help/account/membership/fee-waivers/)
- [Apple regional alternative-distribution eligibility](https://support.apple.com/en-us/118110)
- [Apple EU direct website distribution](https://developer.apple.com/support/web-distribution-eu/)
- [TestFlight build lifetime](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview/)
- [WebKit: iOS home-screen Web Push without developer membership](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/)
- [Matrix Rust SDK target and storage features](https://github.com/matrix-org/matrix-rust-sdk/blob/main/crates/matrix-sdk/Cargo.toml)
- [Matrix JavaScript SDK and Rust encryption initialization](https://github.com/matrix-org/matrix-js-sdk#end-to-end-encryption-support)
- [Matrix Rust crypto WebAssembly bindings](https://github.com/matrix-org/matrix-rust-sdk-crypto-wasm)
