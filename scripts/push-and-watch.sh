#!/usr/bin/env bash
# Push to GitHub and watch the CI pipeline until it completes.
# Usage:  ./scripts/push-and-watch.sh [git push args...]
#
# Examples:
#   ./scripts/push-and-watch.sh                 # plain push
#   ./scripts/push-and-watch.sh --force-with-lease  # force push
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

echo "==> Pushing to GitHub..."
git push "$@"

# Give GitHub a moment to register the run
sleep 2

# Grab the latest run on the current branch
RUN_ID=$(gh run list --branch "$(git branch --show-current)" --limit 1 --json databaseId --jq '.[0].databaseId')

if [ -z "$RUN_ID" ]; then
    echo "!! Could not find a CI run. Check https://github.com/KonTy/SilentPulse/actions"
    exit 1
fi

echo "==> Watching run $RUN_ID …"
echo "    https://github.com/KonTy/SilentPulse/actions/runs/$RUN_ID"
echo ""

gh run watch "$RUN_ID" --exit-status --compact && {
    echo ""
    echo "==> CI PASSED"
} || {
    echo ""
    echo "==> CI FAILED — check logs with:  gh run view $RUN_ID --log-failed"
    exit 1
}
