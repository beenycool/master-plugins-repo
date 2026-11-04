#!/usr/bin/env bash
# Swap back to the previously deployed jars. Stays until a new build is published.
set -euo pipefail

cd /opt/guab-server

exec 9>/run/guab-deploy.lock
flock -w 120 9

[ -d previous ] && compgen -G "previous/*.jar" >/dev/null || {
  echo "No previous build to roll back to."
  exit 1
}

BASE="https://github.com/beenycool/master-plugins-repo/releases/download/deploy-latest"
remote="$(curl -fsSL --retry 3 --retry-delay 2 "${BASE}/build.txt?$(date +%s)" || true)"

docker compose down
rm -f data/plugins/*.jar plugins/*.jar
cp previous/*.jar plugins/

if [ -s .previous-build ]; then
  cp .previous-build .installed-build
fi

# Mark the build we rolled away from as seen so the poller does not re-apply it.
if [ -n "$remote" ]; then
  echo "$remote" > .deployed-build
fi

docker compose up -d

echo "Rolled back to $(cat .installed-build 2>/dev/null || echo 'the previous jars')."
echo "Stays until a new build is published."
