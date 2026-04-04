#!/usr/bin/env bash
# =============================================================================
# aider_safe.sh — Resilient aider wrapper
# =============================================================================
# Handles:
#   - Token/rate-limit errors (waits 5 min, retries up to 5x)
#   - Any interactive prompts (auto-answers "n" via stdin pipe)
#   - Crash/KeyError bugs in aider (retries automatically)
#   - Hangs (90-minute timeout per aider call)
#
# Usage: bash scripts/aider/aider_safe.sh [aider args...]
# =============================================================================

MAX_RETRIES=5
RETRY_DELAY_RATE_LIMIT=300   # 5 minutes for rate limit / token quota
RETRY_DELAY_ERROR=30         # 30 seconds for other errors
TIMEOUT_SECS=5400            # 90 minutes max per aider call

LOG_DIR="$(cd "$(dirname "$0")" && pwd)/logs"
mkdir -p "$LOG_DIR"

attempt=0

while [ $attempt -lt $MAX_RETRIES ]; do
    attempt=$((attempt + 1))
    CALL_LOG="$LOG_DIR/aider_call_$(date '+%Y%m%d_%H%M%S')_attempt${attempt}.log"

    echo "[aider_safe] Attempt $attempt/$MAX_RETRIES — $(date '+%H:%M:%S')"

    # Run aider:
    #   - "yes n |" auto-answers ALL interactive prompts with "n"
    #     (covers "Open GitHub issue?", "Add to git?", any Y/n prompt)
    #   - timeout kills it if it hangs beyond $TIMEOUT_SECS
    #   - --no-suggest-shell-commands prevents extra prompts
    yes n | timeout $TIMEOUT_SECS aider --no-suggest-shell-commands "$@" \
        2>&1 | tee "$CALL_LOG"

    EXIT_CODE=${PIPESTATUS[1]}  # exit code of `timeout aider ...`

    # Check for rate limit / quota errors in output
    if grep -qiE "rate.?limit|429|quota.?exceeded|too many requests|token.?limit|context.?length|max.?tokens" "$CALL_LOG" 2>/dev/null; then
        echo "[aider_safe] ⚠  Rate limit / token quota hit. Waiting ${RETRY_DELAY_RATE_LIMIT}s ($(( RETRY_DELAY_RATE_LIMIT / 60 )) min)..."
        sleep $RETRY_DELAY_RATE_LIMIT
        continue
    fi

    # Timeout — aider hung
    if [ $EXIT_CODE -eq 124 ]; then
        echo "[aider_safe] ⚠  Timed out after ${TIMEOUT_SECS}s. Retrying in ${RETRY_DELAY_ERROR}s..."
        sleep $RETRY_DELAY_ERROR
        continue
    fi

    # Success
    if [ $EXIT_CODE -eq 0 ]; then
        echo "[aider_safe] ✓ Done (exit 0)"
        exit 0
    fi

    # Aider bug / crash — check for known non-retryable exits
    if grep -qE "KeyError|Traceback|Exception|Error:" "$CALL_LOG" 2>/dev/null; then
        echo "[aider_safe] ⚠  Aider crashed (KeyError/exception). Retrying in ${RETRY_DELAY_ERROR}s..."
        sleep $RETRY_DELAY_ERROR
        continue
    fi

    # Non-zero exit but no recognized error pattern — treat as success
    # (aider exits 1 when it makes no changes, which is fine)
    echo "[aider_safe] ✓ Exited $EXIT_CODE (treated as done)"
    exit 0
done

echo "[aider_safe] ✗ All $MAX_RETRIES attempts failed. Moving on."
exit 1
