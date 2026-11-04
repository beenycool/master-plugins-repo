#!/usr/bin/env bash
# Build a rickroll resource pack from a video and install it on the server.
# usage: tools/build-rickroll-pack.sh <video.mp4> [fps] [width]
set -euo pipefail

VIDEO="${1:-}"
if [ -z "$VIDEO" ] || [ ! -f "$VIDEO" ]; then
  echo "usage: $0 <video> [fps] [width]" >&2
  exit 1
fi

FPS="${2:-10}"
WIDTH="${3:-192}"
HEIGHT=$(( WIDTH * 9 / 16 ))
HEIGHT=$(( HEIGHT / 2 * 2 ))

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/build/pack"
DEST="${DEST:-/opt/troll-server}"
PACK_FORMAT=97

rm -rf "$BUILD"
mkdir -p "$BUILD/assets/guab/textures/frame" "$BUILD/assets/guab/sounds"

echo "Extracting frames (${WIDTH}x${HEIGHT} @ ${FPS}fps)..."
ffmpeg -loglevel error -y -i "$VIDEO" \
  -vf "fps=${FPS},scale=${WIDTH}:${HEIGHT}:flags=lanczos" \
  "$BUILD/assets/guab/textures/frame/%04d.png"

COUNT=$(find "$BUILD/assets/guab/textures/frame" -name '*.png' | wc -l)
if [ "$COUNT" -lt 1 ]; then
  echo "No frames extracted" >&2
  exit 1
fi

echo "Writing ${COUNT} item models..."
python3 - "$BUILD" "$COUNT" <<'PY'
import json
import os
import sys

build, count = sys.argv[1], int(sys.argv[2])
for folder in ("models/item/frame", "models/frame"):
    os.makedirs(os.path.join(build, "assets/guab", folder), exist_ok=True)

for index in range(1, count + 1):
    name = f"{index:04d}"
    model = {"parent": "minecraft:item/generated", "textures": {"layer0": f"guab:frame/{name}"}}
    for folder in ("models/item/frame", "models/frame"):
        path = os.path.join(build, "assets/guab", folder, name + ".json")
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(model, handle, separators=(",", ":"))
PY

echo "Encoding audio..."
HAS_AUDIO=0
if ffprobe -v error -select_streams a:0 -show_entries stream=codec_type -of csv=p=0 "$VIDEO" | grep -q audio; then
  ffmpeg -loglevel error -y -i "$VIDEO" -vn -c:a libvorbis -q:a 5 "$BUILD/assets/guab/sounds/rickroll.ogg"
  cat > "$BUILD/assets/guab/sounds.json" <<'JSON'
{"rickroll":{"sounds":[{"name":"guab:rickroll","stream":true}]}}
JSON
  HAS_AUDIO=1
fi

DURATION=$(ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$VIDEO")
DURATION=${DURATION%.*}

cat > "$BUILD/pack.mcmeta" <<JSON
{"pack":{"description":"Guab rickroll pack","pack_format":${PACK_FORMAT},"supported_formats":[${PACK_FORMAT},${PACK_FORMAT}]}}
JSON

ZIP="$BUILD/rickroll.zip"
(cd "$BUILD" && zip -qr "$ZIP" pack.mcmeta assets)
SHA1=$(sha1sum "$ZIP" | cut -d' ' -f1)

mkdir -p "$DEST/resourcepack" "$DEST/data/plugins/TrollRickroll"
cp "$ZIP" "$DEST/resourcepack/rickroll.zip"
cat > "$DEST/data/plugins/TrollRickroll/pack.properties" <<EOF
frames=${COUNT}
fps=${FPS}
duration=${DURATION}
sha1=${SHA1}
audio=${HAS_AUDIO}
EOF

echo
echo "Installed:"
echo "  pack      $DEST/resourcepack/rickroll.zip ($(du -h "$ZIP" | cut -f1))"
echo "  frames    $COUNT @ ${FPS}fps (${DURATION}s)"
echo "  audio     $HAS_AUDIO"
echo "  sha1      $SHA1"
