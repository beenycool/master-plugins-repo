#!/usr/bin/env python3
"""Build the GuabItems resource pack from real product photos.

Most beer photos are front-of-pack images from Open Food Facts
(https://world.openfoodfacts.org, CC BY-SA 3.0, contributed by its
photographers). Baileys uses the CC0 Wikimedia Commons photo
"BaileysIrishCream.JPG". The dartboard is Wikimedia Commons
"Dartboard unlabeled.svg" (CC BY-SA 3.0). The pack icon is a composite of
those assets.

Beer items are modern 1.21.4+ item definitions that point at generated 3D
models: each brand texture is cut out of its photo, then every 4-pixel-tall
row of the 64x64 texture becomes a 1-unit-tall cuboid whose width follows the
alpha silhouette and whose depth is 1.5-4 model units. The flat fallback
(dark backdrop + frame) is only used when a photo has no clean background to
remove; the build prints which textures took that path.

This tool is deliberately not part of the Gradle build: media stays on the VM.
Run it from the repo root with Pillow and numpy installed:

    sudo tools/build-guab-items-pack.py

The minimal base assets (``pack.mcmeta`` and the red-carpet override) live in
``pack/items`` and are committed, so a fresh checkout can build without the
live server. Pass ``--items-base`` with a legacy ``guab-items.zip`` to use an
existing pack as the base instead. The rickroll base still comes from the live
server or ``--rickroll-base``; it is only re-zipped so its frames and audio
survive.

The server now delivers the two packs stacked (guab-items first, then
rickroll), so this tool no longer duplicates the beer, dartboard, icon, HUD font
or custom sounds into rickroll.zip. Those assets live only in the guab-items
pack; rickroll.zip carries just its frames, rickroll.ogg/sounds.json and pack
metadata. ``build-rickroll-pack.sh`` is the source of that pack.

It rebuilds the standalone guab-items pack, validates both finished ZIPs, and
updates the rickroll pack.properties SHA-1 atomically (or prints the exact
command when the file is not writable). It also prints the SHA-1 for
server.properties.

The HUD font and custom sounds live in pack/hud. Regenerate those source
assets with tools/build-guab-hud-assets.py (Pillow + numpy + ffmpeg), then run
this script to copy them into the guab-items zip only. Run this script after
build-rickroll-pack.sh so the rickroll SHA-1 matches the current frames.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import shlex
import shutil
import tempfile
import urllib.request
import zipfile
from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

USER_AGENT = "GuabMinecraft/1.0 (private Paper server; pack build tool)"
ROOT = Path(__file__).resolve().parent.parent
HUD_SOURCE = ROOT / "pack" / "hud"
ITEMS_BASE_SOURCE = ROOT / "pack" / "items"

BRAND_IMAGES = {
    "stella": "https://images.openfoodfacts.org/images/products/789/114/910/1900/2.400.jpg",
    "carling": "https://images.openfoodfacts.org/images/products/501/003/804/9440/3.400.jpg",
    "fosters": "https://images.openfoodfacts.org/images/products/541/026/391/2432/front_fr.6.400.jpg",
    "sanmiguel": "https://images.openfoodfacts.org/images/products/841/065/500/0980/front_fr.9.400.jpg",
    "peroni": "https://images.openfoodfacts.org/images/products/80067955/front_fr.36.400.jpg",
    "moretti": "https://images.openfoodfacts.org/images/products/000/008/011/4024/front_en.32.400.jpg",
    "heineken": "https://images.openfoodfacts.org/images/products/520/126/100/6038/front_en.5.400.jpg",
    "corona": "https://images.openfoodfacts.org/images/products/501/437/901/4457/2.400.jpg",
    "budweiser": "https://images.openfoodfacts.org/images/products/006/206/733/5341/8.400.jpg",
    "johnsmiths": "https://images.openfoodfacts.org/images/products/503/576/664/1223/front_en.3.400.jpg",
    "strongbow": "https://images.openfoodfacts.org/images/products/503/576/604/9463/front_en.3.400.jpg",
    "thatchers": "https://images.openfoodfacts.org/images/products/502/062/800/3318/front_en.3.400.jpg",
    "londonpride": "https://images.openfoodfacts.org/images/products/501/188/500/3647/front_en.9.400.jpg",
    "guinness": "https://images.openfoodfacts.org/images/products/500/021/310/1223/19.400.jpg",
    "baileys_lime": (
        "https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e4/"
        "BaileysIrishCream.JPG/330px-BaileysIrishCream.JPG"
    ),
}
DARTBOARD_IMAGE = (
    "https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f3/"
    "Dartboard_unlabeled.svg/960px-Dartboard_unlabeled.svg.png"
)

# Alpha values below this are treated as empty when finding the silhouette.
ALPHA_THRESHOLD = 24
# Increasing tolerances for the border flood fill.  The first colour that
# leaves a plausible subject is used; a later tolerance is only preferred when
# it removes more obvious background (subject area <= 75% of the photo).
CUTOUT_TOLERANCES = (32, 44, 56, 72, 88, 104)
# Keep the largest connected component plus any detached part at least this
# fraction of its size (e.g. a lime wedge beside a bottle).
COMPONENT_KEEP_RATIO = 0.10
# Fixed ZIP timestamp: makes the SHA-1 values reproducible across rebuilds.
ZIP_DATE_TIME = (2000, 1, 1, 0, 0, 0)

# Vanilla block-item display transforms, used so the extruded pint reads as a
# solid object in the inventory and in both hands.
ITEM_DISPLAY = {
    "gui": {"rotation": [25, 210, 0], "scale": [0.625, 0.625, 0.625]},
    "ground": {
        "rotation": [0, 0, 0],
        "translation": [0, 3, 0],
        "scale": [0.25, 0.25, 0.25],
    },
    "fixed": {"rotation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
    "on_shelf": {"rotation": [0, 180, 0], "scale": [1, 1, 1]},
    "thirdperson_righthand": {
        "rotation": [75, 45, 0],
        "translation": [0, 2.5, 0],
        "scale": [0.375, 0.375, 0.375],
    },
    "thirdperson_lefthand": {
        "rotation": [75, -45, 0],
        "translation": [0, 2.5, 0],
        "scale": [0.375, 0.375, 0.375],
    },
    "firstperson_righthand": {
        "rotation": [0, 45, 0],
        "scale": [0.4, 0.4, 0.4],
    },
    "firstperson_lefthand": {
        "rotation": [0, 225, 0],
        "scale": [0.4, 0.4, 0.4],
    },
}


def download(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def border_median(image: Image.Image):
    """Return the median opaque border colour, or None for an empty image."""
    rgba = image.convert("RGBA")
    width, height = rgba.size
    pixels = rgba.load()
    samples = []
    for x in range(width):
        for y in (0, height - 1):
            red, green, blue, alpha = pixels[x, y]
            if alpha > 8:
                samples.append((red, green, blue))
    for y in range(height):
        for x in (0, width - 1):
            red, green, blue, alpha = pixels[x, y]
            if alpha > 8:
                samples.append((red, green, blue))
    if not samples:
        return None
    return tuple(
        int(sorted(sample[channel] for sample in samples)[len(samples) // 2])
        for channel in range(3)
    )


def remove_flat_background(
        image: Image.Image, background, tolerance: int = 32) -> Image.Image:
    """Flood-fill the border-connected pixels close to ``background`` to alpha 0."""
    image = image.convert("RGBA")
    width, height = image.size
    pixels = image.load()
    seen = bytearray(width * height)
    queue = deque()

    def matches(x: int, y: int) -> bool:
        red, green, blue, alpha = pixels[x, y]
        return (
            alpha > 8
            and abs(red - background[0]) <= tolerance
            and abs(green - background[1]) <= tolerance
            and abs(blue - background[2]) <= tolerance
        )

    for x in range(width):
        for y in (0, height - 1):
            if not seen[y * width + x] and matches(x, y):
                seen[y * width + x] = 1
                queue.append((x, y))
    for y in range(height):
        for x in (0, width - 1):
            if not seen[y * width + x] and matches(x, y):
                seen[y * width + x] = 1
                queue.append((x, y))

    while queue:
        x, y = queue.popleft()
        red, green, blue, _ = pixels[x, y]
        pixels[x, y] = (red, green, blue, 0)
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if 0 <= nx < width and 0 <= ny < height:
                index = ny * width + nx
                if not seen[index] and matches(nx, ny):
                    seen[index] = 1
                    queue.append((nx, ny))
    return image


def largest_connected_component(
        image: Image.Image, alpha_threshold: int = ALPHA_THRESHOLD,
        keep_ratio: float = COMPONENT_KEEP_RATIO):
    """Drop specks and return (image, opaque area fraction, bbox)."""
    rgba = image.convert("RGBA")
    alpha = np.asarray(rgba)[:, :, 3]
    mask = alpha >= alpha_threshold
    height, width = mask.shape
    labels = np.zeros((height, width), dtype=np.int32)
    sizes = [0]
    next_label = 0

    for y in range(height):
        for x in range(width):
            if not mask[y, x] or labels[y, x] != 0:
                continue
            next_label += 1
            labels[y, x] = next_label
            queue = deque([(x, y)])
            count = 0
            while queue:
                current_x, current_y = queue.popleft()
                count += 1
                for nx, ny in (
                        (current_x + 1, current_y),
                        (current_x - 1, current_y),
                        (current_x, current_y + 1),
                        (current_x, current_y - 1)):
                    if 0 <= nx < width and 0 <= ny < height:
                        if mask[ny, nx] and labels[ny, nx] == 0:
                            labels[ny, nx] = next_label
                            queue.append((nx, ny))
            sizes.append(count)

    if next_label == 0:
        return rgba, 0.0, None

    largest = max(sizes[1:])
    keep_threshold = max(8, int(largest * keep_ratio))
    keep_lookup = np.zeros(len(sizes), dtype=bool)
    for index, size in enumerate(sizes):
        if index and size >= keep_threshold:
            keep_lookup[index] = True
    keep = keep_lookup[labels]

    result = rgba.copy()
    result_array = np.array(result)
    result_array[:, :, 3] = np.where(keep, result_array[:, :, 3], 0)
    result = Image.fromarray(result_array, "RGBA")

    ys, xs = np.nonzero(keep)
    bbox = (
        int(xs.min()),
        int(ys.min()),
        int(xs.max()) + 1,
        int(ys.max()) + 1,
    )
    return result, float(keep.sum()) / (width * height), bbox


def corner_opacity(image: Image.Image, bbox, alpha_threshold: int = ALPHA_THRESHOLD):
    """Opaque fraction of the four 10% corners of ``bbox``."""
    x0, y0, x1, y1 = bbox
    width = max(1, x1 - x0)
    height = max(1, y1 - y0)
    corner_width = max(1, width // 10)
    corner_height = max(1, height // 10)
    alpha = np.asarray(image.convert("RGBA"))[:, :, 3]
    corners = (
        alpha[y0:y0 + corner_height, x0:x0 + corner_width],
        alpha[y0:y0 + corner_height, x1 - corner_width:x1],
        alpha[y1 - corner_height:y1, x0:x0 + corner_width],
        alpha[y1 - corner_height:y1, x1 - corner_width:x1],
    )
    return [float((corner >= alpha_threshold).mean()) for corner in corners]


def cutout_subject(image: Image.Image, *, max_border_edges=None):
    """Find the product silhouette or return None when the photo is unsuitable.

    ``max_border_edges`` lets the generic pint photo be stricter: if its
    largest component still touches three or four image borders, it is almost
    certainly the bar background rather than the glass, so the caller should
    use the fallback texture instead.
    """
    rgba = image.convert("RGBA")
    background = border_median(rgba)
    if background is None:
        return None

    width, height = rgba.size
    best = None
    for tolerance in CUTOUT_TOLERANCES:
        candidate = remove_flat_background(rgba, background, tolerance)
        candidate, area, bbox = largest_connected_component(candidate)
        if bbox is None:
            continue
        x0, y0, x1, y1 = bbox
        box_width = x1 - x0
        box_height = y1 - y0

        margin_x = max(1, width * 0.02)
        margin_y = max(1, height * 0.02)
        if not (x0 - margin_x <= width / 2 <= x1 + margin_x):
            continue
        if not (y0 - margin_y <= height / 2 <= y1 + margin_y):
            continue
        if not 0.03 <= area <= 0.96:
            continue
        if area > 0.90 and box_width >= 0.99 * width and box_height >= 0.99 * height:
            continue
        if max_border_edges is not None:
            edges = sum((
                x0 <= 1,
                y0 <= 1,
                x1 >= width - 1,
                y1 >= height - 1,
            ))
            if edges > max_border_edges:
                continue
        # If all four corners are opaque, the "cutout" is still the photo
        # rectangle (a boxed product or retained background).  Prefer the
        # current dark-backdrop fallback and report it at build time.
        if all(value >= 0.75 for value in corner_opacity(candidate, bbox)):
            continue

        best = (tolerance, candidate, area, bbox)
        if area <= 0.75:
            break
    return best


def compose_texture(image: Image.Image, size: int = 64, *, max_border_edges=None):
    """Cut the subject out, pad it by at least 1px and centre it on a 64x64 canvas."""
    cutout = cutout_subject(image, max_border_edges=max_border_edges)
    if cutout is not None:
        tolerance, subject, area, bbox = cutout
        subject = subject.crop(bbox)
        # Leave at least one transparent pixel on every side.
        subject.thumbnail((size - 2, size - 2), Image.LANCZOS)
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        canvas.alpha_composite(
            subject, ((size - subject.width) // 2, (size - subject.height) // 2))
        # Keep the atlas in the cutout pass: semi-transparent edge pixels would
        # push the whole sprite into the translucent pass and make stacked
        # faces sort badly in the GUI.
        canvas.putalpha(
            canvas.getchannel("A").point(lambda alpha: 255 if alpha >= 128 else 0))
        return canvas, {
            "cutout": True,
            "tolerance": tolerance,
            "area": area,
            "bbox": bbox,
        }

    canvas = Image.new("RGBA", (size, size), (30, 30, 36, 255))
    ImageDraw.Draw(canvas).rectangle(
        (0, 0, size - 1, size - 1), outline=(90, 78, 52, 255))
    source = image.convert("RGBA")
    source.thumbnail((size - 6, size - 6), Image.LANCZOS)
    canvas.alpha_composite(
        source, ((size - source.width) // 2, (size - source.height) // 2))
    return canvas, {"cutout": False}


def product_texture(data: bytes, size: int = 64):
    return compose_texture(Image.open(io.BytesIO(data)), size)


# Used only when a brand photo has no usable cutout: a clean, brand-coloured
# can/bottle with the name on the label still looks better than extruding a
# rectangle of someone's table.
BRAND_LABELS = {
    "fosters": ("Foster's", (12, 62, 150), (235, 42, 52), "can"),
    "johnsmiths": ("John Smith's", (18, 44, 78), (226, 184, 74), "can"),
    "strongbow": ("Strongbow", (24, 22, 22), (206, 38, 52), "can"),
    "thatchers": ("Thatchers Gold", (226, 194, 74), (34, 82, 50), "can"),
    "londonpride": ("London Pride", (126, 24, 34), (226, 194, 122), "bottle"),
}
LABEL_FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
# Brands whose OFF photos are scene shots rather than cutouts.  They get a
# clean procedural label instead of an extruded rectangle of background.
LABEL_FALLBACK = {"johnsmiths", "strongbow", "thatchers", "londonpride"}


def _label_lines(name: str, limit: int = 10):
    words = name.upper().split()
    lines = []
    current = ""
    for word in words:
        candidate = f"{current} {word}".strip()
        if len(candidate) <= limit or not current:
            current = candidate
        else:
            lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines[:2]


def brand_label_texture(brand: str, size: int = 64) -> Image.Image:
    """Draw a clean branded can/bottle when the photo cutout is unusable."""
    name, base, accent, shape = BRAND_LABELS[brand]
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    if shape == "bottle":
        draw.polygon([(22, 20), (42, 20), (42, 58), (22, 58)], fill=base)
        draw.polygon([(27, 6), (37, 6), (37, 21), (27, 21)], fill=base)
        draw.rectangle((26, 3, 38, 8), fill=accent)
        draw.rectangle((23, 30, 41, 46), fill=(246, 240, 224, 255))
    else:
        draw.rounded_rectangle((20, 7, 44, 58), radius=4, fill=base)
        draw.rectangle((20, 7, 44, 13), fill=accent)
        draw.rectangle((21, 25, 43, 47), fill=(246, 240, 224, 255))
    try:
        font = ImageFont.truetype(LABEL_FONT, 9)
    except OSError:
        font = ImageFont.load_default()
    lines = _label_lines(name)
    top = 26 if shape == "can" else 31
    for index, line in enumerate(lines):
        box = draw.textbbox((0, 0), line, font=font)
        x = (size - (box[2] - box[0])) // 2
        draw.text((x, top + index * 10), line, font=font, fill=base)
    # A soft highlight down the left edge.
    draw.line((23, 16, 23, 54), fill=(255, 255, 255, 90), width=1)
    canvas.putalpha(
        canvas.getchannel("A").point(lambda alpha: 255 if alpha >= 128 else 0))
    return canvas


def procedural_pint(size: int = 64) -> Image.Image:
    """Draw a clean pint glass for the generic Guab/house pint.

    Used when the real pint photo cannot be cut out; a framed bar scene would
    extrude into an ugly rectangular slab.
    """
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    # Nonics-style glass: wider at the rim, tapering to the base.
    glass = [(18, 6), (46, 6), (40, 58), (24, 58)]
    draw.polygon(glass, fill=(205, 226, 235, 255))
    # Beer, leaving a glass edge visible.
    beer = [(21, 13), (43, 13), (37, 55), (27, 55)]
    draw.polygon(beer, fill=(214, 151, 42, 255))
    # Foam head.
    draw.polygon([(21, 10), (43, 10), (43, 15), (21, 15)], fill=(250, 246, 232, 255))
    draw.ellipse((20, 8, 44, 14), fill=(255, 252, 242, 255))
    # Glass highlights and base.
    draw.line((20, 8, 23, 56), fill=(240, 250, 255, 255), width=2)
    draw.line((44, 8, 38, 56), fill=(170, 196, 207, 255), width=2)
    draw.line((24, 59, 40, 59), fill=(170, 196, 207, 255), width=2)
    # A few bubbles.
    for x, y, r in ((31, 30, 1), (36, 42, 1), (29, 47, 1), (34, 22, 1)):
        draw.ellipse((x - r, y - r, x + r, y + r), fill=(240, 196, 110, 255))
    # Keep the sprite in the cutout pass.
    canvas.putalpha(canvas.getchannel("A").point(lambda alpha: 255 if alpha >= 128 else 0))
    return canvas


def pint_texture(size: int = 64):
    """The house pint is always drawn: real pint photos have bar backgrounds."""
    return procedural_pint(size), {"cutout": True, "procedural": True}


def describe_texture(info) -> str:
    if info.get("procedural"):
        return "procedural pint glass (drawn)"
    if info.get("label"):
        return "procedural branded label (photo had no usable cutout)"
    if info["cutout"]:
        return (
            f"cutout tolerance={info['tolerance']} "
            f"area={info['area']:.0%} bbox={info['bbox']}"
        )
    return "FALLBACK dark backdrop + frame (silhouette will look rectangular)"


def row_spans(image: Image.Image):
    """Return (row, first opaque pixel, one-past-last opaque pixel) per 4px row."""
    if image.size != (64, 64):
        raise ValueError(f"beer texture must be 64x64, got {image.size}")
    alpha = np.asarray(image.convert("RGBA"))[:, :, 3]
    spans = []
    for row in range(16):
        block = alpha[row * 4:(row + 1) * 4, :]
        opaque_columns = np.where((block >= ALPHA_THRESHOLD).any(axis=0))[0]
        if opaque_columns.size:
            first = int(opaque_columns[0])
            last = int(opaque_columns[-1]) + 1
            if last > first:
                spans.append((row, first, last))
    return spans


def build_beer_model(texture: Image.Image, texture_ref: str) -> dict:
    """Extrude every 4px texture row into a 1-unit-tall cuboid."""
    spans = row_spans(texture)
    if not spans:
        raise ValueError(f"texture {texture_ref} has no opaque pixels to extrude")

    elements = []
    for row, first, last in spans:
        span = last - first
        # Half the row width reads as a round can/bottle without turning a
        # narrow bottle into a box.  Depth is centred on z=8.
        depth = round(min(4.0, max(1.5, span * 0.5)), 3)
        x0 = round(first / 4.0, 3)
        x1 = round(last / 4.0, 3)
        y0 = 16 - (row + 1)
        y1 = 16 - row
        z0 = round(8.0 - depth / 2.0, 3)
        z1 = round(8.0 + depth / 2.0, 3)
        # Model UVs are always in 0..16 space, regardless of the PNG
        # resolution: the 64px texture maps to 16 units, so one texel is 0.25.
        uv_first = round(first * 16 / 64, 3)
        uv_last = round(last * 16 / 64, 3)
        uv_top = round(row * 4 * 16 / 64, 3)
        uv_bottom = round((row + 1) * 4 * 16 / 64, 3)
        # North is the readable front; the GUI transform can also show the
        # south or an east/west side, so all four vertical faces carry the label.
        faces = {
            # Keep the label readable from both sides; the GUI transform can
            # show either the north or south face depending on the item pose.
            "north": {
                "texture": "#beer",
                "uv": [uv_first, uv_top, uv_last, uv_bottom],
            },
            "south": {
                "texture": "#beer",
                "uv": [uv_first, uv_top, uv_last, uv_bottom],
            },
            # Wrap the same art around the sides instead of stretching one
            # edge column, so the can reads as printed all the way round.
            "east": {
                "texture": "#beer",
                "uv": [uv_first, uv_top, uv_last, uv_bottom],
            },
            "west": {
                "texture": "#beer",
                "uv": [uv_first, uv_top, uv_last, uv_bottom],
            },
            "up": {
                "texture": "#beer",
                "uv": [uv_first, uv_top, uv_last, uv_bottom],
            },
            "down": {
                "texture": "#beer",
                "uv": [uv_first, uv_top, uv_last, uv_bottom],
            },
        }
        elements.append({
            "from": [x0, y0, z0],
            "to": [x1, y1, z1],
            "faces": faces,
        })

    return {
        "gui_light": "side",
        "textures": {
            "beer": texture_ref,
            "particle": texture_ref,
        },
        "elements": elements,
        "display": ITEM_DISPLAY,
    }


def item_definition(model_ref: str) -> dict:
    return {"model": {"type": "minecraft:model", "model": model_ref}}


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def dartboard_texture(data: bytes, size: int = 128) -> Image.Image:
    image = Image.open(io.BytesIO(data)).convert("RGBA")
    box = image.getchannel("A").getbbox()
    if box:
        image = image.crop(box)
    canvas = Image.new("RGBA", (size, size), (22, 20, 18, 255))
    ImageDraw.Draw(canvas).rectangle(
        (0, 0, size - 1, size - 1), outline=(64, 52, 34, 255), width=2)
    image.thumbnail((size - 10, size - 10), Image.LANCZOS)
    canvas.alpha_composite(
        image, ((size - image.width) // 2, (size - image.height) // 2))
    return canvas


def split_resource_location(reference: str):
    namespace, separator, path = reference.partition(":")
    if not separator:
        return "minecraft", reference
    return namespace, path


def texture_path(pack_root: Path, reference: str) -> Path:
    namespace, path = split_resource_location(reference)
    return pack_root / "assets" / namespace / "textures" / f"{path}.png"


def model_path(pack_root: Path, reference: str) -> Path:
    namespace, path = split_resource_location(reference)
    return pack_root / "assets" / namespace / "models" / f"{path}.json"


def collect_model_references(value, found=None):
    """Recursively collect string values stored under a ``model`` key."""
    if found is None:
        found = []
    if isinstance(value, dict):
        for key, item in value.items():
            if key == "model" and isinstance(item, str):
                found.append(item)
            collect_model_references(item, found)
    elif isinstance(value, list):
        for item in value:
            collect_model_references(item, found)
    return found


def validate_pack(pack_root: Path) -> None:
    """Fail loudly before zipping when a pack contains broken references."""
    errors = []

    for item_file in sorted(pack_root.glob("assets/*/items/**/*.json")):
        try:
            definition = json.loads(item_file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            errors.append(f"{item_file}: cannot read item definition: {error}")
            continue
        references = list(dict.fromkeys(collect_model_references(definition)))
        for reference in references:
            namespace, _ = split_resource_location(reference)
            namespace_models = pack_root / "assets" / namespace / "models"
            if namespace != "guab" and not namespace_models.is_dir():
                # Vanilla model; its file lives in the client jar, not the pack.
                continue
            target = model_path(pack_root, reference)
            if not target.is_file():
                errors.append(
                    f"{item_file}: model {reference} is missing ({target})")

    for model_file in sorted(pack_root.glob("assets/*/models/**/*.json")):
        try:
            model = json.loads(model_file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            errors.append(f"{model_file}: cannot read model: {error}")
            continue

        textures = model.get("textures", {})
        if textures and not isinstance(textures, dict):
            errors.append(f"{model_file}: textures must be an object")
            textures = {}
        for key, reference in textures.items():
            if not isinstance(reference, str):
                errors.append(f"{model_file}: texture {key!r} is not a string")
                continue
            if reference.startswith("#"):
                if reference[1:] not in textures:
                    errors.append(
                        f"{model_file}: texture {key!r} aliases missing key "
                        f"{reference}")
                continue
            namespace, _ = split_resource_location(reference)
            namespace_textures = pack_root / "assets" / namespace / "textures"
            if namespace != "guab" and not namespace_textures.is_dir():
                # Vanilla texture; its file lives in the client jar.
                continue
            target = texture_path(pack_root, reference)
            if not target.is_file():
                errors.append(
                    f"{model_file}: texture {key!r} -> {reference} is missing "
                    f"({target})")

        for element_index, element in enumerate(model.get("elements", [])):
            if not isinstance(element, dict):
                errors.append(
                    f"{model_file}: element {element_index} is not an object")
                continue
            for end in ("from", "to"):
                coordinates = element.get(end)
                if not isinstance(coordinates, list) or len(coordinates) != 3:
                    errors.append(
                        f"{model_file}: element {element_index} {end} is not "
                        f"three coordinates")
                    continue
                for coordinate in coordinates:
                    if (not isinstance(coordinate, (int, float))
                            or isinstance(coordinate, bool)
                            or not np.isfinite(coordinate)):
                        errors.append(
                            f"{model_file}: element {element_index} {end} has "
                            f"non-numeric coordinate {coordinate!r}")
                    elif not 0 <= coordinate <= 16:
                        errors.append(
                            f"{model_file}: element {element_index} {end} "
                            f"coordinate {coordinate} is outside 0..16")
            faces = element.get("faces", {})
            if faces and not isinstance(faces, dict):
                errors.append(
                    f"{model_file}: element {element_index} faces is not an object")
                continue
            for face_name, face in faces.items():
                if not isinstance(face, dict):
                    errors.append(
                        f"{model_file}: element {element_index} face "
                        f"{face_name!r} is not an object")
                    continue
                face_texture = face.get("texture")
                if isinstance(face_texture, str) and face_texture.startswith("#"):
                    if face_texture[1:] not in textures:
                        errors.append(
                            f"{model_file}: element {element_index} face "
                            f"{face_name!r} references missing texture key "
                            f"{face_texture}")
                uv = face.get("uv")
                if uv is None:
                    continue
                if not isinstance(uv, list) or len(uv) != 4:
                    errors.append(
                        f"{model_file}: element {element_index} face "
                        f"{face_name!r} uv is not four numbers")
                    continue
                for value in uv:
                    if (not isinstance(value, (int, float))
                            or isinstance(value, bool)
                            or not np.isfinite(value)):
                        errors.append(
                            f"{model_file}: element {element_index} face "
                            f"{face_name!r} has non-numeric uv {value!r}")
                    elif not 0 <= value <= 16:
                        errors.append(
                            f"{model_file}: element {element_index} face "
                            f"{face_name!r} uv {value} is outside 0..16")

    if errors:
        details = "\n  ".join(errors)
        raise ValueError(
            f"resource pack validation failed for {pack_root}:\n  {details}")


def write_previews(pack_root: Path, destination: Path) -> None:
    """Write a flat front-face preview with a depth-offset silhouette."""
    destination.mkdir(parents=True, exist_ok=True)
    written = 0
    for model_file in sorted((pack_root / "assets/guab/models/item").glob("*.json")):
        model = json.loads(model_file.read_text(encoding="utf-8"))
        elements = model.get("elements")
        if not elements:
            continue
        textures = model.get("textures", {})
        reference = textures.get("beer") or textures.get("particle")
        if not isinstance(reference, str) or reference.startswith("#"):
            reference = next(
                (value for value in textures.values()
                 if isinstance(value, str) and not value.startswith("#")),
                None,
            )
        if not reference:
            continue
        source = texture_path(pack_root, reference)
        if not source.is_file():
            continue

        front = Image.open(source).convert("RGBA")
        max_depth = max(
            float(element["to"][2]) - float(element["from"][2])
            for element in elements)
        scale = 4
        offset = max(1, int(round(max_depth * 4 * scale * 0.5)))
        width = front.width * scale + offset
        height = front.height * scale + offset
        canvas = Image.new("RGBA", (width, height), (28, 28, 34, 255))

        scaled = front.resize((front.width * scale, front.height * scale),
                              Image.NEAREST)
        shadow = Image.new("RGBA", scaled.size, (12, 12, 16, 220))
        shadow.putalpha(
            scaled.getchannel("A").point(lambda alpha: min(200, alpha)))
        canvas.alpha_composite(shadow, (offset, offset))
        canvas.alpha_composite(scaled, (0, 0))
        canvas.save(destination / f"{model_file.stem}.png", optimize=True)
        written += 1
    print(f"wrote {written} previews to {destination}")


def write_zip(folder: Path, destination: Path) -> None:
    """Write a reproducible ZIP: sorted entries, fixed timestamps, fixed modes."""
    with zipfile.ZipFile(
            destination, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for base, directories, files in os.walk(folder):
            directories.sort()
            files.sort()
            for name in files:
                full = Path(base) / name
                relative = full.relative_to(folder).as_posix()
                info = zipfile.ZipInfo(relative, date_time=ZIP_DATE_TIME)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.create_system = 3
                info.external_attr = 0o644 << 16
                archive.writestr(
                    info, full.read_bytes(),
                    compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def sha1(path: Path) -> str:
    return hashlib.sha1(path.read_bytes()).hexdigest()


FRAME_ENTRY = re.compile(r"^assets/[^/]+/items/frame/(\d{4,})\.json$")


def validate_written_zip(path: Path) -> None:
    """Re-open a finished ZIP and fail if it is corrupt or structurally wrong."""
    with zipfile.ZipFile(path) as archive:
        broken = archive.testzip()
        if broken:
            raise ValueError(f"{path}: corrupt ZIP entry {broken}")
        names = archive.namelist()
        if "pack.mcmeta" not in names:
            raise ValueError(f"{path}: pack.mcmeta is missing")
        try:
            metadata = json.loads(archive.read("pack.mcmeta"))
        except json.JSONDecodeError as error:
            raise ValueError(f"{path}: pack.mcmeta is not valid JSON: {error}") from error
        if "pack" not in metadata:
            raise ValueError(f"{path}: pack.mcmeta has no pack object")
        for name in names:
            parts = Path(name).parts
            if name.startswith("/") or "\\" in name or ".." in parts:
                raise ValueError(f"{path}: unsafe entry path {name!r}")
        if not any(name.startswith("assets/") for name in names):
            raise ValueError(f"{path}: ZIP has no assets/ entries")


def frame_count_in_zip(path: Path) -> int:
    with zipfile.ZipFile(path) as archive:
        return sum(1 for name in archive.namelist() if FRAME_ENTRY.match(name))


def validate_pack_properties(path: Path) -> dict[str, str]:
    """Read pack.properties and check the values GuabRickroll relies on."""
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", "!")) or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        values[key.strip()] = value.strip()

    missing = [key for key in ("frames", "fps", "duration", "sha1") if key not in values]
    if missing:
        raise ValueError(f"{path}: missing keys {', '.join(missing)}")
    try:
        frames = int(values["frames"])
        fps = int(values["fps"])
        duration = int(values["duration"])
    except ValueError as error:
        raise ValueError(f"{path}: frames/fps/duration must be integers") from error
    if not 1 <= frames <= 200_000:
        raise ValueError(f"{path}: frames={frames} is out of range")
    if not 1 <= fps <= 20:
        raise ValueError(f"{path}: fps={fps} is out of range (1-20)")
    if not 1 <= duration <= 3600:
        raise ValueError(f"{path}: duration={duration} is out of range")
    if not re.fullmatch(r"[0-9a-fA-F]{40}", values["sha1"]):
        raise ValueError(f"{path}: sha1 is not a 40-character hex digest")
    return values


def update_pack_properties(path: Path, sha1_value: str, rickroll_zip: Path) -> None:
    """Atomically update the rickroll SHA-1 while preserving comments and other keys."""
    if not path.is_file():
        raise FileNotFoundError(path)

    values = validate_pack_properties(path)
    updates = {"sha1": sha1_value}
    archive_frames = frame_count_in_zip(rickroll_zip)
    if archive_frames and int(values["frames"]) != archive_frames:
        print(f"warning: {path} frames={values['frames']} but ZIP has {archive_frames}; updating frames")
        updates["frames"] = str(archive_frames)

    lines = path.read_text(encoding="utf-8").splitlines()
    seen: set[str] = set()
    output: list[str] = []
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", "!")) or "=" not in stripped:
            output.append(line)
            continue
        key = stripped.split("=", 1)[0].strip()
        if key in updates:
            output.append(f"{key}={updates[key]}")
            seen.add(key)
        else:
            output.append(line)
    for key, value in updates.items():
        if key not in seen:
            output.append(f"{key}={value}")

    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text("\n".join(output).rstrip("\n") + "\n", encoding="utf-8")
    os.replace(temporary, path)
    validate_pack_properties(path)


def report_properties(path: Path, sha1_value: str) -> None:
    print(f"rickroll pack.properties not updated ({path}).")
    print("Update it atomically with:")
    print(f"  sed -i 's/^sha1=.*/sha1={sha1_value}/' {shlex.quote(str(path))}")


def merge_sounds_json(pack_root: Path, fragment: Path) -> None:
    """Merge the HUD sound events into a pack's sounds.json, keeping existing entries."""
    target = pack_root / "assets" / "guab" / "sounds.json"
    data = {}
    if target.is_file():
        data = json.loads(target.read_text(encoding="utf-8"))
    data.update(json.loads(fragment.read_text(encoding="utf-8")))
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def prune_rickroll_duplicates(pack_root: Path) -> None:
    """Strip base-pack assets the rickroll pack no longer needs.

    The packs stack, so beer/item models, the HUD font and custom sounds belong
    only to guab-items.zip. Old rickroll bases may still contain them; removing
    them keeps the rickroll download to frames + rickroll.ogg.
    """
    keep = {"frame"}
    for relative in ("assets/guab/items", "assets/guab/models/item",
                     "assets/guab/textures/item"):
        directory = pack_root / relative
        if not directory.is_dir():
            continue
        for child in directory.iterdir():
            if child.name in keep:
                continue
            # A top-level frame file, if any, is not part of the generated layout.
            if child.is_dir():
                shutil.rmtree(child, ignore_errors=True)
            else:
                child.unlink(missing_ok=True)

    for relative in ("assets/guab/font", "assets/guab/textures/font"):
        shutil.rmtree(pack_root / relative, ignore_errors=True)

    sounds = pack_root / "assets" / "guab" / "sounds"
    if sounds.is_dir():
        for sound in sounds.glob("*.ogg"):
            if sound.name != "rickroll.ogg":
                sound.unlink(missing_ok=True)

    sounds_json = pack_root / "assets" / "guab" / "sounds.json"
    if sounds_json.is_file():
        try:
            data = json.loads(sounds_json.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            data = {}
        rickroll = data.get("rickroll") if isinstance(data, dict) else None
        if rickroll:
            sounds_json.write_text(
                json.dumps({"rickroll": rickroll}, separators=(",", ":")) + "\n",
                encoding="utf-8")


def install_hud_assets(pack_root: Path, hud_source: Path) -> None:
    """Copy the HUD font and custom sounds into an extracted pack root."""
    if not hud_source.is_dir():
        raise FileNotFoundError(f"HUD source directory not found: {hud_source}")
    for relative in ("assets/guab/font/hud.json", "assets/guab/textures/font/hud.png"):
        source = hud_source / relative
        if not source.is_file():
            raise FileNotFoundError(f"missing HUD asset: {source}")
        destination = pack_root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)

    sound_root = pack_root / "assets" / "guab" / "sounds"
    sound_root.mkdir(parents=True, exist_ok=True)
    sounds = sorted((hud_source / "sounds" / "guab").glob("*.ogg"))
    if not sounds:
        raise FileNotFoundError(f"no HUD sounds in {hud_source / 'sounds' / 'guab'}")
    for sound in sounds:
        shutil.copy2(sound, sound_root / sound.name)

    merge_sounds_json(pack_root, hud_source / "sounds.json")
    validate_guab_sounds(pack_root)

    # Generated item definitions, models and textures (cards, chips, UI icons,
    # milk, darts, prank items) live in the HUD source tree and belong in the
    # base guab-items pack. The rickroll pack deliberately does not duplicate them.
    for relative in ("assets/guab/items", "assets/guab/models/item", "assets/guab/textures/item"):
        source = hud_source / relative
        if not source.is_dir():
            continue
        destination = pack_root / relative
        destination.mkdir(parents=True, exist_ok=True)
        for asset in sorted(source.rglob("*")):
            if not asset.is_file():
                continue
            target = destination / asset.relative_to(source)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(asset, target)


def validate_guab_sounds(pack_root: Path) -> None:
    """Fail loudly when a guab sound event points at a file that is not in the pack."""
    target = pack_root / "assets" / "guab" / "sounds.json"
    data = json.loads(target.read_text(encoding="utf-8"))
    for event, definition in data.items():
        for sound in definition.get("sounds", []):
            name = sound["name"]
            namespace, _, path = name.partition(":")
            if not path:
                namespace, path = "guab", namespace
            if namespace != "guab":
                continue
            ogg = pack_root / "assets" / "guab" / "sounds" / f"{path}.ogg"
            if not ogg.is_file():
                raise FileNotFoundError(
                    f"sound event guab:{event} references missing file {ogg}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--items-base-dir", default=str(ITEMS_BASE_SOURCE),
        help="base pack directory (default: pack/items)")
    parser.add_argument(
        "--items-base", default=None,
        help="legacy guab-items.zip to extract instead of --items-base-dir")
    parser.add_argument("--rickroll-base", default="/opt/guab-server/resourcepack/rickroll.zip")
    parser.add_argument("--hud-source", default=str(HUD_SOURCE))
    parser.add_argument("--out", default="/tmp/guab-pack")
    parser.add_argument(
        "--preview", default=None, metavar="DIR",
        help="optional directory for flat front-face preview PNGs")
    parser.add_argument(
        "--pack-properties",
        default="/opt/guab-server/data/plugins/GuabRickroll/pack.properties",
        help="GuabRickroll/pack.properties to update with the new rickroll SHA-1")
    parser.add_argument(
        "--no-update-properties", action="store_true",
        help="print the SHA-1/manual command instead of writing pack.properties")
    args = parser.parse_args()

    hud_source = Path(args.hud_source)

    out = Path(args.out)
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)

    with tempfile.TemporaryDirectory() as temporary:
        temporary = Path(temporary)
        items = temporary / "items"
        rickroll = temporary / "rickroll"
        items.mkdir()
        rickroll.mkdir()
        items_base_dir = Path(args.items_base_dir)
        items_base_zip = Path(args.items_base) if args.items_base else None
        if items_base_zip is not None:
            if not items_base_zip.is_file():
                raise SystemExit(
                    f"items base ZIP not found: {items_base_zip} "
                    "(pass --items-base or omit it to use --items-base-dir)")
            with zipfile.ZipFile(items_base_zip) as archive:
                archive.extractall(items)
        elif items_base_dir.is_dir():
            shutil.copytree(items_base_dir, items, dirs_exist_ok=True)
        else:
            raise SystemExit(
                "items base not found: expected a directory at "
                f"{items_base_dir} or a ZIP via --items-base")
        # The rickroll zip is delivered stacked on top of guab-items, so it gets
        # no duplicated beer, dartboard, icon, HUD font or custom sounds.  Keep
        # the extracted base (frames, rickroll.ogg, pack metadata) as-is.
        with zipfile.ZipFile(args.rickroll_base) as archive:
            archive.extractall(rickroll)
        prune_rickroll_duplicates(rickroll)

        beer_dir = items / "assets/guab/textures/item"
        target_dir = items / "assets/minecraft/textures/block"
        item_models = items / "assets/guab/models/item"
        item_definitions = items / "assets/guab/items"
        for directory in (beer_dir, target_dir, item_models, item_definitions):
            directory.mkdir(parents=True, exist_ok=True)

        # Generic pint first, then every brand.  The texture, 3D model and item
        # definition all use the same base name (guab:item/beer[_brand]).
        pint_image, pint_info = pint_texture()
        pint_image.save(beer_dir / "beer.png", optimize=True)
        write_json(item_models / "beer.json",
                   build_beer_model(pint_image, "guab:item/beer"))
        write_json(item_definitions / "beer.json", item_definition("guab:item/beer"))
        print(f"beer: {describe_texture(pint_info)}")

        for name, url in BRAND_IMAGES.items():
            if name in LABEL_FALLBACK:
                texture = brand_label_texture(name)
                info = {"cutout": True, "label": True}
            else:
                texture, info = product_texture(download(url))
            texture.save(beer_dir / f"beer_{name}.png", optimize=True)
            write_json(item_models / f"beer_{name}.json",
                       build_beer_model(texture, f"guab:item/beer_{name}"))
            write_json(item_definitions / f"beer_{name}.json",
                       item_definition(f"guab:item/beer_{name}"))
            print(f"beer_{name}: {describe_texture(info)}")

        dartboard_texture(download(DARTBOARD_IMAGE)).save(
            target_dir / "target_side.png", optimize=True)
        shutil.copy2(target_dir / "target_side.png", beer_dir / "dartboard.png")
        write_json(item_models / "dartboard.json", {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": "guab:item/dartboard"},
        })
        write_json(item_definitions / "dartboard.json",
                   item_definition("guab:item/dartboard"))

        icon = Image.new("RGBA", (128, 128), (24, 22, 20, 255))
        icon.alpha_composite(
            Image.open(target_dir / "target_side.png").convert("RGBA").resize(
                (64, 64), Image.LANCZOS),
            (0, 32))
        icon.alpha_composite(
            Image.open(beer_dir / "beer.png").convert("RGBA").resize(
                (64, 64), Image.LANCZOS),
            (64, 32))
        icon.save(items / "pack.png", optimize=True)

        # HUD font and custom sounds belong to the stacked guab-items pack only.
        install_hud_assets(items, hud_source)

        # Validate the items pack, then whatever the rickroll base supplied
        # (its frame definitions/models).  rickroll no longer carries beer,
        # dartboard, HUD or custom-sound assets, so nothing here should expect
        # guab-items assets inside it.
        validate_pack(items)
        validate_pack(rickroll)

        if args.preview:
            write_previews(items, Path(args.preview))

        items_zip = out / "guab-items.zip"
        rickroll_zip = out / "rickroll.zip"
        write_zip(items, items_zip)
        write_zip(rickroll, rickroll_zip)

    validate_written_zip(items_zip)
    validate_written_zip(rickroll_zip)
    rickroll_sha1 = sha1(rickroll_zip)

    print(f"{items_zip}  {items_zip.stat().st_size} bytes  sha1={sha1(items_zip)}")
    print(f"{rickroll_zip}  {rickroll_zip.stat().st_size} bytes  sha1={rickroll_sha1}")

    properties = Path(args.pack_properties)
    if args.no_update_properties:
        report_properties(properties, rickroll_sha1)
    else:
        try:
            update_pack_properties(properties, rickroll_sha1, rickroll_zip)
        except (OSError, ValueError) as error:
            print(f"Could not update {properties}: {error}")
            report_properties(properties, rickroll_sha1)
        else:
            print(f"updated {properties} sha1={rickroll_sha1}")

    print("server.properties resource-pack-sha1 for guab-items.zip: "
          f"{sha1(items_zip)}")
    print("server.properties resource-pack-sha1 for rickroll.zip: "
          f"{rickroll_sha1}")


if __name__ == "__main__":
    main()
