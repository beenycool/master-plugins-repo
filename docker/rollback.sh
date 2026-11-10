#!/usr/bin/env bash
# Swap back to the previously deployed jars. Stays until a new build is published.
set -Eeuo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/opt/guab-server}"
LOCK_FILE="${LOCK_FILE:-/run/guab-deploy.lock}"
PAUSE_FILE="${PAUSE_FILE:-$DEPLOY_DIR/.deploy-paused}"
STOP_TIMEOUT="${STOP_TIMEOUT:-90}"
HEALTH_WAIT="${HEALTH_WAIT:-420}"
THIRD_PARTY_DIR="${THIRD_PARTY_DIR:-$DEPLOY_DIR/third-party}"

cd "$DEPLOY_DIR"

if [ -f "$PAUSE_FILE" ]; then
  echo "Deploy/rollback paused by $PAUSE_FILE."
  exit 0
fi

exec 9>"$LOCK_FILE"
flock -w 120 9

[ -d previous ] && compgen -G "previous/*.jar" >/dev/null || {
  echo "No previous build to roll back to."
  exit 1
}

BASE="${DEPLOY_BASE:-https://github.com/beenycool/master-plugins-repo/releases/download/deploy-latest}"
remote="$(curl -fsSL --retry 3 --retry-delay 2 "${BASE}/build.txt?$(date +%s)" || true)"

docker compose down --timeout "$STOP_TIMEOUT"
rm -f data/plugins/*.jar plugins/*.jar
cp previous/*.jar plugins/

if compgen -G "$THIRD_PARTY_DIR/*.jar" >/dev/null; then
  cp "$THIRD_PARTY_DIR"/*.jar plugins/
fi

if [ -s .previous-build ]; then
  cp .previous-build .installed-build
fi

docker compose up -d --wait --wait-timeout "$HEALTH_WAIT"

# Mark the build we rolled away from as seen only after the rollback is healthy,
# so the poller does not re-apply it if the rollback fails.
if [ -n "$remote" ]; then
  printf '%s\n' "$remote" > .deployed-build
fi

echo "Rolled back to $(cat .installed-build 2>/dev/null || echo 'the previous jars')."
echo "Stays until a new build is published."
