#!/usr/bin/env bash
# Daily snapshot of worlds, plugin data and server.properties. Keeps the last 7.
#
# Env knobs:
#   DEPLOY_DIR=/opt/guab-server
#   BACKUP_DIR=backups
#   BACKUP_KEEP=7
#   BACKUP_PATHS="data/world data/world_nether data/world_the_end data/plugins data/server.properties"
#   RCON_CONTAINER=guab-mc
#   SAVE_WAIT=5
set -Eeuo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/opt/guab-server}"
BACKUP_DIR="${BACKUP_DIR:-backups}"
BACKUP_KEEP="${BACKUP_KEEP:-7}"
BACKUP_PATHS="${BACKUP_PATHS:-data/world data/world_nether data/world_the_end data/plugins data/server.properties}"
RCON_CONTAINER="${RCON_CONTAINER:-guab-mc}"
SAVE_WAIT="${SAVE_WAIT:-5}"

cd "$DEPLOY_DIR"
mkdir -p "$BACKUP_DIR"

saving_disabled=0

rcon() {
  docker exec "$RCON_CONTAINER" rcon-cli "$@" >/dev/null 2>&1 || true
}

container_running() {
  docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$RCON_CONTAINER"
}

save_off() {
  if container_running; then
    # Disable autosave first, then force the pending region writes to disk.
    rcon save-off
    rcon save-all flush
    saving_disabled=1
    sleep "$SAVE_WAIT"
  fi
}

save_on() {
  if [ "$saving_disabled" -eq 1 ]; then
    rcon save-on
    saving_disabled=0
  fi
}

cleanup() {
  save_on
}

trap cleanup EXIT
trap 'save_on; exit 130' INT
trap 'save_on; exit 143' TERM

save_off

stamp="$(date -u +%Y%m%d-%H%M%S)"
target="$BACKUP_DIR/world-${stamp}.tar.gz"

paths=()
for path in $BACKUP_PATHS; do
  if [ -e "$path" ]; then
    paths+=("$path")
  fi
done
if [ "${#paths[@]}" -eq 0 ]; then
  echo "Nothing to back up (checked: $BACKUP_PATHS)." >&2
  exit 1
fi

tar -czf "$target" "${paths[@]}"
if ! tar -tzf "$target" >/dev/null; then
  echo "Backup verification failed: $target is not a readable tarball." >&2
  rm -f "$target"
  exit 1
fi
if ! tar -tzf "$target" | grep -qx 'data/server.properties'; then
  echo "Warning: backup does not contain data/server.properties." >&2
fi

# Prune oldest, keeping BACKUP_KEEP files.
mapfile -t old_backups < <(ls -1t "$BACKUP_DIR"/world-*.tar.gz 2>/dev/null | tail -n +"$((BACKUP_KEEP + 1))")
if [ "${#old_backups[@]}" -gt 0 ]; then
  rm -f "${old_backups[@]}"
fi
echo "Backed up to $target ($(du -h "$target" | cut -f1), ${#paths[@]} paths)."

save_on
