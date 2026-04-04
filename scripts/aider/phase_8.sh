#!/usr/bin/env bash
# Add crash reporting + remote log viewing while phone is disconnected
# =============================================================================
# Uses: Firebase Crashlytics (already in project) + FileLoggingTree
# You can view crashes at: https://console.firebase.google.com
# =============================================================================
# set -e removed: aider exits non-zero on "no changes" which would abort the phase
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"
SCRIPTS="$REPO/scripts/aider"

echo "→ Phase 8: Crash reporting + remote diagnostics..."

# ── Wire up Crashlytics + logging ─────────────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/java/com/moez/QKSMS/common/QKApplication.kt \
  presentation/src/main/java/com/moez/QKSMS/common/util/FileLoggingTree.kt \
  presentation/build.gradle \
  --message "$(cat << 'MSG'
Set up crash reporting and diagnostics for SilentPulse:

1. In QKApplication.kt:
   - Initialize Firebase Crashlytics (already a dependency): FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
   - Plant both DebugTree AND FileLoggingTree so all logs are written to /sdcard/Android/data/com.silentpulse.messenger/files/Logs/
   - Add an UncaughtExceptionHandler that logs the full stack trace to the file log before crashing

2. In FileLoggingTree.kt:
   - Ensure logs are written with timestamp, priority, tag, message
   - Also log to Crashlytics as custom keys: FirebaseCrashlytics.getInstance().log("$priorityString/$tag: $message")
   - For ERROR and WTF priority, call: FirebaseCrashlytics.getInstance().recordException(t ?: Exception(message))
   - Keep file log to last 7 days (delete older log files on startup)

3. In QKApplication.kt - add diagnostic startup log:
   Timber.i("SilentPulse started - version ${BuildConfig.VERSION_NAME}, SDK ${Build.VERSION.SDK_INT}, device ${Build.MODEL}")

This gives you:
- Real-time logs on file (readable via adb pull or file manager)
- Crash stack traces in Firebase Console when the phone is not connected
MSG
)"

# ── Settings: Add log viewer ──────────────────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/java/com/moez/QKSMS/feature/settings/SettingsController.kt \
  presentation/src/main/res/values/strings.xml \
  --message "$(cat << 'MSG'
Add a "Diagnostics" section at the bottom of Settings:
1. "Enable detailed logging" toggle (maps to prefs.logging preference - already exists)
2. "Share logs" button → shares the current log file via Intent.ACTION_SEND (File provider)
3. "Clear logs" button → deletes all log files
4. Info text: "Log files are stored on your device. Share them for support."

This lets users share logs with you for debugging even when the device isn't connected.
MSG
)"

echo "✓ Phase 8 COMPLETE - crash reporting + diagnostics set up"
echo ""
echo "View crashes at: https://console.firebase.google.com"
echo "Pull logs from device: adb pull /sdcard/Android/data/com.silentpulse.messenger/files/Logs/ ~/SilentPulseLogs/"
