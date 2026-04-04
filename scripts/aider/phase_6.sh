#!/usr/bin/env bash
# Notification Listener: Read ALL app notifications aloud (Signal, Email, WhatsApp, etc.)
# =============================================================================
# Feature: Intercept all system notifications during Drive Mode
#   - Read Signal messages, emails, other messengers
#   - Extract sender + body from notification extras
#   - Privacy: Nothing leaves phone - we only READ what's visible in notification tray
# =============================================================================
# set -e removed: aider exits non-zero on "no changes" which would abort the phase
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"
SCRIPTS="$REPO/scripts/aider"

echo "→ Phase 6: All-app Notification Listener..."

# ── Step 1: Create NotificationListenerService ───────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  --message "$(cat << 'MSG'
Create presentation/src/main/java/com/moez/QKSMS/feature/drivemode/SilentPulseNotificationListener.kt:

This extends NotificationListenerService and:
1. Receives ALL notifications when Drive Mode is enabled
2. Extracts relevant info from each notification:
   - sender = extras.getString(Notification.EXTRA_TITLE)
   - body = extras.getString(Notification.EXTRA_TEXT) or EXTRA_BIG_TEXT
   - app = getPackageName() to identify Signal, Gmail, etc.
3. Formats TTS string: "[App] from [Sender]: [Body]"
4. App name mapping:
   "org.thoughtcrime.securesms" -> "Signal"
   "com.google.android.gm" -> "Gmail"  
   "com.whatsapp" -> "WhatsApp"
   "com.microsoft.teams" -> "Teams"
   "com.slack" -> "Slack"
   etc.
5. Passes to DriveModeService.queueForReading(text, notifContext)
6. Only active when driveModeEnabled AND driveModeReadAllNotifications
7. Stores NotificationContext (sender, packageName, key) for voice reply
8. Override onNotificationPosted() and onNotificationRemoved()

For voice REPLY to non-SMS apps:
- Signal: Use Intent(Intent.ACTION_SEND) with "org.thoughtcrime.securesms" - attempt inline reply via RemoteInput if available
- Gmail: Mark for manual response (TTS: "I'll remind you to reply when you arrive")
- For apps that support inline reply: extract RemoteInput action from WearExtender/CarExtender
  Example: notification.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }

class SilentPulseNotificationListener : NotificationListenerService()
MSG
)"

# ── Step 2: Register service + permission ────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/AndroidManifest.xml \
  --message "$(cat << 'MSG'
Add to AndroidManifest.xml:
<service
    android:name=".feature.drivemode.SilentPulseNotificationListener"
    android:exported="true"
    android:label="SilentPulse Drive Mode"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
    <meta-data
        android:name="android.service.notification.default_filter_types"
        android:value="conversations|alerting" />
</service>
MSG
)"

# ── Step 3: Permission flow in Settings ──────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/java/com/moez/QKSMS/feature/settings/SettingsController.kt \
  --message "$(cat << 'MSG'
In SettingsController, when user enables "Read all notifications":
1. Check if NotificationListenerService has permission:
   val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
       .contains(context.packageName)
2. If not enabled, show dialog explaining: "SilentPulse needs permission to read notifications"
   Then launch: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
3. Add a helper method: fun hasNotificationListenerPermission(): Boolean

Also add to Drive Mode section in settings:
- "Notification Filter" multi-select: checkboxes for Signal, Gmail, WhatsApp, Other
  Store as comma-separated string in preferences: "drivemode_allowed_apps"
- If empty/all = read from all apps
MSG
)"

# ── Step 4: Inline reply to Signal etc ───────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/java/com/moez/QKSMS/feature/drivemode/VoiceCommandProcessor.kt \
  --message "$(cat << 'MSG'
Update VoiceCommandProcessor to handle cross-app replies:

When NotificationContext.app is NOT "sms":
1. Try inline reply via RemoteInput (works for Signal, WhatsApp, Telegram):
   - Get the ReplyAction from stored notification actions (in SilentPulseNotificationListener)
   - Build a RemoteInput.Builder response
   - sendBroadcast with the PendingIntent from the action
   
2. Fallback for apps without inline reply (Gmail etc):
   - TTS: "I cannot directly reply to [App]. Would you like me to open it?"
   - If "yes": launch app with notification intent

Add this helper to SilentPulseNotificationListener:
  companion object {
    val storedNotifications = ConcurrentHashMap<String, StatusBarNotification>()
    fun getStoredReplyIntent(key: String): Notification.Action? { ... }
  }
MSG
)"

echo "✓ Phase 6 COMPLETE - All-app notification listener created"
