#!/usr/bin/env bash
# Rebrand from QKSMS to SilentPulse
# =============================================================================
# set -e removed: aider exits non-zero on "no changes" which would abort the phase
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"
SCRIPTS="$REPO/scripts/aider"

echo "→ Phase 3: Rebranding to SilentPulse..."

# ── Step 1: Bulk rename strings/resources ────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/res/values/strings.xml \
  presentation/src/main/res/values/strings_pref.xml \
  presentation/src/main/AndroidManifest.xml \
  --message "$(cat << 'MSG'
Rebrand QKSMS to SilentPulse:
1. In strings.xml: change app_name from "QKSMS" to "SilentPulse"
2. In AndroidManifest.xml: update android:label references to "SilentPulse"
3. In strings.xml: update any QKSMS+ references to SilentPulse+
4. Keep all other strings unchanged
5. DO NOT change package names (com.moez.QKSMS stays as the package for now)
MSG
)"

# ── Step 2: Update build configs ─────────────────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/build.gradle \
  --message "$(cat << 'MSG'
Update presentation/build.gradle:
1. Change applicationId from "com.moez.QKSMS" to "com.silentpulse.messenger"  
2. Keep namespace as "com.moez.QKSMS" (separate from applicationId - this is the code namespace)
3. Update versionName to "5.0.0" (reflecting the major rebrand)
4. Update versionCode to 5000000
MSG
)"

# ── Step 3: Update About/changelog UI text ───────────────────────────────────
bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
  presentation/src/main/java/com/moez/QKSMS/feature/settings/about/AboutController.kt \
  presentation/src/main/res/values/strings.xml \
  --message "$(cat << 'MSG'
Update About screen for SilentPulse rebrand:
1. Change app name display to "SilentPulse"  
2. Add a subtitle: "Formerly QKSMS • Privacy-first messaging"
3. Update any GitHub/store links for the app
4. Keep all other about info (version, license, authors)
MSG
)"

echo "✓ Phase 3 COMPLETE - rebranded to SilentPulse"
