# Unattended Aider Automation — Post-Mortem & Learnings

Every failure mode we hit building SilentPulse, what caused it, and the fix.
Use this as a checklist before starting any new unattended aider session.

---

## Failure 1 — `KeyError: 'message'` crash on every aider call

**Symptom:** Aider prints a Python traceback and exits immediately. Nothing gets done.

**Root cause:** `.aider.conf.yml` had:
```yaml
commit-prompt: "feat: {message}"
```
Aider tried to format that string with a `{message}` variable that doesn't exist in its format context, causing a Python `KeyError`.

**Fix:** Remove `commit-prompt` entirely, or use a plain string with no `{}` placeholders.

**Rule:** Never use Python format-string syntax in `.aider.conf.yml` values.

---

## Failure 2 — `set -e` silently killed phases

**Symptom:** Phase script exits after the first aider call; subsequent steps never run.

**Root cause:** Shell phase scripts had `set -e` at the top. Aider returns exit code `1` when it makes no changes (nothing to do). `set -e` treats any non-zero exit as fatal and aborts.

**Fix:** Remove `set -e` from every phase script. Check exit codes explicitly if needed.

**Rule:** Never use `set -e` in any script that calls aider. Aider's exit codes are not standard Unix success/failure.

---

## Failure 3 — Empty XML resource file broke the build

**Symptom:** `Premature end of file` Gradle error on `strings_pref.xml`. Build permanently broken until manually fixed.

**Root cause:** A crashed mid-session aider left `strings_pref.xml` as a zero-byte file. Gradle's XML parser can't handle an empty file.

**Fix:** Restored `<resources></resources>` manually. Going forward, the pre-flight build-fix loop catches this automatically.

**Rule:** Add an XML sanity check to pre-flight: `find . -name "*.xml" -empty` and write a minimal valid skeleton to any empty ones before running aider.

---

## Failure 4 — Playwright/sudo prompt blocked unattended session indefinitely

**Symptom:** Session hangs forever on `[sudo] password for blin:`. No timeout. No recovery.

**Root cause:** A phase script's `--message` contained a raw URL (`https://jitpack.io`). Aider auto-fetches any URL it sees in a message, which triggers a Playwright browser install requiring sudo.

**Fix:** Never put raw `https://` URLs in `--message` strings. Use text descriptions instead (e.g., "jitpack dot io" or "alphacephei.com/vosk").

**Rule:** Grep all phase scripts for `https://` in `--message` blocks before running. Zero tolerance.

---

## Failure 5 — `browser: false` in config did NOT prevent URL scraping

**Symptom:** Same as Failure 4, even after adding `browser: false` to `.aider.conf.yml`.

**Root cause:** `browser: false` in the YAML config controls an unrelated UI feature (opening a browser window), not aider's web-scraping behaviour. The actual flag is `--no-browser` passed on the command line.

**Fix:** Added `--no-browser` to **every** aider invocation in `aider_safe.sh`.

**Rule:** The config file is NOT a reliable safety net. Flags that block dangerous behaviour (`--no-browser`, `--no-suggest-shell-commands`) must be hardcoded into the wrapper script so they apply unconditionally.

---

## Failure 6 — Aider created a duplicate class that shadowed the real one

**Symptom:** 30+ `Unresolved reference` errors in `data/` module after phases 3/4 completed. `prefs.blockingManager`, `prefs.changelogVersion`, etc. all unresolved.

**Root cause:** Aider created `data/src/main/java/com/moez/QKSMS/util/Preferences.kt` as an empty stub when asked to add preferences. This shadowed the real `Preferences.kt` in `domain/`. Kotlin resolved the wrong class, which had none of the expected properties.

**Fix:** The post-phase build-fix loop detects compile errors and asks aider to fix them, with an explicit hint: *"If a file in data/ shadows a class from domain/, delete or correct the offending file."*

**Rule:** After giving aider any task that might create new files, immediately run a compile check. Don't proceed to the next phase if the build is broken.

---

## Failure 7 — No build health gate between phases

**Symptom:** Breakage introduced in phase 3 silently propagated into phase 4, 5. Errors compounded. The further we got, the harder it was to untangle.

**Root cause:** `run_all_phases.sh` ran each phase regardless of whether the previous phase left the build in a working state.

**Fix:** Added `fix_build_if_broken()` function that:
1. Runs `./gradlew assembleNoAnalyticsDebug`
2. Collects error lines
3. Calls `aider_safe.sh` with errors in the prompt (up to 5 rounds)
4. Is called **before** any phase starts (pre-flight) and **after** each phase completes (post-phase)

**Rule:** Treat the build as an invariant. Every phase must leave it green. The orchestrator is responsible for enforcing this, not the human.

---

## Failure 8 — Aider "no changes made" vs "crash" both exit non-zero, indistinguishable

**Symptom:** Hard to know from exit code alone whether aider succeeded, did nothing, or crashed.

**Root cause:** Aider exits `0` on success, `1` on "no changes needed", and also `1` (with a traceback) on crash. The exit code is meaningless without reading the log.

**Fix:** `aider_safe.sh` reads the call log for `KeyError|Traceback|Exception|Error:` patterns to distinguish crash-retry from clean-no-op.

**Rule:** Always log all aider output to a file. Make pass/fail decisions by inspecting log content, not just exit code.

---

## Failure 9 — `aider_safe.sh` timeout of 90 min with no intermediate check-in

**Symptom:** Not triggered yet, but a single blocked `aider` call (e.g. waiting for a prompt we didn't anticipate) would silently consume 90 minutes before retrying.

**Mitigation:** `yes n |` pipes "n" to all interactive prompts. Combined with `--no-browser` and `--no-suggest-shell-commands`, most prompt sources are eliminated.

**Rule:** Audit new aider flags/versions for new interactive prompts. If aider adds a new Y/n gate, `yes n` handles it automatically.

---

## Failure 10 — Shell string expansion of error output broke the fix_build script

**Symptom:** `unexpected EOF while looking for matching '"'` — the orchestrator crashes when trying to pass build errors to aider.

**Root cause:** `local fix_prompt="...Errors:\n\n$errors"` — when `$errors` (from `./gradlew` output) contains double quotes, backticks, or `$` signs, they break the enclosing `"..."` string.

**Fix:** Write the error message to a temp file and pass it via `--message-file` instead of inline. Or strip special characters from `$errors` before embedding: `errors=$(... | tr -d '"`$\\')`.

**Rule:** Never embed arbitrary command output directly into a shell double-quoted string. Always sanitize or use a temp file.

---

## Failure 11 — Aider added layout referencing a non-existent drawable

**Symptom:** `resource drawable/ic_directions_car_black_24dp not found` — build fails at resource linking.

**Root cause:** Aider added a Drive Mode settings UI section referencing a Material icon that doesn't exist in the project's drawable directory.

**Fix:** Created the missing vector drawable manually. Going forward, the post-phase build-fix loop should catch this since it shows as a build error.

**Rule:** The post-phase build-fix prompt should explicitly say: *"If there are missing drawable or resource errors, create the missing resource file."*

---

## Failure 12 — Aider silently did nothing when no files were passed

**Symptom:** Aider responded "I don't have the file" and made no changes despite being given a detailed fix prompt.

**Root cause:** The `fix_build_now.sh` and `fix_build_if_broken` functions called `aider_safe.sh --message "..."` without passing any file paths. Aider's architect mode requires files in the chat to make edits.

**Fix:** Extract file paths from error lines (`grep -oE '[a-zA-Z0-9_/.-]+\.kt'`) and pass them as positional arguments to aider. Also always include the drivemode feature directory since it's the most common source of issues.

**Rule:** Every aider call that is meant to fix errors must pass the relevant files. Extract them from the error output automatically.

---

## Master Checklist — Before Starting Any Unattended Aider Session

```
[ ] .aider.conf.yml has NO commit-prompt with {} placeholders
[ ] .aider.conf.yml has: yes-always, auto-commits, suggest-shell-commands: false, check-update: false
[ ] aider_safe.sh passes: --no-browser --no-suggest-shell-commands
[ ] aider_safe.sh uses: yes n | timeout N aider ... (pipe + timeout)
[ ] No phase script uses set -e
[ ] No --message string contains a raw https:// URL
[ ] Orchestrator has pre-flight build-fix loop before first phase
[ ] Orchestrator has post-phase build-fix loop after each phase
[ ] Pre-flight checks for empty *.xml files and writes minimal valid skeleton
[ ] All phase done-markers are in correct state (re-run vs skip)
[ ] tmux session has enough columns (>= 200) to avoid line-wrapping issues in logs
```
