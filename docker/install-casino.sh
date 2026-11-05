#!/usr/bin/env bash
# Installs the pinned third-party plugins the casino event needs.
# Run on the VM before deploying; deploy.sh reinstalls them on every jar swap.
set -euo pipefail

DEST="${DEST:-/opt/guab-server/third-party}"
mkdir -p "$DEST"

fetch() {
  local url="$1" name="$2" sha="$3"
  local tmp="$DEST/$name.tmp"
  echo "Fetching $name"
  curl -fsSL --retry 3 --retry-delay 2 "$url" -o "$tmp"
  echo "$sha  $tmp" | sha256sum -c -
  mv "$tmp" "$DEST/$name"
}

# MineGames 1.0.6 (GPL-3.0) - physical casino stations, Vault economy.
# The installed jar carries docker/patches/minegames-fights.patch, which spawns
# fighters in the arena's world instead of the console's world, and previews them
# (frozen, with their real gear) during the betting window. Keep it once built.
if [ -f "$DEST/minegames-1.0.6.jar" ]; then
  echo "Keeping the patched MineGames build already installed."
else
  fetch "https://cdn.modrinth.com/data/YAetfB2l/versions/zZcPJxzf/minegames-1.0.6.jar" \
    "minegames-1.0.6.jar" \
    "d27a9a0d9bef34e52d018a7ed540f370649c18462a32321c6f0db6c517e3e0b5"
fi

# VaultUnlocked 2.20.3 - Vault API and the economy service registry.
fetch "https://cdn.modrinth.com/data/ayRaM8J7/versions/qZgRzoYs/VaultUnlocked-2.20.3.jar" \
  "VaultUnlocked-2.20.3.jar" \
  "fbc6651aca11e13376c115df66eeffa79592e79517be398299aa6beb49c22695"

echo "Installed:"
ls -lh "$DEST"/*.jar
