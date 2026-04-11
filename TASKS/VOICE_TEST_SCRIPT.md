# Voice Assistant — Pre-Deploy Test Script

Run through every section before releasing a build.
Check each box as you test. A ✅ means "responded correctly and spoke a sensible answer."
A ❌ means "wrong answer, silence, or crash — investigate before shipping."

Log monitor to run in parallel:
```
adb logcat -s WebAiScraper:V VoiceAssistantService:D GeneralQuery:D BraveSearch:D SP_ROUTE:V
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

---

## 3. Time

| # | Say | Expected |
|---|-----|----------|
| 3.1 | "What time is it?" | Speaks current time |
| 3.2 | "What's today's date?" | Speaks today's date |

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
| 5.2 | "Directions to Times Square New York" | Launches nav app |
| 5.3 | "Stop navigation" | Confirms navigation stopped |

---

## 6. Drive time

| # | Say | Expected |
|---|-----|----------|
| 6.1 | "How long to downtown?" | Speaks estimated drive time |
| 6.2 | "How far is it to the airport?" | Speaks drive time/distance |

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

---

## 9. Notifications & email

| # | Say | Expected |
|---|-----|----------|
| 9.1 | "Read my notifications" | Reads each notification aloud |
| 9.2 | *(during reading)* "Skip" | Skips to next |
| 9.3 | *(during reading)* "Repeat" | Re-reads current |
| 9.4 | *(during reading)* "Dismiss" | Dismisses current notification |
| 9.5 | *(during reading)* "Reply: I'll call you back" | Sends inline reply |
| 9.6 | *(during reading)* "Stop" | Stops notification reading |
| 9.7 | "Read my email" | Reads unread emails only |

---

## 10. Stock prices

| # | Say | Expected |
|---|-----|----------|
| 10.1 | "What's the price of Apple stock?" | Speaks AAPL price |
| 10.2 | "Price of Bitcoin" | Speaks BTC price |
| 10.3 | "Price of gold" | Speaks gold price |
| 10.4 | "What is TSLA trading at?" | Speaks Tesla price |

---

## 11. General knowledge — Brave AI (fast path, ~1-2 s)

These should be answered by Brave Search. Log line: `Brave answer candidate`.

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

## 12. General knowledge — Bing WebView fallback (~3-8 s)

Ask something Brave is less likely to have an AI card for.
Log line: `Bing WebView:` then `[BingAI] matched:`.

| # | Say | Expected |
|---|-----|----------|
| 12.1 | "What are good exercises for lower back pain?" | Exercise suggestions |
| 12.2 | "What should I eat to build muscle?" | Protein / diet advice |
| 12.3 | "How do I treat a blister?" | First aid steps |

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

---

## 15. Bing Chat verification (one-time, skip if already done)

```
adb shell am start -n com.silentpulse.messenger.debug/com.silentpulse.messenger.feature.assistant.BingChatVerificationActivity
```

- [ ] Bing Chat page loads
- [ ] CAPTCHA / "I'm human" appears and can be solved
- [ ] Chat interface loads after verification
- [ ] Tap **Done** — app confirms verified
- [ ] Subsequent queries use Bing Chat cookies (check log: `bing_chat_verified=true`)

---

## Notes

- Fill in the **Date** and **Build** columns and keep this file in git so you have a history.
- If a test fails, note the logcat tag and error in the row.

| Date | Build | Tester | All pass? | Notes |
|------|-------|--------|-----------|-------|
|      |       |        |           |       |
