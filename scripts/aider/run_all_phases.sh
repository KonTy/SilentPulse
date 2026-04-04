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
}

# Mark phase 1 as done since compilation is already at 0 errors
if [ ! -f "$PHASE_DONE_DIR/phase_1.done" ]; then
    echo "pre-done: compilation already at 0 errors" > "$PHASE_DONE_DIR/phase_1.done"
    log "⏭  Phase 1 pre-marked as done (compilation already clean)"
fi

TARGET="${1:-all}"

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
