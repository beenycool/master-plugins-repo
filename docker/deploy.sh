#!/usr/bin/env bash
# Pulls the latest rolling plugin build and restarts the Minecraft container.
# Runs as root from guab-server-deploy.timer every 2 minutes.
#
#   deploy.sh                 deploy the newest published build
#   deploy.sh --force         redeploy the current build
#   deploy.sh --check         validate prerequisites/config, change nothing
#   deploy.sh --dry-run       show what would be deployed, change nothing
set -Eeuo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/opt/guab-server}"
LOCK_FILE="${LOCK_FILE:-/run/guab-deploy.lock}"
PAUSE_FILE="${PAUSE_FILE:-$DEPLOY_DIR/.deploy-paused}"
BASE="${DEPLOY_BASE:-https://github.com/beenycool/master-plugins-repo/releases/download/deploy-latest}"
THIRD_PARTY_DIR="${THIRD_PARTY_DIR:-$DEPLOY_DIR/third-party}"
STOP_TIMEOUT="${STOP_TIMEOUT:-90}"
HEALTH_WAIT="${HEALTH_WAIT:-420}"

MODE=deploy
FORCE=0
for arg in "$@"; do
  case "$arg" in
    --check) MODE=check ;;
    --dry-run) MODE=dry-run ;;
    --force) FORCE=1 ;;
    -h|--help)
      sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "usage: $0 [--check|--dry-run|--force]" >&2
      exit 2
      ;;
  esac
done

cd "$DEPLOY_DIR"

if [ -f "$PAUSE_FILE" ]; then
  echo "Deploy paused by $PAUSE_FILE ($(cat "$PAUSE_FILE" 2>/dev/null || true))."
  exit 0
fi

if [ "$MODE" = check ]; then
  command -v docker >/dev/null || { echo "docker is not installed" >&2; exit 1; }
  command -v curl >/dev/null || { echo "curl is not installed" >&2; exit 1; }
  command -v flock >/dev/null || { echo "flock is not installed" >&2; exit 1; }
  command -v tar >/dev/null || { echo "tar is not installed" >&2; exit 1; }
  [ -f docker-compose.yml ] || { echo "missing $DEPLOY_DIR/docker-compose.yml" >&2; exit 1; }
  RCON_PASSWORD="${RCON_PASSWORD:-check}" docker compose config -q
  echo "deploy.sh --check: ok (STOP_TIMEOUT=${STOP_TIMEOUT}s HEALTH_WAIT=${HEALTH_WAIT}s)"
  exit 0
fi

exec 9>"$LOCK_FILE"
flock -n 9 || { echo "Another deploy/rollback holds $LOCK_FILE."; exit 0; }

STAMP="$(date +%s)"
remote="$(curl -fsSL --retry 3 --retry-delay 2 "${BASE}/build.txt?${STAMP}")" || exit 0
[ -n "$remote" ] || exit 0
if [ "$FORCE" -ne 1 ] && [ "$remote" = "$(cat .deployed-build 2>/dev/null || true)" ]; then
  exit 0
fi

if [ "$MODE" = dry-run ]; then
  echo "Would deploy build ${remote} (currently $(cat .deployed-build 2>/dev/null || echo none))."
  echo "Would: download, verify checksum, docker compose down --timeout ${STOP_TIMEOUT},"
  echo "       swap jars, docker compose up -d --wait, then write .deployed-build."
  exit 0
fi

tmp="$(mktemp -d)"
cleanup_tmp() {
  rm -rf "$tmp"
}
trap cleanup_tmp EXIT

curl -fsSL --retry 3 --retry-delay 2 "${BASE}/plugins.sha256?${STAMP}" -o "$tmp/plugins.sha256"
curl -fsSL --retry 3 --retry-delay 2 "${BASE}/plugins.tar.gz?${STAMP}" -o "$tmp/plugins.tar.gz"

(cd "$tmp" && sha256sum -c plugins.sha256)

# Reject absolute paths and traversal before extracting a downloaded tarball.
if tar -tzf "$tmp/plugins.tar.gz" | grep -Eq '(^/|(^|/)\.\./)'; then
  echo "Refusing to extract unsafe tarball paths" >&2
  exit 1
fi
tar -tzf "$tmp/plugins.tar.gz" >/dev/null
mkdir -p "$tmp/jars"
tar -xzf "$tmp/plugins.tar.gz" -C "$tmp/jars"
compgen -G "$tmp/jars/*.jar" >/dev/null || { echo "tarball had no jars" >&2; exit 1; }

echo "Deploying build ${remote}"

deploying=0
healthy=0

install_third_party() {
  if compgen -G "$THIRD_PARTY_DIR/*.jar" >/dev/null; then
    cp "$THIRD_PARTY_DIR"/*.jar plugins/
  fi
}

restore_previous() {
  trap - ERR INT TERM
  set +e
  echo "Deploy failed; restoring the previous jars and restarting..." >&2
  rm -f data/plugins/*.jar plugins/*.jar
  if compgen -G "previous/*.jar" >/dev/null; then
    cp previous/*.jar plugins/
  else
    echo "No previous jars were available to restore." >&2
  fi
  install_third_party
  if ! docker compose up -d --wait --wait-timeout "$HEALTH_WAIT"; then
    echo "Previous jars also failed the health check; check docker compose logs." >&2
    docker compose up -d || true
  fi
}

on_error() {
  local status=$?
  if [ "$deploying" -eq 1 ] && [ "$healthy" -ne 1 ]; then
    restore_previous
  fi
  exit "$status"
}

on_signal() {
  local status=$1
  if [ "$deploying" -eq 1 ] && [ "$healthy" -ne 1 ]; then
    restore_previous
  fi
  exit "$status"
}

trap on_error ERR
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

if docker ps --format '{{.Names}}' | grep -qx guab-mc; then
  docker exec guab-mc rcon-cli say "Plugins updated - restarting in 15s" || true
  sleep 15
fi

if compgen -G "plugins/*.jar" >/dev/null; then
  mkdir -p previous
  rm -f previous/*.jar
  cp plugins/*.jar previous/
  if [ -f .installed-build ]; then
    cp .installed-build .previous-build
  fi
fi

deploying=1
docker compose down --timeout "$STOP_TIMEOUT"

rm -f data/plugins/*.jar plugins/*.jar
cp "$tmp/jars"/*.jar plugins/
install_third_party

# --wait only returns once the healthcheck passes or HEALTH_WAIT expires. The
# deployed-build marker is written afterwards so a failed deploy is retried.
docker compose up -d --wait --wait-timeout "$HEALTH_WAIT"
healthy=1

printf '%s\n' "$remote" > .deployed-build.tmp
mv .deployed-build.tmp .deployed-build
printf '%s\n' "$remote" > .installed-build
echo "Deployed ${remote} (health check passed)."
