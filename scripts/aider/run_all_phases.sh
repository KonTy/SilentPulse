#!/usr/bin/env bash
# =============================================================================
# SilentPulse Aider Automation Master Script
# =============================================================================
# Usage: bash scripts/aider/run_all_phases.sh [phase_number]
# Example: bash scripts/aider/run_all_phases.sh 3   # runs only phase 3
#          bash scripts/aider/run_all_phases.sh      # runs all phases
#          bash scripts/aider/run_all_phases.sh 3-8  # runs phases 3 through 8
# =============================================================================
# AUTONOMOUS MODE: no set -e, failures are logged and skipped, not fatal
# Phase 1 is skipped by default (compilation already at 0 errors)
# =============================================================================

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPTS="$REPO/scripts/aider"
LOG_DIR="$REPO/scripts/aider/logs"
mkdir -p "$LOG_DIR"

PHASE_DONE_DIR="$LOG_DIR/done"
mkdir -p "$PHASE_DONE_DIR"

START_TIME=$(date '+%Y-%m-%d %H:%M:%S')
SUMMARY_LOG="$LOG_DIR/run_$(date '+%Y%m%d_%H%M%S').log"

log() { echo "$*" | tee -a "$SUMMARY_LOG"; }

# =============================================================================
# Build-fix loop: detect compile errors and hand them to aider to fix.
# Runs up to MAX_FIX_ROUNDS (default 5). Called before phases and after each phase.
# =============================================================================
fix_build_if_broken() {
    local context_label="${1:-preflight}"
    local MAX_FIX_ROUNDS=5
    local round=0

    while [ $round -lt $MAX_FIX_ROUNDS ]; do
        round=$((round + 1))
        local errors
        errors=$(cd "$REPO" && ./gradlew assembleNoAnalyticsDebug 2>&1 | grep -E "^e:|error:|defined multiple times" | head -40)

        if [ -z "$errors" ]; then
            log "✓ Build is clean ($context_label, round $round)"
            return 0
        fi

        log "⚠  Build errors detected ($context_label, round $round/$MAX_FIX_ROUNDS) — asking aider to fix..."
        log "$errors"

        # Write prompt to temp file to avoid shell quoting issues with special chars in $errors
        local fix_prompt_file
        fix_prompt_file=$(mktemp /tmp/aider_fix_prompt_XXXXXX.txt)
        cat > "$fix_prompt_file" << 'PROMPT_EOF'
The project has compile errors. Fix ALL errors listed below. Rules:
- Do NOT add new features, only fix existing errors.
- If a file in data/ defines a class that also exists in domain/, delete the data/ copy.
- If a drawable or resource is referenced but missing, create a minimal valid version of it.
- If a Kotlin type mismatch, resolve it by using the correct type.
Errors:
PROMPT_EOF
        # Append the actual errors (sanitized) separately
        printf '%s\n' "$errors" >> "$fix_prompt_file"

        # Extract file paths from errors so aider has context to edit
        local error_files
        error_files=$(printf '%s\n' "$errors" | grep -oE '[a-zA-Z0-9_/.-]+\.kt' | sort -u | while read f; do
            full=$(find "$REPO" -path "*/$f" -not -path "*/build/*" 2>/dev/null | head -1)
            [ -n "$full" ] && echo "$full"
        done)
        local drivemode_files
        drivemode_files=$(find "$REPO/presentation/src/main/java/com/moez/QKSMS/feature/drivemode" -name "*.kt" 2>/dev/null | tr '\n' ' ')

        cd "$REPO" && bash "$SCRIPTS/aider_safe.sh" \
            $error_files $drivemode_files \
            --message "$(cat "$fix_prompt_file")" \
            --yes-always \
            2>&1 | tee -a "$LOG_DIR/build_fix_${context_label}_round${round}.log"
        rm -f "$fix_prompt_file"
    done

    log "✗ Build still broken after $MAX_FIX_ROUNDS fix rounds ($context_label) — continuing anyway"
    return 1
}

run_phase() {
    local phase=$1
    local script="$SCRIPTS/phase_${phase}.sh"
    local phase_log="$LOG_DIR/phase_${phase}.log"
    local done_marker="$PHASE_DONE_DIR/phase_${phase}.done"

    # Skip if already completed successfully
    if [ -f "$done_marker" ]; then
        log "⏭  Phase $phase already done ($(cat $done_marker)) — skipping"
        return 0
    fi

    if [ ! -f "$script" ]; then
        log "✗ Phase $phase script not found: $script"
        return 1
    fi

    log ""
    log "════════════════════════════════════════════════════"
    log "  PHASE $phase: $(head -2 "$script" | tail -1 | sed 's/# //')"
    log "  Started: $(date '+%H:%M:%S')"
    log "════════════════════════════════════════════════════"

    # Run the phase, capture output, do NOT abort on failure
    if bash "$script" 2>&1 | tee "$phase_log"; then
        local ts=$(date '+%Y-%m-%d %H:%M:%S')
        echo "$ts" > "$done_marker"
        log "✓ Phase $phase complete at $ts"
    else
        local exit_code=$?
        log "⚠  Phase $phase exited with code $exit_code — see $phase_log"
        log "   Continuing to next phase..."
    fi

    # After each phase: check build health and auto-fix any new compile errors
    fix_build_if_broken "post_phase_${phase}"
}

# Mark phase 1 as done since compilation is already at 0 errors
if [ ! -f "$PHASE_DONE_DIR/phase_1.done" ]; then
    echo "pre-done: compilation already at 0 errors" > "$PHASE_DONE_DIR/phase_1.done"
    log "⏭  Phase 1 pre-marked as done (compilation already clean)"
fi

TARGET="${1:-all}"

# Pre-flight: fix empty XML files (aider crashes leave zero-byte XML that breaks Gradle)
log ""
log "→ Pre-flight XML sanity check..."
while IFS= read -r -d '' xml_file; do
    log "   ⚠  Empty XML found: $xml_file — writing minimal skeleton"
    echo '<?xml version="1.0" encoding="utf-8"?><resources></resources>' > "$xml_file"
done < <(find "$REPO" -name "*.xml" -not -path "*/build/*" -empty -print0)

# Pre-flight: delete any duplicate class files in data/ that shadow domain/ classes
log ""
log "→ Pre-flight duplicate class check..."
while IFS= read -r -d '' data_file; do
    relative="${data_file#$REPO/data/src/main/}"
    domain_equiv="$REPO/domain/src/main/$relative"
    if [ -f "$domain_equiv" ]; then
        log "   ⚠  Duplicate class: $data_file shadows $domain_equiv — deleting data/ copy"
        rm -f "$data_file"
    fi
done < <(find "$REPO/data/src/main" -name "*.kt" -not -path "*/build/*" -print0)

# Pre-flight: fix any compile errors before starting phases
log ""
log "→ Pre-flight build check..."
fix_build_if_broken "preflight"

if [ "$TARGET" = "all" ]; then
    log "Starting all phases from phase 2 onward..."
    log "Repo: $REPO"
    log "Time: $START_TIME"
    for i in 2 3 4 5 6 7 8; do
        run_phase $i
    done
elif echo "$TARGET" | grep -q '-'; then
    # Range like 3-8
    START=$(echo "$TARGET" | cut -d- -f1)
    END=$(echo "$TARGET" | cut -d- -f2)
    log "Running phases $START through $END"
    for i in $(seq $START $END); do
        run_phase $i
    done
else
    run_phase "$TARGET"
fi

log ""
log "════════════════════════════════════════════════════"
log "  ALL PHASES COMPLETE"
log "  Finished: $(date '+%Y-%m-%d %H:%M:%S')"
log "════════════════════════════════════════════════════"
log "  Log: $SUMMARY_LOG"

# Final build verification
log ""
log "→ Final build check..."
cd "$REPO"
if ./gradlew assembleNoAnalyticsDebug 2>&1 | tail -3 | tee -a "$SUMMARY_LOG"; then
    APK=$(find . -name "*.apk" | grep noAnalytics | grep debug | head -1)
    log "✓ APK: $APK"
else
    log "✗ Build failed — check logs in $LOG_DIR"
fi
