#!/usr/bin/env bash
# Build a rickroll resource pack from a video and install it on the server.
# usage: tools/build-rickroll-pack.sh <video.mp4> [fps] [width]
#
# Same pack layout and pack.properties as before (models guab:item/frame/NNNN,
# frames=<total frame count>), so the plugin needs no changes.
#
# Env knobs:
#   COLORS=256                    palette size (shared by every frame)
#   DITHER=bayer:bayer_scale=3    paletteuse dither: none | bayer:bayer_scale=0..5 |
#                                 floyd_steinberg | sierra2_4a ...
#   AUDIO_QUALITY=5               libvorbis -q:a
#   DEST=/opt/guab-server
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
WORK="$ROOT/build/work"
DEST="${DEST:-/opt/guab-server}"
PACK_FORMAT=97
COLORS="${COLORS:-256}"
DITHER="${DITHER:-bayer:bayer_scale=3}"
AUDIO_QUALITY="${AUDIO_QUALITY:-5}"

FRAMES="$BUILD/assets/guab/textures/item/frame"

rm -rf "$BUILD" "$WORK"
mkdir -p "$FRAMES" "$BUILD/assets/guab/sounds" "$WORK"

VF="fps=${FPS},scale=${WIDTH}:${HEIGHT}:flags=lanczos"

# One palette for the whole clip instead of one per frame: no palette flicker
# between frames and no per-frame PLTE chunk. reserve_transparent=0 keeps all
# colors usable (the default wastes one slot on transparency).
echo "Building shared ${COLORS}-color palette..."
ffmpeg -loglevel error -y -i "$VIDEO" \
  -vf "${VF},palettegen=max_colors=${COLORS}:reserve_transparent=0:stats_mode=full" \
  -frames:v 1 -update 1 "$WORK/palette.png"

echo "Extracting frames (${WIDTH}x${HEIGHT} @ ${FPS}fps, dither=${DITHER})..."
ffmpeg -loglevel error -y -i "$VIDEO" -i "$WORK/palette.png" -an \
  -lavfi "[0:v]${VF}[x];[x][1:v]paletteuse=dither=${DITHER}" \
  "$FRAMES/%04d.png"

COUNT=$(find "$FRAMES" -name '*.png' | wc -l)
if [ "$COUNT" -lt 1 ]; then
  echo "No frames extracted" >&2
  exit 1
fi

# Lossless recompression of the already-indexed PNGs.
if command -v oxipng >/dev/null 2>&1; then
  echo "Optimizing ${COUNT} PNGs (oxipng + zopfli)..."
  oxipng -o max --zopfli --strip all -q "$FRAMES"/*.png || true
elif command -v advpng >/dev/null 2>&1; then
  echo "Optimizing PNGs (advpng)..."
  find "$FRAMES" -name '*.png' -print0 \
    | xargs -0 -P "$(nproc)" -n 32 advpng -z4 -q || true
elif command -v optipng >/dev/null 2>&1; then
  echo "Optimizing PNGs (optipng)..."
  find "$FRAMES" -name '*.png' -print0 \
    | xargs -0 -P "$(nproc)" -n 32 optipng -o2 -quiet || true
fi

# Models for every frame index (the plugin still steps 0001..COUNT), but
# byte-identical frames share one texture and one model.
echo "Writing item models for ${COUNT} frames..."
python3 - "$BUILD" "$COUNT" <<'PY'
import hashlib
import json
import os
import sys

build, count = sys.argv[1], int(sys.argv[2])
asset = os.path.join(build, "assets/guab")
frames = os.path.join(asset, "textures/item/frame")
models = os.path.join(asset, "models/item/frame")
items = os.path.join(asset, "items/frame")
os.makedirs(models, exist_ok=True)
os.makedirs(items, exist_ok=True)

def dump(path, data):
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(data, handle, separators=(",", ":"))

seen = {}
unique = 0
for index in range(1, count + 1):
    name = f"{index:04d}"
    png = os.path.join(frames, name + ".png")
    with open(png, "rb") as handle:
        digest = hashlib.sha1(handle.read()).hexdigest()
    if digest in seen:
        canon = seen[digest]
        os.remove(png)
    else:
        canon = name
        seen[digest] = name
        unique += 1
        dump(os.path.join(models, name + ".json"),
             {"parent": "minecraft:item/generated", "textures": {"layer0": f"guab:item/frame/{name}"}})
    # 1.21.4+ resolves ITEM_MODEL through assets/<ns>/items/<id>.json.
    dump(os.path.join(items, name + ".json"),
         {"model": {"type": "minecraft:model", "model": f"guab:item/frame/{canon}"}})

print(f"  {unique} unique textures for {count} frames")
PY

echo "Encoding audio..."
HAS_AUDIO=0
if ffprobe -v error -select_streams a:0 -show_entries stream=codec_type -of csv=p=0 "$VIDEO" | grep -q audio; then
  ffmpeg -loglevel error -y -i "$VIDEO" -vn -c:a libvorbis -q:a "$AUDIO_QUALITY" "$BUILD/assets/guab/sounds/rickroll.ogg"
  cat > "$BUILD/assets/guab/sounds.json" <<'JSON'
{"rickroll":{"sounds":[{"name":"guab:rickroll","stream":true}]}}
JSON
  HAS_AUDIO=1
fi

DURATION=$(ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$VIDEO")
DURATION=${DURATION%.*}

cat > "$BUILD/pack.mcmeta" <<JSON
{"pack":{"description":"Guab rickroll pack","pack_format":${PACK_FORMAT},"min_format":${PACK_FORMAT},"max_format":${PACK_FORMAT}}}
JSON

ZIP="$BUILD/rickroll.zip"
(cd "$BUILD" && zip -qr -X -9 "$ZIP" pack.mcmeta assets)
SHA1=$(sha1sum "$ZIP" | cut -d' ' -f1)

mkdir -p "$DEST/resourcepack" "$DEST/data/plugins/GuabRickroll"
cp "$ZIP" "$DEST/resourcepack/rickroll.zip"
cat > "$DEST/data/plugins/GuabRickroll/pack.properties" <<EOF
frames=${COUNT}
fps=${FPS}
duration=${DURATION}
sha1=${SHA1}
audio=${HAS_AUDIO}
EOF

UNIQUE=$(find "$FRAMES" -name '*.png' | wc -l)
echo
echo "Installed:"
echo "  pack      $DEST/resourcepack/rickroll.zip ($(du -h "$ZIP" | cut -f1))"
echo "  frames    $COUNT @ ${FPS}fps (${DURATION}s), ${UNIQUE} unique PNGs, $(du -ch "$FRAMES"/*.png | tail -1 | cut -f1)"
if [ "$HAS_AUDIO" = 1 ]; then
  echo "  audio     $(du -h "$BUILD/assets/guab/sounds/rickroll.ogg" | cut -f1)"
else
  echo "  audio     none"
fi
echo "  sha1      $SHA1"
