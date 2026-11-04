#!/usr/bin/env bash
# Generate a placeholder clip so the cinema can be tested before the real mp4 arrives.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/build"
OUT="$ROOT/build/placeholder.mp4"

FONT=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf
FILTER="testsrc2=size=192x108:rate=10:duration=20"
if [ -f "$FONT" ]; then
  FILTER="${FILTER},drawtext=fontfile=${FONT}:text='PLACEHOLDER':fontcolor=white:fontsize=18:x=(w-text_w)/2:y=(h-text_h)/2"
fi

ffmpeg -loglevel error -y \
  -f lavfi -i "$FILTER" \
  -f lavfi -i "sine=frequency=440:duration=20" \
  -shortest -c:v libx264 -pix_fmt yuv420p -c:a aac "$OUT"

echo "Wrote $OUT"
"$ROOT/tools/build-rickroll-pack.sh" "$OUT" 10 192
