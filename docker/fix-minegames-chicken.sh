#!/usr/bin/env bash
# The casino chicken has two MineGames settings that make it look broken:
# the multiplier never grows and pickups never pay. Restore the live-crash
# behaviour, then ask MineGames to reload its config in the running server.
set -euo pipefail

CONFIG="${MINEGAMES_CONFIG:-/opt/guab-server/data/plugins/MineGames/config.yml}"

if [ ! -f "$CONFIG" ]; then
  echo "MineGames config not found: $CONFIG" >&2
  exit 1
fi

cp "$CONFIG" "$CONFIG.bak-$(date +%s)"

python3 - "$CONFIG" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
lines = path.read_text().splitlines(keepends=True)
inside = False
seen = set()
for index, line in enumerate(lines):
    if line.startswith("chicken:"):
        inside = True
        continue
    if inside and line and not line.startswith((" ", "\t")):
        inside = False
    if not inside:
        continue
    stripped = line.strip()
    if stripped.startswith("multiplier-increase-per-second:"):
        lines[index] = "  multiplier-increase-per-second: 0.03\n"
        seen.add("multiplier")
    elif stripped.startswith("pickup-multiplier:"):
        lines[index] = "  pickup-multiplier: 0.25\n"
        seen.add("pickup")
path.write_text("".join(lines))
if seen != {"multiplier", "pickup"}:
    raise SystemExit(f"Only patched {sorted(seen)} in {path}")
PY

if docker ps --format '{{.Names}}' | grep -qx guab-mc; then
  docker exec guab-mc rcon-cli chickenadmin reload || true
fi

echo "Chicken multiplier restored: 0.03/s growth, 0.25 per pickup."
