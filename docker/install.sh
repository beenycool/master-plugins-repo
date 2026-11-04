#!/usr/bin/env bash
# One-time install of the troll server on the VM.
set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)"
DEST=/opt/troll-server

install -d -m 755 "$DEST"
install -m 644 "$SRC/docker-compose.yml" "$DEST/docker-compose.yml"
install -m 755 "$SRC/deploy.sh" "$DEST/deploy.sh"
install -m 755 "$SRC/rollback.sh" "$DEST/rollback.sh"
install -m 755 "$SRC/backup.sh" "$DEST/backup.sh"

for unit in troll-server-deploy troll-server-backup; do
  install -m 644 "$SRC/${unit}.service" "/etc/systemd/system/${unit}.service"
  install -m 644 "$SRC/${unit}.timer" "/etc/systemd/system/${unit}.timer"
done

if [ ! -f "$DEST/.env" ]; then
  printf 'RCON_PASSWORD=%s\n' "$(openssl rand -hex 16)" > "$DEST/.env"
  chmod 600 "$DEST/.env"
fi

mkdir -p "$DEST/plugins" "$DEST/data" "$DEST/backups"

cd "$DEST"
docker compose up -d

systemctl daemon-reload
systemctl enable --now troll-server-deploy.timer troll-server-backup.timer

echo "Installed. Minecraft is on port 30565; deploys every 2 minutes, backups daily."
