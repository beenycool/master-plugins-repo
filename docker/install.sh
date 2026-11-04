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
install -m 755 "$SRC/webhook.py" "$DEST/webhook.py"

for unit in troll-server-deploy troll-server-backup; do
  install -m 644 "$SRC/${unit}.service" "/etc/systemd/system/${unit}.service"
  install -m 644 "$SRC/${unit}.timer" "/etc/systemd/system/${unit}.timer"
done
install -m 644 "$SRC/troll-server-webhook.service" /etc/systemd/system/troll-server-webhook.service
install -m 644 "$SRC/troll-server-pack.service" /etc/systemd/system/troll-server-pack.service

if [ ! -f "$DEST/.env" ]; then
  printf 'RCON_PASSWORD=%s\n' "$(openssl rand -hex 16)" > "$DEST/.env"
  chmod 600 "$DEST/.env"
fi
if ! grep -q '^DEPLOY_TOKEN=' "$DEST/.env"; then
  printf 'DEPLOY_TOKEN=%s\n' "$(openssl rand -hex 32)" >> "$DEST/.env"
fi

mkdir -p "$DEST/plugins" "$DEST/data" "$DEST/backups" "$DEST/resourcepack" "$DEST/media"

cd "$DEST"
docker compose up -d

systemctl daemon-reload
systemctl enable --now troll-server-deploy.timer troll-server-backup.timer troll-server-webhook.service troll-server-pack.service

echo "Installed. Minecraft on 30565, webhook 39001, resource packs 39002."
