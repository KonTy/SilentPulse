#!/usr/bin/env bash
# One-shot build fix: runs up to 8 rounds of aider fixes until build is clean.
# Usage: bash scripts/aider/fix_build_now.sh

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPTS="$REPO/scripts/aider"
LOG_DIR="$REPO/scripts/aider/logs"
mkdir -p "$LOG_DIR"

MAX_ROUNDS=8
round=0

cd "$REPO"

while [ $round -lt $MAX_ROUNDS ]; do
    round=$((round + 1))
    echo ""
    echo "════ Build-fix round $round/$MAX_ROUNDS — $(date '+%H:%M:%S') ════"

    errors=$(./gradlew assembleNoAnalyticsDebug 2>&1 | grep -E "^e:|error:|defined multiple times" | head -40)

    if [ -z "$errors" ]; then
        echo "✓ BUILD CLEAN after $round round(s)"
        APK=$(find . -name "*.apk" | grep noAnalytics | grep debug | head -1)
        echo "✓ APK: $APK"
        exit 0
    fi

    echo "Errors found, asking aider to fix..."
    echo "$errors"

    # Extract .kt source file paths from Kotlin compiler error lines (e: /path/to/File.kt:N:...)
    error_files=$(printf '%s\n' "$errors" | grep -oE '/[^ :]+\.kt' | sort -u | grep -v "/build/")

    # Always include drivemode directory — most common source of wrong-package issues
    drivemode_files=$(find "$REPO/presentation/src/main/java/com/moez/QKSMS/feature/drivemode" -name "*.kt" 2>/dev/null | tr '\n' ' ')

    # Write prompt to temp file to avoid shell quoting issues with special chars
    fix_prompt_file=$(mktemp /tmp/aider_fix_XXXXXX.txt)
    cat > "$fix_prompt_file" << 'PROMPT_EOF'
Fix ALL build errors listed below. Rules:
- Fix each error in the exact file/line indicated.
- If a file has the wrong package declaration (e.g. com.example.drivesafe) correct it to com.moez.QKSMS.
- If a file imports from com.example.*, fix those imports to match the actual classes in com.moez.QKSMS.
- If @AndroidEntryPoint (Hilt) is used but the project uses Dagger 2 (@Inject), remove @AndroidEntryPoint.
- If a class references PreferencesManager, replace with com.moez.QKSMS.util.Preferences injected via @Inject.
- If SttEngine is referenced but does not exist, create a minimal SttEngine interface in com.moez.QKSMS.feature.drivemode with: fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit); fun stopListening(); fun shutdown()
- If a missing drawable is referenced, create a minimal vector drawable XML in presentation/src/main/res/drawable/.
- If a class in data/ duplicates a class in domain/, delete the data/ version.
- The TtsEngine interface has: fun speak(text: String, onDone: () -> Unit = {}); fun stop(); fun shutdown(); val isReady: Boolean — no onError parameter.
- Do NOT add new features, only fix errors.
Errors:
PROMPT_EOF
    printf '%s\n' "$errors" >> "$fix_prompt_file"

    bash "$SCRIPTS/aider_safe.sh" \
        $error_files $drivemode_files \
        --message "$(cat "$fix_prompt_file")" \
        --yes-always \
        2>&1 | tee -a "$LOG_DIR/fix_build_now_round${round}.log"

    rm -f "$fix_prompt_file"
done

echo "✗ Build still broken after $MAX_ROUNDS rounds"
exit 1
