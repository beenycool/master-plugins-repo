#!/usr/bin/env bash
# Daily world snapshot. Keeps the last 7.
set -euo pipefail

cd /opt/troll-server
mkdir -p backups

docker exec troll-mc rcon-cli save-all flush >/dev/null 2>&1 || true
sleep 3

stamp="$(date -u +%Y%m%d-%H%M%S)"
tar -czf "backups/world-${stamp}.tar.gz" -C data world world_nether world_the_end 2>/dev/null \
  || tar -czf "backups/world-${stamp}.tar.gz" -C data world

ls -1t backups/world-*.tar.gz | tail -n +8 | xargs -r rm -f
echo "Backed up to backups/world-${stamp}.tar.gz"
