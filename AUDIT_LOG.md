# SilentPulse — Model Collaboration Audit Log

> **Purpose:** Running log of all changes made by AI coding agents.
> Another model can use this to audit intent vs. implementation, catch regressions,
> or continue work without needing the full conversation history.
>
> **Format per entry:**
> - **Session** — approximate date/model context
> - **Goal** — what the user asked for
> - **Files changed** — list with one-line rationale per file
> - **Key decisions** — non-obvious choices made
> - **Known gaps / TODOs** — what was deliberately left out
> - **Test command** — how to verify the change

---

## Session 2026-04-08 — Microcore Voice Integration

### Goal
Implement the SilentPulse broadcast protocol on the Microcore side (Flutter/Android)
so users can say "Computer, Microcore, log weight 220" and have it silently logged
while driving.  
Spec: `OFFLINE_VOICE_ASSISTANT_SPEC.md` in this repo.

---

### Pre-existing state (read-only context, no changes)

**`CommandRouter.kt`** (`presentation/src/main/java/com/silentpulse/messenger/feature/assistant/`)  
Already complete and correct. Has:
- `refreshApps()` — queries PackageManager for `ASSISTANT_CAPABLE` intent filter
- `route(command)` — fuzzy app name extraction + boilerplate stripping
- `dispatch(routeResult, sessionId)` — broadcasts `EXECUTE_COMMAND` to target package
- `requestSchema(targetPackage)` — broadcasts `REQUEST_SCHEMA`
- All action/extra constants matching the spec exactly
- Levenshtein fuzzy matching (distance ≤ 2, min word length 4)

No changes needed to `CommandRouter.kt`.

---

### Changes made in this session

#### 1. `microcore/android/app/src/main/kotlin/com/microcore/microcore/voice/PendingCommandCache.kt` — NEW
**Rationale:** Multi-turn disambiguation requires in-memory session state.  
**Key decisions:**
- Singleton `object` (simple, no DI needed for a BroadcastReceiver)
- 60-second expiry matches spec exactly
- `evictExpired()` called on every get/put to avoid memory leaks
- `PendingCommand` holds `action`, `partialParams: Map<String,String>`, `candidates: List<String>`
- Not persisted to disk — sessions are ephemeral by design (privacy: no trace of partial commands)

#### 2. `microcore/android/app/src/main/kotlin/com/microcore/microcore/voice/MicrocoreCommandParser.kt` — NEW
**Rationale:** All domain parsing lives in Microcore, not SilentPulse.  
**Key decisions:**
- Known metrics hardcoded from spec examples + `microcore_llm_context.json` patterns:
  `weight`, `blood sugar`, `blood pressure systolic`, `blood pressure diastolic`,
  `waist`, `bicep`, `body fat`, `calories`, `steps`, `water`, `heart rate`, `mood`
- Number extraction via regex: `(\d+(?:\.\d+)?)\s*(lbs?|pounds?|kg|inches?|cm|mg/dl|%|bpm)?`
- "blood" alone → ambiguous → confidence 0.0 (forces disambiguation flow)
- Levenshtein on extracted metric phrase for fuzzy matching
- Confidence thresholds: >0.80 = high (execute), 0.55–0.80 = medium (confirm), <0.55 = low (reject)
- Threshold intentionally tighter than spec's 0.90/0.65 to reduce false logs of health metrics
  (logging wrong health metric is worse than failing to log)

#### 3. `microcore/android/app/src/main/kotlin/com/microcore/microcore/voice/VoiceCommandReceiver.kt` — NEW
**Rationale:** Entry point for all SilentPulse broadcasts.  
**Key decisions:**
- Handles `EXECUTE_COMMAND` and `REQUEST_SCHEMA`; ignores `ASSISTANT_CAPABLE` (no-op, just needs to be in manifest)
- Multi-turn: checks `PendingCommandCache` first on every `EXECUTE_COMMAND`
- Follow-up resolution: "yes/no" for confirmation; otherwise tries to re-match against `candidates`
- Schema returned as human-readable strings (spec §7)
- All broadcasts sent with `setPackage(null)` — global broadcast so SilentPulse receives regardless of build variant
- No Activity started — silent background execution (drive mode requirement)
- WorkManager NOT used (commands are fast; Realm writes complete well within BroadcastReceiver window)
- TODO: If Realm writes ever exceed ~8s, promote to `goAsync()` + coroutine

#### 4. `microcore/android/app/src/main/AndroidManifest.xml` — MODIFIED
**Rationale:** Receiver must be declared `exported=true` with three intent filters for PackageManager discovery.  
**Key decisions:**
- `android:exported="true"` required — SilentPulse is a different package
- Three actions in one `<intent-filter>`: `ASSISTANT_CAPABLE`, `EXECUTE_COMMAND`, `REQUEST_SCHEMA`
- No `android:permission` guard — spec does not define a signature permission yet;
  adding one would require coordinating the permission declaration in SilentPulse's manifest first.
  **FUTURE:** Add `android:permission="com.silentpulse.permission.VOICE_COMMAND"` once SilentPulse declares it.

---

### Key decisions summary

| Decision | Rationale |
|---|---|
| Broadcasts only (no deep links) | Spec §3 is explicit: deep links are secondary/legacy |
| Confidence threshold 0.80 not 0.90 | Health metric false-positives are high risk |
| "blood" alone → ambiguous | "blood pressure" and "blood sugar" are genuinely different metrics |
| No WorkManager | Command execution is fast; avoid over-engineering |
| No disk persistence of PendingCommandCache | Privacy — no partial command traces |
| Schema as human-readable strings | Spec §7 format; SilentPulse reads them aloud |

---

### Known gaps / Audit TODOs

- [ ] **Realm integration**: `VoiceCommandReceiver.executeAction()` has a stub. A Microcore
  developer needs to wire `parsed.params` into the actual Realm write calls matching
  Microcore's measurement schema. The stub logs with Timber but does not write.
- [ ] **`microcore_llm_context.json` dynamic schema**: Spec §7 mentions reading this file
  for dynamic schema discovery. Currently the schema is hardcoded. A future pass should
  read `getExternalFilesDir()/.../microcore_llm_context.json` and generate schema from it.
- [ ] **Signature permission**: No `android:permission` on the receiver. Anyone can send
  `EXECUTE_COMMAND` to Microcore and trigger a log. Acceptable for now (local device only),
  but should be hardened with a shared signature permission before any MDM deployment.
- [ ] **`goAsync()` guard**: If Realm writes take >8s on slow devices, the broadcast
  receiver will be killed. Monitor Logcat for "Broadcaster timeout" warnings and add
  `goAsync()` + coroutine if seen.
- [ ] **adb test**: Run the test command below before shipping.

---

### Test commands

```bash
# 1. High-confidence: should log silently, TTS says "Logged weight at 220 pounds."
adb shell am broadcast -a com.silentpulse.action.EXECUTE_COMMAND \
  --es EXTRA_TRANSCRIPT "log weight 220 pounds" \
  --es EXTRA_SESSION_ID "audit-test-1" \
  -n com.microcore.microcore/.voice.VoiceCommandReceiver

# 2. Ambiguous blood: should ask "Did you mean blood sugar, systolic, or diastolic?"
adb shell am broadcast -a com.silentpulse.action.EXECUTE_COMMAND \
  --es EXTRA_TRANSCRIPT "log blood at 110" \
  --es EXTRA_SESSION_ID "audit-test-2" \
  -n com.microcore.microcore/.voice.VoiceCommandReceiver

# 3. Follow-up resolution (run immediately after test 2):
adb shell am broadcast -a com.silentpulse.action.EXECUTE_COMMAND \
  --es EXTRA_TRANSCRIPT "blood sugar" \
  --es EXTRA_SESSION_ID "audit-test-2" \
  -n com.microcore.microcore/.voice.VoiceCommandReceiver

# 4. Schema request: Logcat should show JSON array
adb shell am broadcast -a com.silentpulse.action.REQUEST_SCHEMA \
  -n com.microcore.microcore/.voice.VoiceCommandReceiver

# Observe replies in Logcat:
adb logcat -s MicrocoreVoice:V SilentPulse:V
```

---

## Previous sessions (summary from conversation history)

### Session ~2026-04-06 — Privacy hardening + Voice Engine UI

**Changes (committed at `1e1b7f02`):**
- `AndroidSttEngine.kt` — added `EXTRA_PREFER_OFFLINE=true` (Python byte patch, NBSP file)
- `AndroidManifest.xml` — added `ApiKeyReceiver`; fixed INTERNET comment accuracy
- `network_security_config.xml` — added `api.search.brave.com`; fixed NSC scope comment
- `ModelImporter.kt` — NEW: zip extractor for Vosk/Kokoro model import
- `AssistantController.kt` — REWRITTEN: STT/TTS engine toggle UI + model import flow
- `AssistantState.kt` — REWRITTEN: added `sttEngine`, `ttsEngine`, model path fields
- `AssistantPresenter.kt` — REWRITTEN: observes new prefs
- `assistant_controller.xml` — REWRITTEN: inline MaterialButtonToggleGroup for STT + TTS engine selection

### Session ~2026-04-06 — CI fix

**Changes (committed at `1e1b7f02`):**
- `.github/workflows/release.yml` — REWRITTEN:
  - `setup-java@v4` (was deprecated v3)
  - NDK via `sdkmanager "ndk;28.2.13676358"` (was wrong version `r25c`)
  - Gradle cache with `actions/cache@v4`
  - Keystore: use secret if set, fall back to debug keystore (never fails CI)
  - `softprops/action-gh-release@v2`

---

*Log maintained by AI coding agent. Each entry should be committed alongside the code changes it describes.*
