# Voice Assistant — Pre-Deploy Test Script

Run through every section before releasing a build.
Check each box as you test. A ✅ means "responded correctly and spoke a sensible answer."
A ❌ means "wrong answer, silence, or crash — investigate before shipping."

Log monitor to run in parallel:
```
adb logcat -s WebAiScraper:V BraveSearch:D GeneralQuery:D SP_ROUTE:V SP_WAKE:D SP_SESSION:D SP_XAPP:D ConfirmSend:D SmsHandler:D VoiceAssistantSvc:D NavCmd:D DriveTimeCmd:D MusicCmd:D
```

---

## 1. Wake word

| # | Say | Expected |
|---|-----|----------|
| 1.1 | *(wake word)* | Chime / "Listening" |
| 1.2 | *(wake word × 2 in a row)* | Only one listening session starts (no double-trigger) |

---

## 2. Help & discovery

| # | Say | Expected |
|---|-----|----------|
| 2.1 | "What can you do?" | Lists all capabilities |
| 2.2 | "What apps can you talk to?" | Lists installed assistant-capable apps, or "none" |
| 2.3 | "What can SilentPulse do?" *(if cross-app schema is enabled)* | Reads SilentPulse app-specific commands |

---

## 3. Time

| # | Say | Expected |
|---|-----|----------|
| 3.1 | "What time is it?" | Speaks current time |
| 3.2 | "What's today's date?" | Speaks today's date |
| 3.3 | "What time is it in Tokyo?" | Speaks Tokyo time |
| 3.4 | "Time in New York" | Speaks New York time |

---

## 4. Weather

| # | Say | Expected |
|---|-----|----------|
| 4.1 | "What's the weather?" | Speaks local weather |
| 4.2 | "Weather in London" | Speaks London weather |
| 4.3 | "What's the weather in Tokyo?" | Speaks Tokyo weather |
| 4.4 | "Weather along I-95" | Speaks corridor weather for multiple cities |

---

## 5. Navigation

| # | Say | Expected |
|---|-----|----------|
| 5.1 | "Navigate to the nearest hospital" | Launches nav app with destination |
| 5.2 | "Directions to Times Square New York with Google Maps" | Launches Google Maps specifically |
| 5.3 | "Directions to Times Square New York" | Launches preferred open-source map app |
| 5.4 | "What's my ETA?" / "When will I arrive?" | Speaks current route ETA / arrival time from nav notification |
| 5.5 | "How long left on this route?" | Speaks time remaining if a nav notification is active |
| 5.6 | "Stop navigation" | Confirms navigation stopped |
| 5.7 | *(Android 14+, overlay permission disabled)* "Navigate to the airport" | Does **not** crash. Speaks overlay warning and shows SilentPulse foreground notification action **Enable overlay** |

---

## 6. Drive time

| # | Say | Expected |
|---|-----|----------|
| 6.1 | "How long to downtown?" | Speaks estimated drive time |
| 6.2 | "How far is it to the airport?" | Speaks drive time/distance |
| 6.3 | "Drive time to Chicago" | Speaks route duration and distance |
| 6.4 | "ETA to the nearest gas station" | Speaks estimated drive time |
| 6.5 | "How many miles to Seattle?" | Speaks approximate distance and time |

---

## 7. Music & media

| # | Say | Expected |
|---|-----|----------|
| 7.1 | "Play music" (with VLC installed) | Resumes or starts VLC |
| 7.2 | "Play Beethoven" | Searches library for Beethoven |
| 7.3 | "Resume" | Resumes paused media |
| 7.4 | "Listen to book" / "Open Voice" | Opens audiobook app |

---

## 8. App control

| # | Say | Expected |
|---|-----|----------|
| 8.1 | "Open Settings" | Opens system Settings |
| 8.2 | "Open Google Maps" | Opens Google Maps (special-cased) |
| 8.3 | "Open Calculator" | Opens Calculator |
| 8.4 | "Close Settings" | Closes Settings |
| 8.5 | "Launch Camera" | Opens Camera |
| 8.6 | "Close Google Maps" | Attempts to stop Maps / navigation cleanly |

---

## 9. Notifications & email

Post a replyable test notification before running this section:
```
adb shell 'am broadcast -n com.silentpulse.messenger/.feature.debug.DebugTestReceiver \
  -a com.silentpulse.messenger.POST_TEST_MESSAGE \
  --es sender Alice --es message "Are you coming to the meeting?"'
```

### 9a. Basic notification controls

| # | Say | Expected |
|---|-----|----------|
| 9.1 | "Read my notifications" | Reads each notification aloud — "Alice: Are you coming to the meeting? Say dismiss, delete, reply, repeat, or stop." |
| 9.2 | *(during reading)* "Skip" / "Dismiss" | Skips to next / dismisses current |
| 9.3 | *(during reading)* "Repeat" | Re-reads current notification |
| 9.4 | *(during reading)* "Delete" | Deletes notification (and marks SMS read if ours) |
| 9.5 | *(during reading)* "Stop" | Goes idle; notification stays in tray |

### 9b. Reply confirm workflow — notification listener path

Post a fresh test notification, then say "Read my notifications" and wait for the readout. Then:

| # | Say | Expected |
|---|-----|----------|
| 9.6 | "Reply" | "What would you like to say?" |
| 9.7 | *(dictate)* "I'll be there at five" | "I'll send to Alice: I'll be there at five. Say yes, no to cancel, or read back." |
| 9.8 | "Read back" | Re-reads the composed text. "Say yes, no to cancel, or dictate again." |
| 9.9 | "Dictate again" | Returns to dictation — "What would you like to say?" |
| 9.10 | *(re-dictate)* "On my way" | "I'll send to Alice: On my way. Say yes, no to cancel, or read back." |
| 9.11 | "Yes" | "Reply sent." *(check logcat `ConfirmSend:D` for SEND log)* |
| 9.12 | *(same flow)* → say "No" at confirm step | "Reply cancelled. Say dismiss, delete, reply, repeat, or stop." |

### 9c. Email

| # | Say | Expected |
|---|-----|----------|
| 9.13 | "Read my email" | Reads unread emails only |

---

## 10. Stock prices

| # | Say | Expected |
|---|-----|----------|
| 10.1 | "What's the price of Apple stock?" | Speaks AAPL price |
| 10.2 | "Price of Bitcoin" | Speaks BTC price |
| 10.3 | "Price of gold" | Speaks gold price |
| 10.4 | "What is TSLA trading at?" | Speaks Tesla price |

---

## 11. General knowledge — default Brave path (HTTP first, Leo fallback if needed)

These should normally be answered by Brave Search. Log line: `Brave answer candidate`.
If the HTTP answer is missing, the app may fall back to the Brave Leo WebView path.

| # | Say | Expected |
|---|-----|----------|
| 11.1 | "What is the speed of light?" | Correct physics answer |
| 11.2 | "Who invented the telephone?" | Alexander Graham Bell |
| 11.3 | "What is photosynthesis?" | Plant energy explanation |
| 11.4 | "What is the capital of Japan?" | Tokyo |
| 11.5 | "How many bones are in the human body?" | 206 |
| 11.6 | "What is the boiling point of water?" | 100°C / 212°F |
| 11.7 | "What is DNA?" | Explanation of DNA |

---

## 12. Bing Chat — conversation mode (~5-15 s first turn, ~3-8 s follow-ups)

Triggered by saying "bing ..." (or "being ..." — STT mishear alias).
First call loads `bing.com/chat` in a hidden WebView and waits for the SPA to boot.
Follow-up calls inject text directly, preserving multi-turn context.
Log lines to watch: `Bing chat page loaded:`, `Bing chat inject: OK:`, `Bing chat answer`.
If injection fails (`Bing chat: input not found`) it falls back to the search-page scraper.

### 12a. Single-turn queries

| # | Say | Expected |
|---|-----|----------|
| 12.1 | "Bing what are good exercises for lower back pain?" | Exercise suggestions (first call loads chat page) |
| 12.2 | "Bing what should I eat to build muscle?" | Protein / diet advice |
| 12.3 | "Bing how do I treat a blister?" | First aid steps |
| 12.4 | "Being what is a red giant star?" | Same as `bing ...` path; alias works |

### 12b. Multi-turn conversation (context carry-over)

Start a topic, then follow up without re-stating it:

| # | Say | Expected |
|---|-----|----------|
| 12.5 | "Bing tell me about the James Webb telescope" | Speaks summary about JWST |
| 12.6 | "Bing how far away is it?" | Answers *about JWST* (not a generic "how far" — context preserved) |
| 12.7 | "Bing what has it discovered so far?" | Continues the JWST conversation |
| 12.8 | "Bing who built it?" | Still about JWST |

### 12c. Clear session & start fresh

| # | Say | Expected |
|---|-----|----------|
| 12.9 | "Bing clear" | "Clearing Bing session." → "Bing session cleared. Say bing followed by your question to start fresh." |
| 12.10 | "Bing delete cookies" | Same as above |
| 12.11 | "Bing reset" | Same as above |
| 12.12 | "Bing new session" | Same as above |
| 12.13 | "Bing fresh" / "Bing erase" | Same as above |
| 12.14 | *(after clear)* "Bing what are the planets?" | Answer is correct AND starts a fresh conversation (log: `Bing chat: first use`) |
| 12.15 | *(after clear)* "Bing what's the largest one?" | Should NOT know prior context (fresh) |

---

## 12.5. Brave Leo — conversation mode (~5-20 s first turn, ~3-10 s follow-ups)

Triggered by saying "brave …" (prefix is "brave" to avoid STT mis-recognition of the short word "leo").
First call loads `search.brave.com/ask?q=…` in a hidden WebView. SvelteKit hydration takes ~2 s, then Leo's AI answer is polled from the DOM.
Follow-up calls inject the query into Leo's follow-up textarea (preserving conversation context).
**Important:** keep saying the `brave` prefix on follow-up turns; the shared conversation state lives in Leo's WebView, not in the generic app session manager.
Leo cookies are **separate** from Bing — clearing Brave does not affect Bing.

Log lines to watch (tag `WebAiScraper`):
- `Brave Leo: first use — loading …` → initial page load
- `Brave Leo page finished:` → page ready, 2 s hydration starts
- `[LeoAI] sel=… best=…ch:` → first selector that matched + excerpt
- `[LeoAI] no match — dump:` → selectors missed, classes dumped for tuning
- `Leo inject: OK:…` → follow-up query injected successfully
- `Leo inject: NO_INPUT` → Leo's textarea wasn't found (page may need a longer wait)
- `Leo chat poll N: …status…` → polling for new answer
- `Leo answer (N chars):` → answer extracted

### 12.5a. Single-turn queries

| # | Say | Expected |
|---|-----|----------|
| 12.5.1 | "Brave what is the James Webb telescope?" | Speaks Leo AI answer (first call loads page) |
| 12.5.2 | "Brave explain photosynthesis" | Speaks AI summary |
| 12.5.3 | "Brave what causes thunder?" | Correct answer |

### 12.5b. Multi-turn conversation (context carry-over)

| # | Say | Expected |
|---|-----|----------|
| 12.5.4 | "Brave tell me about black holes" | Speaks Leo summary |
| 12.5.5 | "Brave how are they formed?" | Continues the *black holes* topic (context preserved) |
| 12.5.6 | "Brave how massive is the largest known one?" | Still on topic |
| 12.5.7 | "Brave what about quasars?" | Continues in the same conversation thread |

### 12.5c. Clear session & start fresh

| # | Say | Expected |
|---|-----|----------|
| 12.5.8 | "Brave clear" | "Clearing Brave session." → "Brave session cleared. Say brave followed by your question to start fresh." |
| 12.5.9 | "Brave delete" | Same as above |
| 12.5.10 | "Brave reset" | Same as above |
| 12.5.11 | "Brave new session" / "Brave fresh" / "Brave erase" | Same as above |
| 12.5.12 | *(after clear)* "Brave what are neutron stars?" | Fresh conversation (log: `Brave Leo: first use`) |
| 12.5.13 | "Brave what's the largest one?" | Should NOT carry prior black-holes context (new session) |

---

## 13. General knowledge — DDG/Wikipedia ultimate fallback

Force this by running `adb shell am broadcast` to disable the scraper:
```
adb shell am broadcast -a com.silentpulse.SET_SCRAPER_ENABLED \
  -n com.silentpulse.messenger.debug/.feature.assistant.ApiKeyReceiver \
  --ez enabled false
```
*(or temporarily call WebAiSearchScraper.setEnabled(context, false) in code)*

| # | Say | Expected |
|---|-----|----------|
| 13.1 | "What is the speed of sound?" | DDG or Wikipedia answers |
| 13.2 | "What is theobromine?" | Wikipedia extract |
| 13.3 | "How many planets are in the solar system?" | 8 |

---

## 14. Edge cases

| # | Say | Expected |
|---|-----|----------|
| 14.1 | *(gibberish / unknown command)* | Graceful "I don't know how to handle that" |
| 14.2 | *(question with no network)* | "No data connection" message |
| 14.3 | "What can SilentPulse do?" *(if cross-app)* | Lists SilentPulse commands |
| 14.4 | *(wake word while TTS is speaking)* | Does not interrupt mid-sentence |
| 14.5 | Two quick commands back-to-back | Second queues or waits correctly |
| 14.6 | *(Android 14+, overlay permission off)* navigation request | No crash; assistant refreshes notification with **Enable overlay** action |
| 14.7 | *(turn off location, then ask drive time)* "How long to the airport?" | Explains location is required |

---

## 15. SMS commands (wake-word path via VoiceAssistantService)

### 15a. Read SMS

| # | Say | Expected |
|---|-----|----------|
| 15.1 | "Read my SMS" | Reads unread SMS aloud: "You have N unread messages. Message 1 from [name]: [text]. Say next, repeat, or stop." |
| 15.2 | *(during reading)* "Next" | Reads next SMS |
| 15.3 | *(during reading)* "Repeat" | Re-reads current SMS |
| 15.4 | *(during reading)* "Stop" | Goes idle |

### 15b. Send SMS — confirm workflow

| # | Say | Expected |
|---|-----|----------|
| 15.5 | "Send a text to [contact name]" | Resolves contact, "What would you like to say to [name]?" |
| 15.6 | *(dictate)* "I'm running late" | "I'll send to [name]: I'm running late. Say yes, no to cancel, or read back." |
| 15.7 | "Read back" | Re-reads. "Say yes, no to cancel, or dictate again." |
| 15.8 | "Dictate again" | Re-prompts for dictation |
| 15.9 | "Yes" | "Message sent." *(check logcat `SmsHandler:D`)* |
| 15.10 | *(same flow)* → say "No" at confirm | "Cancelled." |
| 15.11 | "Send text to [unknown name]" | "I couldn't find a contact named [name]." |

---

## 16. Widget controls

Remove and re-add the drive mode widget if it still shows 3 buttons (the 4-button layout requires a widget re-add).

| # | Action | Expected |
|---|--------|----------|
| 16.1 | Tap **mic** button | Starts wake word / voice assistant |
| 16.2 | Tap **notif reader** toggle | Toggles notification reader on/off — **voice assistant must stay running** (check bubblegum still works after toggling) |
| 16.3 | Tap **stop speaking** (✕ button) | TTS stops immediately mid-sentence |
| 16.4 | Toggle notif reader OFF then back ON | Wake word still responds to "bubblegum" both times |

---

## 17. Bing Chat — first-run setup (required before section 12 tests)

`bing.com/chat` is now the **primary** Bing backend. All "bing ..." voice queries
go to `fetchBingChat()` which opens `bing.com/chat` in a hidden WebView.
If Bing shows a CAPTCHA or login gate on first use, complete it here once —
cookies persist for all subsequent headless queries.

```
adb shell am start -n com.silentpulse.messenger.debug/com.silentpulse.messenger.feature.assistant.BingChatVerificationActivity
```

- [ ] `bing.com/chat` page loads in the verification activity
- [ ] If CAPTCHA / "I'm human" appears: solve it manually
- [ ] Chat input box becomes visible and functional
- [ ] Tap **Done** — app saves `bing_chat_verified = true`
- [ ] Re-run section 12 — log should show `Bing chat: continuing conversation` on second+ turns (not `first use`)

> **Note:** If no CAPTCHA appears on first use, section 12 tests can be run
> directly. The hidden WebView uses a real Chrome UA which often bypasses it.

---

## Notes

- Fill in the **Date** and **Build** columns and keep this file in git so you have a history.
- If a test fails, note the logcat tag and error in the row.

| Date | Build | Tester | All pass? | Notes |
|------|-------|--------|-----------|-------|
|      |       |        |           |       |
