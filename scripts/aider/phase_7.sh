#!/usr/bin/env bash
# Build debug APK and optionally deploy to connected phone
# =============================================================================
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"
SCRIPTS="$REPO/scripts/aider"

echo "-> Phase 7: Fix tests then Build APK..."

# ── Step 1: Fix any compile errors first ─────────────────────────────────────
ERRORS=$(./gradlew :presentation:compileNoAnalyticsDebugKotlin 2>&1 | grep "^e:" | wc -l)
if [ "$ERRORS" -gt 0 ]; then
    echo "-> $ERRORS compile errors found. Fixing before tests..."
    ERROR_LIST=$(./gradlew :presentation:compileNoAnalyticsDebugKotlin 2>&1 | grep "^e:" | head -30)
    bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
      --message "Fix these Kotlin compile errors:
$ERROR_LIST"
fi

# ── Step 2: Iterate on test failures (up to 4 rounds) ────────────────────────
MAX_TEST_FIX_ROUNDS=4
round=0
while [ $round -lt $MAX_TEST_FIX_ROUNDS ]; do
    round=$((round + 1))
    echo "-> Test iteration round $round/$MAX_TEST_FIX_ROUNDS..."

    # Run all tests, capturing output. --continue means all tests run even if some fail.
    TEST_OUTPUT=$(./gradlew :domain:test :data:test \
        :presentation:testNoAnalyticsDebugUnitTest \
        --continue 2>&1)
    echo "$TEST_OUTPUT" | tail -20

    # Extract failures
    FAILURES=$(echo "$TEST_OUTPUT" | grep -E "FAILED|tests failed|test failures" | head -20)

    if [ -z "$FAILURES" ]; then
        echo "-> All tests pass!"
        break
    fi

    echo "-> Tests failing. Asking aider to fix (round $round)..."

    # Get the actual test failure details
    FAILURE_DETAILS=$(echo "$TEST_OUTPUT" | grep -A 10 "FAILED\|Exception\|AssertionError" | head -60)

    bash "$SCRIPTS/aider_safe.sh" --no-gitignore \
      --message "Fix these failing unit tests. Tests must pass - do not delete tests, fix the implementation or test setup instead.

Failing tests:
$FAILURES

Failure details:
$FAILURE_DETAILS

Common fixes:
1. Missing mock setup in @Before
2. Wrong RxJava scheduler (use TestScheduler or RxAndroidPlugins.setMainThreadSchedulerHandler)
3. Realm access on wrong thread (mock Realm, do not use real Realm in unit tests)
4. Missing dependency injection setup
5. Outdated API calls in test code"

    # Brief pause before rerunning tests
    sleep 5
done

if [ $round -eq $MAX_TEST_FIX_ROUNDS ]; then
    REMAINING=$(./gradlew :domain:test :data:test :presentation:testNoAnalyticsDebugUnitTest --continue 2>&1 | grep "FAILED" | wc -l)
    if [ "$REMAINING" -gt 0 ]; then
        echo "-> WARNING: $REMAINING tests still failing after $MAX_TEST_FIX_ROUNDS rounds. Building APK anyway."
    fi
fi

# ── Step 3: Build debug APK ───────────────────────────────────────────────────
echo "-> Building debug APK..."
./gradlew assembleNoAnalyticsDebug 2>&1 | tail -10

APK=$(find . -name "*.apk" | grep -i noanalytics | grep -i debug | head -1)
if [ -n "$APK" ]; then
    echo "-> APK built: $APK"
    echo "-> APK size: $(du -sh "$APK" | cut -f1)"
else
    echo "-> WARNING: APK not found after build"
fi

# ── Step 4: Check for connected device ───────────────────────────────────────
DEVICE=$(adb devices 2>/dev/null | grep -v "List\|daemon" | grep "device$" | head -1 | cut -f1)
if [ -n "$DEVICE" ]; then
    echo "-> Installing APK to $DEVICE..."
    adb -s "$DEVICE" install -r "$APK"
    echo "-> Launching SilentPulse..."
    adb -s "$DEVICE" shell am start -n "com.silentpulse.messenger/com.moez.QKSMS.feature.main.MainActivity"
else
    echo "-> No USB device connected. APK is at: $APK"
    echo "   To install: adb install -r $APK"
fi

echo "-> Phase 7 COMPLETE"
