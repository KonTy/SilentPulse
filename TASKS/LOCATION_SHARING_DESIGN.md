# SilentPulse — Family Sharing Hub (Design Doc, LIVING)

> Living design doc. Update as decisions are made. Date stamp each section when
> it is locked. Open questions stay at the bottom.
>
> **Status: DRAFT — client-first design phase. Server contract captured;
> server implementation deferred.**
>
> **Scope** (grew during design):
> - Family location sharing (primary)
> - Per-person + shared calendars / contacts / todos via Radicale (CalDAV/CardDAV)
> - Single onboarding ceremony covering Tailscale + SilentPulse + DAVx5
> - Watch (Bolide / AsteroidOS) participates via phone-as-gateway, no internet of its own

---

## North Star

A privacy-first family hub, self-hosted, no Google, no third-party telemetry.
SilentPulse on Android is the only app the family is required to install
(plus Tailscale and DAVx5 silently configured by SilentPulse).
The server runs in our own infrastructure — Azure ARM today, Raspberry Pi when
the budget runs out, identical container images on both.

We deliberately ship the **client first** and let the wire format dictate the
server, not the other way around.

---

## Locked decisions

### Platform & ecosystem
- **Client**: SilentPulse on Android (existing app, has autostart, has
  network_security_config domain whitelist, has BootReceiver +
  AssistantBootReceiver already wired).
- **No iOS** for v1 (Apple Developer fee + Mac for Xcode; revisit later).
- **No standalone OwnTracks app**. SilentPulse is the publisher AND subscriber.

### Map UX — layered

The "Google-Maps-style live shared map" experience requires a **renderer that
knows about our server** (a one-shot `geo:` URI cannot poll). We split the UX
into three layers so the everyday glance is cheap and the live map is opt-in:

**Layer 1 — Family List (primary, always-on, cheap)**
- Plain list in SilentPulse: name, last-seen, distance, battery.
- Polled every N min from the server. No map renderer involved.
- Covers ~90% of glances. Lowest battery cost.

**Layer 2 — Static one-shot launch (per-peer "View on map")**
- Tier 1: standard `geo:` URI — any installed map app handles it
  (OsmAnd, Organic Maps, Magic Earth, etc.).
- Tier 2 (fallback): `https://www.openstreetmap.org/?mlat=...&mlon=...` in
  the system browser if no `geo:` handler is installed.
- Tier 3 (optional, later): OsmAnd-specific `osmand.api://` URI when user
  picks "Prefer OsmAnd" in settings — bypasses Google Maps even if installed.
- One-shot, static. Reload to refresh. No live updates.

**Layer 3 — Live Multi-Peer Map (opt-in, deferred renderer choice)**

Renderer is a **swappable plug-in** behind the same `PeerLocationStore`. Pick
when we get there. Options:

| Option | Live? | Labels? | Effort | Where work goes |
|---|---|---|---|---|
| **B. OsmAnd AIDL live markers** | ✅ | ✅ | ~1-2 days | client only |
| **C. osmdroid embedded view** | ✅ | ✅ | ~3 days | client only |
| **D. WebView → server-rendered Leaflet** | ✅ | ✅ | ~1d server + 1h client | server (write once, runs everywhere) |

Tradeoffs:
- **B** is cheapest and gives best per-Android UX (offline tiles, nav, search
  for free), but only works inside OsmAnd; user has to swipe to OsmAnd.
- **C** is best self-contained Android UX. osmdroid Apache-2, ~500 KB.
  Tile fetch needs a domain in `network_security_config.xml`.
- **D** is highest leverage: same web map renders on Android, Linux desktop,
  laptop, anyone's browser. Write the live map UI **once**. But server work.

Decision rule: **the wire format is the same for all three**, so we ship Layer
1+2 first, then prototype B in a weekend (lowest cost) and only escalate to
C or D if B isn't enough.

We do **NOT** embed a map widget for Layers 1-2. Layer 3 is opt-in and the
embedded path (C) is the renderer of last resort.

### Wire format
- OwnTracks-compatible JSON. Keeps the door open for OwnTracks
  Android/Recorder interop and means the eventual server is a known shape.
  ```json
  {
    "_type": "location",
    "lat":  47.5,
    "lon":  19.05,
    "tst":  1714000000,
    "acc":  12,
    "batt": 78,
    "vel":  0,
    "alt":  120,
    "tid":  "ab"
  }
  ```
- All times Unix epoch seconds. Coordinates WGS84.
- We may add namespaced fields later (`sp_*`) but never break the OwnTracks
  shape.

### Privacy & networking baseline
- App must not connect to Google. Enforced by
  `network_security_config.xml` (TLS handshake fails to non-whitelisted domains).
- Server endpoint will be a single user-configurable HTTPS URL on the user's
  own infra (Tailscale or public). That hostname is added to the whitelist
  at config time.
- All "Drive Mode / Voice Processing Rules" from copilot-instructions.md
  continue to apply.

### Server hosting & portability
- **v1 host**: Azure ARM VM (B2pls v2 / D2pls v6, ~$25-35/mo). ARM-from-day-one
  catches arch-specific bugs before the Pi cutover.
- **v2 host**: Raspberry Pi 4/5 in your home, when Azure budget runs out.
  Same Docker Compose, same multi-arch images. Migration is `rsync` of the
  data volume + reassign the Tailscale MagicDNS hostname.
- **Ingress**: **tailnet-only** via Tailscale. **No public IP, ever.** Eliminates
  whole classes of attack surface and requires no domain name purchase.
- **Stack**: Caddy (reverse proxy + auto-HTTPS via Tailscale cert) → Radicale
  (CalDAV/CardDAV/VTODO) + location-api (our service) + later owntracks-
  recorder. All in Docker Compose, multi-arch (`linux/amd64,linux/arm64`),
  state in named volumes, no cloud-vendor lock-in.
- **DNS**: MagicDNS hostname `family-server` resolves identically from any
  tailnet device. Survives node migration.
- **Cost discipline**: no static public IP, no Application Insights, no Azure
  Storage; clients fetch tiles directly from `tile.openstreetmap.org` (do not
  proxy through our server — egress would dominate the budget).

### Onboarding ceremony — "the QR scan that does everything"

Mom is non-technical. The single ceremony she goes through:

1. Install SilentPulse (sideload APK or F-Droid).
2. Install Tailscale Android (Play / F-Droid). **No Tailscale account** —
   SilentPulse hands it a pre-authorized auth-key from the QR.
3. Install DAVx5 (F-Droid). SilentPulse hands it a CalDAV URL + credentials
   via deep-link (`davx5://`).
4. Scan **one QR** that you generated for her.
5. Accept VPN permission dialog (one-time).
6. Done.

**QR payload** (single deep link, `silentpulse://join?...`):
```
  server   = https://family-server                  (MagicDNS, tailnet-only)
  ts_key   = tskey-auth-xxxxxxxxxxxxxxx              (Tailscale pre-auth, tag:family)
  user     = mom                                     (Radicale + location identity)
  password = <generated random app password>         (htpasswd entry on server)
  token    = <generated bearer token>                (location API auth)
  circle   = fam-7a3b                                (location circle membership)
  expires  = 2026-05-01T00:00:00Z                    (one-shot, 7-day TTL)
```

Server-side, the admin (you) created:
- htpasswd entry `mom:<hash>`
- Radicale collections `mom/personal`, `mom/contacts`
- Tailscale auth-key tagged `tag:family` (ACL: only reach `family-server`)
- Bearer token row in location DB linked to `circle = fam-7a3b`

**Tokens**: one-shot invite tokens expire after 7 days or first use. Permanent
device tokens never expire but are revocable (you delete the row → mom's
phone goes silent until re-onboarded). Tokens are device-scoped not
user-scoped: phone and tablet get separate tokens.

### Calendar / Contacts / Todos via Radicale

Radicale's per-user + shared collection model is exactly what we need:
```
collections/
├── you/
│   ├── personal/        ← your private calendar (RrWw: you only)
│   ├── work/
│   └── contacts/
├── mom/
│   ├── personal/        ← mom's private (RrWw: mom only)
│   └── appointments/
└── shared/
    ├── family/          ← family calendar (RrWw: you, mom, kid)
    └── shopping/        ← shared todo list (RrWw: everyone)
```
ACL via Radicale `[rights]` config. Per-pair shares ("mom can read your
`you/personal`") = one rule giving mom `Rr` on `you/personal`. No data
duplication; CalDAV does the rest.

**On-device integration**: DAVx5 syncs Radicale → Android Calendar Provider.
After that, the Android system calendar app, SilentPulse's existing assistant,
any widget, and our Bolide companion all read from the same Calendar Provider.
The watch never knows Radicale exists.

### Network topology

```
                    ┌─ tailnet ──────────────────────────────┐
you (laptop) ───────┤                                        │
  Tailscale         │                                        │
                    ├──→ family-server (Azure ARM → Pi)      │
mom's phone ────────┤        ├─ Caddy + Tailscale cert        │
  Tailscale Android │        ├─ Radicale (CalDAV/CardDAV)     │
  + SilentPulse     │        ├─ location-api                  │
  + DAVx5           │        └─ (later) owntracks-recorder    │
                    │                                        │
your phone   ───────┤                                        │
  (same)            │                                        │
                    └────────────────────────────────────────┘
                              ↑
                              │ BLE companion app
                              │   (calendar push, peer-distance,
                              │    workout GPX upload, notifications)
                              │
                          Bolide watch
                          (no internet, no Tailscale, no Radicale)
```

**Watch's role is firmly behind the phone.** Phone is the single source of
truth that talks to the server; watch just displays whatever the phone
pushes over BLE. This pattern matches AsteroidOS-Sync's existing calendar/
notification flow — we extend it with peer-location summary ("mom 0.4 mi NE")
and workout GPX upload.

---

## Open questions (decide in this order)

### 1. Topology — who publishes, who subscribes?
- [ ] Symmetric (every device publishes + subscribes to all), or
- [ ] Asymmetric (some "trackers", some "viewers"), or
- [ ] Per-edge (mom→me yes, me→mom yes, cousin→me one-way)

This decides the data model. Probably **per-edge** for flexibility, but a
symmetric default UX.

### 2. Per-recipient privacy posture
- [ ] Server-side ACL (server enforces "X can read Y") — simple, server is trusted
- [ ] Client-side E2E (server is dumb relay) — stronger, more UX
- [ ] Time-bounded sharing ("share until I arrive home")
- [ ] Precision tiers (exact / city only / online-status only)

For a self-hosted Pi over Tailscale, server-side ACL is defensible. E2E is a
later upgrade that doesn't have to break the JSON shape.

### 3. UI surface in SilentPulse
- [ ] Where does "Family map" live in the existing nav?
- [ ] List view first (name + last-seen + distance), `geo:` launch on tap?
- [ ] Per-contact share toggle on the existing contact screen?
- [ ] Notifications? ("mom arrived home", "kid left school")
- [ ] First-run setup screen (server URL + token + battery-opt prompt + OEM
      autostart deep link)

### 4. Publish trigger policy
- [ ] Time-based (every N min, simplest, worst battery)
- [ ] Distance-based (`LocationManager.requestLocationUpdates` minDistance —
      pure AOSP, no Google FusedLocationProvider)
- [ ] Activity-based (significant motion sensor, no Google Activity Recognition)
- [ ] Geofence-based (home / work / school via AOSP `Geofence` API or
      manual radius checks)
- [ ] Manual ("share now", "I'm driving home")
- [ ] Adaptive: passive listener when stationary; active poll when moving;
      idle cutoff overnight

### 5. Battery & service architecture
- [ ] Foreground service with `type=location` + persistent notification when
      actively sharing
- [ ] WorkManager periodic (15-min minimum) for ambient
- [ ] AOSP `Geofence` for entry/exit events
- [ ] Quiet hours (configurable)
- [ ] Target: <X% battery/day (number to be decided)

### 6. Offline / store-and-forward
- [ ] Queue locations in Realm when offline
- [ ] Flush on reconnect, oldest-first
- [ ] Cap queue size + age (drop oldest)
- [ ] Survive reboot (Realm-backed → yes)

### 7. End-to-end encryption
- [ ] None for v1 (server is on tailnet, trusted)
- [ ] OwnTracks `_type:"encrypted"` libsodium box later (additive, no schema break)
- [ ] Key onboarding flow (QR code at pairing time)

### 8. Identity / pairing
- [ ] Server URL + bearer token format
- [ ] QR code from your phone to a new family member?
- [ ] Token rotation policy
- [ ] Revocation when a phone is lost

### 9. Watch role (Bolide)
- [ ] No role for v1 (recommended — keep client surface small)
- [ ] Or: watch shows "compass arrow + distance to nearest family member"
      using phone's location relayed over BLE
- [ ] Or (later): watch publishes its own GPS during workouts

---

## Component sketch (client side, post-decisions)

```
SilentPulse
├── data
│   └── location
│       ├── LocationPublishRule       — when to sample
│       ├── LocationSampler           — AOSP LocationManager wrapper
│       ├── LocationQueue (Realm)     — offline store-and-forward
│       ├── LocationPublisher         — POSTs JSON to server
│       └── LocationSubscriber        — pulls peer locations from server
│
├── domain
│   └── location
│       ├── ShareRule(contactId, mode, until, precision)
│       ├── PeerLocation(contactId, lat, lon, tst, accuracy, battery, ...)
│       └── Geofence(name, lat, lon, radius)
│
├── presentation
│   └── feature
│       └── familymap
│           ├── FamilyMapScreen       — list view
│           ├── PeerDetailScreen      — last seen, history, "View on map"
│           ├── ShareSettingsScreen   — per-contact share rules
│           └── MapLauncher           — geo: → osmand → browser fallback
│
└── service
    ├── LocationPublishService        — foreground when active
    └── LocationPollWorker            — WorkManager periodic subscribe
```

---

## Server contract (capture as we go — server impl deferred)

This section grows as the client design firms up. The server only has to satisfy
this contract.

### Endpoints (tentative)
```
POST  /pub                      Publish own location (OwnTracks JSON, bearer auth)
GET   /sub?since=<tst>          Subscribe to peer updates since timestamp
GET   /peers                    List visible peers + their last fix
POST  /pair                     Onboard: validate one-shot invite, issue device token
GET   /circles                  List circles this device is in
(CalDAV/CardDAV served by Radicale on the same host, separate path /dav/)
```

### Auth
- HTTPS bearer token per device for location-api
- HTTP basic auth (per Radicale convention) for CalDAV/CardDAV
- Both credentials issued together at QR-pairing time, stored together client-side
- Tailscale auth-key tagged `tag:family` so the device can reach the server at all

### Storage
- Location: append-only log per device + last-known cache (SQLite v1, Postgres if scale demands)
- Calendar/Contacts/Todos: Radicale on-disk `.ics` / `.vcf` files
- Retention: location 30–90 days raw, 1 year aggregated. Calendar/contacts forever.
- Daily `rsync` snapshot to your laptop for off-server backup.

### Deployment shape
- `docker-compose.yml` with caddy + radicale + location-api + (later) recorder
- Multi-arch images via `docker buildx --platform linux/amd64,linux/arm64`
- Pushed to `ghcr.io/<you>/...` (free, no Azure lock-in)
- Same compose file runs on Azure ARM today, Pi tomorrow
- All state in named volumes; migration = `rsync` + DNS reassignment

### Tailscale config
- ACL: `tag:family` may reach only the location server, not your other tailnet nodes
- Pre-auth keys generated per family member, single-use, 7-day TTL
- MagicDNS: `family-server` (one name, survives Azure→Pi cutover)

### Out of scope for server v1
- Web UI (use OsmAnd/browser on the client; later: server-rendered Leaflet at /map)
- Push notifications (poll model for now)
- E2E key directory
- Public ingress / Tailscale Funnel

---

## Notes & reminders
- Whatever Android client we build, `infra/owntracks-recorder` on the Pi will
  also accept its POSTs as long as we keep the wire format. That's the escape
  hatch if we ever want to validate against a known-good server.
- Don't bind to `FusedLocationProviderClient` — it pulls Google Play Services.
  Use plain `LocationManager` from `android.location.*`.
- Don't bind to `ActivityRecognitionClient` (Play Services). Use
  `Sensor.TYPE_SIGNIFICANT_MOTION` on AOSP.
- Add server hostname to `network_security_config.xml` as a `domain-config`
  with system trust anchors — not the base-config (which trusts none).
- Setup checklist on first launch (regardless of OwnTracks vs DIY):
  - Battery optimization exemption prompt
  - OEM autostart deep-link (Xiaomi/OPPO/Huawei/Vivo)
  - Background location permission
  - Notification permission (Android 13+)
- Tile fetching for option C (osmdroid embedded map): tiles are pulled from
  `tile.openstreetmap.org` and cached on-disk by osmdroid automatically.
  No tile bridge to OsmAnd, no proxying through our server (egress cost),
  no MBTiles bundle in v1. Set a real `User-Agent` (`SilentPulse/<version>`).
- Watch (Bolide) does NOT run Tailscale, does NOT talk to Radicale, does NOT
  fetch tiles. Phone is gateway over BLE for everything: calendar push,
  peer-distance summary, GPX upload. Reuses AsteroidOS-Sync companion pattern.

---

## Changelog
- 2026-04-24 — initial draft. Locked: Android-only, SilentPulse-as-client,
  geo: URI fallback chain, OwnTracks JSON wire format, no embedded map widget.
  Open: topology, ACL, UI surface, publish triggers, service arch, queue,
  E2E, pairing, watch role.
- 2026-04-24 — Map UX restructured into 3 layers. Layer 1 (list) and Layer 2
  (one-shot `geo:`/browser launch) locked. Layer 3 (live multi-peer map) is
  opt-in with a deferred renderer choice between OsmAnd AIDL (B) /
  osmdroid (C) / server-rendered WebView (D). Renderer is a swappable
  plug-in; wire format is identical for all three.
- 2026-04-24 — Tile strategy locked for option C: HTTP fetch from
  `tile.openstreetmap.org` + osmdroid built-in disk cache. No OsmAnd tile
  bridge (rejected — AIDL latency, projection mismatch, version churn).
  No MBTiles bundle in v1.
- 2026-04-24 — Scope expanded to family hub: location + Radicale
  CalDAV/CardDAV/VTODO with per-user + shared collections. Single QR onboarding
  ceremony covers Tailscale auth-key + SilentPulse + DAVx5.
- 2026-04-24 — Server hosting locked: Azure ARM VM (~$25-35/mo) v1, Raspberry
  Pi v2; tailnet-only via Tailscale, no public ingress; multi-arch Docker
  Compose; MagicDNS hostname `family-server` survives node migration.
- 2026-04-24 — Watch architecture locked: phone-as-gateway. Watch does not run
  Tailscale, does not talk to Radicale or location-api directly. Phone pushes
  calendar / peer-distance / notifications to watch over BLE; watch pushes
  HR / GPX back. Reuses AsteroidOS-Sync companion pattern.
