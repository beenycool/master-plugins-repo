#!/usr/bin/env bash
# Restore a world/plugin-data backup produced by docker/backup.sh.
#
#   sudo docker/restore.sh /opt/guab-server/backups/world-20261110-030000.tar.gz
#   sudo docker/restore.sh <archive> --dry-run
#   sudo docker/restore.sh <archive> --yes
#
# The archive is extracted to a staging directory first and verified. With
# --yes the live data directory is copied aside, the staged data is overlaid,
# and the server is started again. On any failure during the apply step the
# previous data directory is put back and the server is started.
#
# Env knobs:
#   DEPLOY_DIR=/opt/guab-server
#   COMPOSE_FILE=docker-compose.yml
set -Eeuo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/opt/guab-server}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.yml}"
ARCHIVE="${1:-}"
MODE="${2:-}"

usage() {
  echo "Usage: $0 <backup.tar.gz> [--dry-run|--yes]" >&2
  exit 2
}

[ -n "$ARCHIVE" ] || usage
[ -f "$ARCHIVE" ] || {
  echo "Archive not found: $ARCHIVE" >&2
  exit 1
}
case "$MODE" in
  ""|--dry-run|--yes) ;;
  *) usage ;;
esac

if ! tar -tzf "$ARCHIVE" >/dev/null; then
  echo "Archive is not a readable tar.gz: $ARCHIVE" >&2
  exit 1
fi

cd "$DEPLOY_DIR"
stamp="$(date -u +%Y%m%d-%H%M%S)"
staging="$DEPLOY_DIR/restore-staging-$stamp"
previous="$DEPLOY_DIR/data.pre-restore-$stamp"
mkdir -p "$staging"

echo "Extracting $ARCHIVE to $staging ..."
tar -xzf "$ARCHIVE" -C "$staging"

if [ ! -d "$staging/data" ]; then
  echo "Archive does not contain a data/ directory; refusing to restore." >&2
  rm -rf "$staging"
  exit 1
fi

if [ "$MODE" = "--dry-run" ]; then
  echo "Dry run OK. The archive would overlay these paths onto $DEPLOY_DIR/data:"
  (cd "$staging" && find data -maxdepth 2 -type d | sort | sed 's/^/  /')
  echo "Staging kept at $staging; remove it when you are done."
  exit 0
fi

if [ "$MODE" != "--yes" ]; then
  echo "This will stop the server, copy $DEPLOY_DIR/data aside, and overlay the archive."
  echo "Type RESTORE to continue:"
  read -r answer
  if [ "$answer" != "RESTORE" ]; then
    echo "Cancelled. Staging kept at $staging." >&2
    exit 1
  fi
fi

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  echo "docker compose is unavailable; cannot stop/start the server." >&2
  echo "Staged files are at $staging." >&2
  exit 1
fi

if [ ! -f "$COMPOSE_FILE" ]; then
  echo "Compose file not found: $DEPLOY_DIR/$COMPOSE_FILE" >&2
  exit 1
fi

applied=0
rollback() {
  status=$?
  if [ "$status" -ne 0 ]; then
    echo "Restore failed; rolling back the data directory." >&2
    if [ -d "$previous" ]; then
      rm -rf "$DEPLOY_DIR/data"
      cp -a "$previous" "$DEPLOY_DIR/data"
      echo "Previous data restored from $previous." >&2
    fi
    docker compose -f "$COMPOSE_FILE" up -d --wait || true
  fi
  exit "$status"
}
trap rollback EXIT

echo "Stopping the server ..."
docker compose -f "$COMPOSE_FILE" down --timeout 90

echo "Copying current data aside to $previous ..."
cp -a "$DEPLOY_DIR/data" "$previous"

echo "Overlaying the backup onto $DEPLOY_DIR/data ..."
cp -a "$staging/data/." "$DEPLOY_DIR/data/"

echo "Starting the server ..."
docker compose -f "$COMPOSE_FILE" up -d --wait
applied=1

echo "Restore complete. Previous data kept at $previous."
echo "Staging kept at $staging; remove both when you are happy with the result."
