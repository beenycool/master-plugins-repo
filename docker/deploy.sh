#!/usr/bin/env bash
# Pulls the latest rolling plugin build and restarts the Minecraft container.
# Runs as root from guab-server-deploy.timer, every 2 minutes.
set -euo pipefail

cd /opt/guab-server

exec 9>/run/guab-deploy.lock
flock -n 9 || exit 0

BASE="https://github.com/beenycool/master-plugins-repo/releases/download/deploy-latest"
STAMP="$(date +%s)"
THIRD_PARTY_DIR="${THIRD_PARTY_DIR:-/opt/guab-server/third-party}"

remote="$(curl -fsSL --retry 3 --retry-delay 2 "${BASE}/build.txt?${STAMP}")" || exit 0
[ -n "$remote" ] || exit 0
if [ "${1:-}" != "--force" ] && [ "$remote" = "$(cat .deployed-build 2>/dev/null || true)" ]; then
  exit 0
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

curl -fsSL --retry 3 --retry-delay 2 "${BASE}/plugins.sha256?${STAMP}" -o "$tmp/plugins.sha256"
curl -fsSL --retry 3 --retry-delay 2 "${BASE}/plugins.tar.gz?${STAMP}" -o "$tmp/plugins.tar.gz"

(cd "$tmp" && sha256sum -c plugins.sha256)

mkdir -p "$tmp/jars"
tar -xzf "$tmp/plugins.tar.gz" -C "$tmp/jars"
compgen -G "$tmp/jars/*.jar" >/dev/null || { echo "tarball had no jars"; exit 1; }

echo "Deploying build ${remote}"

if docker ps --format '{{.Names}}' | grep -qx guab-mc; then
  docker exec guab-mc rcon-cli say "Plugins updated - restarting in 15s" || true
  sleep 15
fi

docker compose down

if compgen -G "plugins/*.jar" >/dev/null; then
  mkdir -p previous
  rm -f previous/*.jar
  cp plugins/*.jar previous/
  if [ -f .installed-build ]; then
    cp .installed-build .previous-build
  fi
fi

rm -f data/plugins/*.jar plugins/*.jar
cp "$tmp/jars"/*.jar plugins/

# Third-party plugins (Vault, the casino) are pinned outside the release and are
# reinstalled on every deploy so a jar swap never drops them.
if compgen -G "$THIRD_PARTY_DIR/*.jar" >/dev/null; then
  cp "$THIRD_PARTY_DIR"/*.jar plugins/
fi

docker compose up -d
echo "$remote" > .deployed-build
echo "$remote" > .installed-build
echo "Deployed ${remote}"
