#!/usr/bin/env python3
"""Generate the Guab HUD bitmap font, item assets and the custom sound effects.

Everything here is deterministic: the PNGs are drawn from fixed pixel maps and
the OGG files are synthesised from fixed waveforms with seeded noise sources,
then encoded with libvorbis. Re-running the script on the same
Pillow/ffmpeg versions produces the same bytes. No network access is needed.

Run from anywhere with Pillow, numpy, and ffmpeg installed:

    python3 tools/build-guab-hud-assets.py

Outputs:

    pack/hud/assets/guab/textures/font/hud.png
    pack/hud/assets/guab/textures/item/*.png
    pack/hud/assets/guab/models/item/*.json
    pack/hud/assets/guab/items/*.json
    pack/hud/sounds/guab/*.ogg
    pack/hud/sounds.json

Item assets follow the same 26.3 shape as tools/build-guab-items-pack.py:
``assets/guab/items/<id>.json`` points at ``guab:item/<id>``, which is a
``minecraft:item/generated`` model with a ``guab:item/<id>`` texture. The milk
pint is extruded into a small 3D element model instead.

The font JSON (pack/hud/assets/guab/font/hud.json) is hand-written and lists
the private-use codepoints in the same order as the glyphs below.
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
import tempfile
import wave
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
HUD = ROOT / "pack" / "hud"
SAMPLE_RATE = 44_100

GLYPHS = [
    # U+E000 - points/coin
    (
        "coin",
        {
            "o": (120, 78, 16, 255),
            "g": (244, 197, 66, 255),
            "h": (255, 243, 176, 255),
        },
        [
            "..oooo..",
            ".oggggo.",
            "ogghhggo",
            "ogghhggo",
            "oggggggo",
            "oggggggo",
            ".oggggo.",
            "..oooo..",
        ],
    ),
    # U+E001 - beer/pint
    (
        "beer",
        {
            "o": (92, 72, 44, 255),
            "f": (255, 247, 224, 255),
            "b": (232, 163, 61, 255),
            "h": (255, 217, 138, 255),
        },
        [
            "..ffff..",
            ".offffo.",
            ".obbbbo.",
            ".obhbbo.",
            ".obhbbo.",
            ".obbbbo.",
            "..obbo..",
            "..oooo..",
        ],
    ),
    # U+E002 - dart
    (
        "dart",
        {
            "r": (231, 76, 60, 255),
            "w": (217, 217, 217, 255),
            "t": (244, 197, 66, 255),
        },
        [
            ".....rr.",
            "....rrrr",
            "...wwwr.",
            "..wwww..",
            ".wwww...",
            "twww....",
            "tt......",
            "t.......",
        ],
    ),
    # U+E003 - heart
    (
        "heart",
        {
            "r": (226, 59, 59, 255),
            "h": (255, 138, 138, 255),
        },
        [
            ".rr..rr.",
            "rrrrrrrr",
            "rhhrrrrr",
            "rrrrrrrr",
            ".rrrrrr.",
            "..rrrr..",
            "...rr...",
            "........",
        ],
    ),
    # U+E004 - skull
    (
        "skull",
        {
            "w": (242, 242, 242, 255),
            "k": (34, 34, 34, 255),
        },
        [
            "..wwww..",
            ".wwwwww.",
            "wwwwwwww",
            "wwkwwkww",
            "wwkwwkww",
            "wwwwwwww",
            ".wkwkwk.",
            "..wwww..",
        ],
    ),
    # U+E005 - small G logo
    (
        "logo",
        {
            "o": (120, 78, 16, 255),
            "g": (244, 197, 66, 255),
        },
        [
            "..oooo..",
            ".oggggo.",
            "oggooooo",
            "oggo....",
            "oggo.ggo",
            "oggo.ggo",
            ".oggggo.",
            "..oooo..",
        ],
    ),
]


def render_font(destination: Path) -> None:
    for name, _, rows in GLYPHS:
        if len(rows) != 8 or any(len(row) != 8 for row in rows):
            raise ValueError(f"glyph {name} is not 8x8")

    width = 8 * len(GLYPHS)
    canvas = Image.new("RGBA", (width, 8), (0, 0, 0, 0))
    pixels = canvas.load()
    for index, (_, palette, rows) in enumerate(GLYPHS):
        for y, row in enumerate(rows):
            for x, symbol in enumerate(row):
                if symbol == ".":
                    continue
                pixels[index * 8 + x, y] = palette[symbol]
    destination.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(destination, format="PNG", optimize=False, compress_level=9)


# ---------------------------------------------------------------------------
# Item assets: playing cards, casino chips, menu icons and pub props.
# ---------------------------------------------------------------------------

ITEM_ASSETS = HUD / "assets" / "guab"
ITEM_TEXTURES = ITEM_ASSETS / "textures" / "item"
ITEM_MODELS = ITEM_ASSETS / "models" / "item"
ITEM_DEFINITIONS = ITEM_ASSETS / "items"

# Suit letter, label and ink colour. Diamonds/hearts are red, spades/clubs are
# near-black so the rank reads at 64px in a GUI.
SUITS = (
    ("s", "spades", (32, 32, 38, 255)),
    ("h", "hearts", (192, 30, 46, 255)),
    ("d", "diamonds", (192, 30, 46, 255)),
    ("c", "clubs", (32, 32, 38, 255)),
)
RANKS = ("a", "2", "3", "4", "5", "6", "7", "8", "9", "10", "j", "q", "k")

CARD_INK = (28, 28, 32, 255)
CARD_PAPER = (250, 249, 244, 255)
CARD_EDGE = (78, 74, 66, 255)
CARD_INNER = (214, 208, 194, 255)

# A 5x7 pixel font keeps the cards, chips and icons independent of whatever
# system fonts happen to be installed. Only the characters we draw are here.
FONT_5X7 = {
    "0": ["01110", "10001", "10011", "10101", "11001", "10001", "01110"],
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "2": ["01110", "10001", "00001", "00010", "00100", "01000", "11111"],
    "3": ["11111", "00010", "00100", "00010", "00001", "10001", "01110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "5": ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    "6": ["00110", "01000", "10000", "11110", "10001", "10001", "01110"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    "8": ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
    "9": ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
    "a": ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    "j": ["00111", "00010", "00010", "00010", "00010", "10010", "01100"],
    "q": ["01110", "10001", "10001", "10001", "10101", "10010", "01101"],
    "k": ["10001", "10010", "10100", "11000", "10100", "10010", "10001"],
}

# Vanilla block-item display transforms for the extruded milk pint.
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

# A held dart is drawn diagonally, so it only needs hand transforms.
DART_HAND_DISPLAY = {
    "thirdperson_righthand": {
        "rotation": [0, -90, 25],
        "translation": [1.13, 3.2, 1.13],
        "scale": [0.68, 0.68, 0.68],
    },
    "firstperson_righthand": {
        "rotation": [0, -90, 25],
        "translation": [1.13, 3.2, 1.13],
        "scale": [0.68, 0.68, 0.68],
    },
}


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def save_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=False, compress_level=9)


def item_definition(model_ref: str) -> dict:
    return {"model": {"type": "minecraft:model", "model": model_ref}}


def generated_model(texture_ref: str, display: dict | None = None) -> dict:
    model = {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": texture_ref},
    }
    if display is not None:
        model["display"] = display
    return model


def text_image(text: str, scale: int, colour) -> Image.Image:
    """Render ``text`` with the built-in 5x7 font onto a transparent canvas."""
    if not text:
        return Image.new("RGBA", (1, 1), (0, 0, 0, 0))
    width = (len(text) * 6 - 1) * scale
    image = Image.new("RGBA", (width, 7 * scale), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    for index, character in enumerate(text.lower()):
        glyph = FONT_5X7.get(character)
        if glyph is None:
            continue
        for row, bits in enumerate(glyph):
            for column, bit in enumerate(bits):
                if bit == "1":
                    x = (index * 6 + column) * scale
                    y = row * scale
                    draw.rectangle(
                        (x, y, x + scale - 1, y + scale - 1), fill=colour)
    return image


def draw_suit(draw: ImageDraw.ImageDraw, suit: str, cx: float, cy: float,
              size: float, colour) -> None:
    """Draw a small pip centred on (cx, cy) with the given half-height."""
    r = size
    if suit == "d":
        draw.polygon(
            [(cx, cy - r), (cx + r * 0.72, cy), (cx, cy + r),
             (cx - r * 0.72, cy)],
            fill=colour)
        draw.polygon(
            [(cx, cy - r * 0.62), (cx + r * 0.44, cy), (cx, cy + r * 0.62),
             (cx - r * 0.44, cy)],
            fill=(255, 255, 255, 70))
        return
    if suit == "h":
        circle = r * 0.52
        draw.ellipse((cx - r * 0.72, cy - r * 0.78,
                      cx - r * 0.72 + circle * 2, cy - r * 0.78 + circle * 2),
                     fill=colour)
        draw.ellipse((cx + r * 0.72 - circle * 2, cy - r * 0.78,
                      cx + r * 0.72, cy - r * 0.78 + circle * 2), fill=colour)
        draw.polygon(
            [(cx - r * 0.93, cy - r * 0.15), (cx + r * 0.93, cy - r * 0.15),
             (cx, cy + r)],
            fill=colour)
        return
    if suit == "s":
        # A heart upside down, with a short stem.
        circle = r * 0.52
        draw.ellipse((cx - r * 0.72, cy - r * 0.02,
                      cx - r * 0.72 + circle * 2, cy - r * 0.02 + circle * 2),
                     fill=colour)
        draw.ellipse((cx + r * 0.72 - circle * 2, cy - r * 0.02,
                      cx + r * 0.72, cy - r * 0.02 + circle * 2), fill=colour)
        draw.polygon(
            [(cx - r * 0.93, cy + r * 0.12), (cx + r * 0.93, cy + r * 0.12),
             (cx, cy - r)],
            fill=colour)
        draw.polygon(
            [(cx - r * 0.16, cy + r * 0.4), (cx + r * 0.16, cy + r * 0.4),
             (cx + r * 0.34, cy + r), (cx - r * 0.34, cy + r)],
            fill=colour)
        return
    # Clubs: three lobes plus a stem.
    lobe = r * 0.46
    for ox, oy in ((0.0, -0.52), (-0.50, 0.06), (0.50, 0.06)):
        draw.ellipse((cx + ox * r - lobe, cy + oy * r - lobe,
                      cx + ox * r + lobe, cy + oy * r + lobe), fill=colour)
    draw.polygon(
        [(cx - r * 0.16, cy + r * 0.36), (cx + r * 0.16, cy + r * 0.36),
         (cx + r * 0.34, cy + r), (cx - r * 0.34, cy + r)],
        fill=colour)


def card_texture(rank: str, suit: str, colour) -> Image.Image:
    image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((1, 1, 62, 62), radius=6, fill=CARD_PAPER,
                           outline=CARD_EDGE, width=2)
    draw.rounded_rectangle((4, 4, 59, 59), radius=4, outline=CARD_INNER,
                           width=1)
    # Rank in the top-left and, rotated, in the bottom-right.
    top = text_image(rank, 2, colour)
    image.paste(top, (6, 6), top)
    rotated = top.rotate(180)
    image.paste(rotated, (58 - rotated.width, 58 - rotated.height), rotated)
    draw_suit(draw, suit, 32, 33, 14, colour)
    return image


def card_back_texture() -> Image.Image:
    image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((1, 1, 62, 62), radius=6, fill=(30, 52, 106, 255),
                           outline=(14, 26, 60, 255), width=2)
    draw.rounded_rectangle((5, 5, 58, 58), radius=4,
                           outline=(224, 196, 112, 255), width=2)
    # A deterministic diagonal lattice clipped to the inner card.
    for row in range(-1, 8):
        for column in range(-1, 8):
            cx = 6 + column * 9 + (4 if row % 2 else 0)
            cy = 6 + row * 9
            if 8 <= cx <= 55 and 8 <= cy <= 55:
                draw.polygon(
                    [(cx, cy - 4), (cx + 4, cy), (cx, cy + 4), (cx - 4, cy)],
                    outline=(120, 154, 214, 255), fill=(44, 74, 138, 255))
    return image


def chip_texture(label: str, base, accent) -> Image.Image:
    image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.ellipse((2, 2, 61, 61), fill=(22, 22, 24, 255))
    draw.ellipse((4, 4, 59, 59), fill=base, outline=(20, 20, 22, 255), width=2)
    # Edge spots so the chip reads as a casino chip rather than a coin.
    for index in range(12):
        angle = math.radians(index * 30.0)
        cx = 32 + 26.0 * math.cos(angle)
        cy = 32 + 26.0 * math.sin(angle)
        draw.ellipse((cx - 2.6, cy - 2.6, cx + 2.6, cy + 2.6), fill=accent)
    draw.ellipse((11, 11, 52, 52), fill=(250, 247, 238, 255),
                 outline=(60, 56, 50, 255), width=1)
    draw.ellipse((14, 14, 49, 49), outline=accent, width=2)
    scale = 2 if len(label) <= 2 else 1
    text = text_image(label, scale, base)
    image.paste(text, ((64 - text.width) // 2, (64 - text.height) // 2), text)
    return image


def ui_icon(kind: str) -> Image.Image:
    image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    fills = {
        "hit": ((44, 150, 84, 255), (22, 92, 50, 255)),
        "stand": ((205, 140, 34, 255), (128, 82, 16, 255)),
        "double": ((112, 74, 184, 255), (66, 40, 118, 255)),
    }
    fill, edge = fills[kind]
    draw.ellipse((3, 3, 60, 60), fill=(20, 20, 22, 255))
    draw.ellipse((5, 5, 58, 58), fill=fill, outline=edge, width=2)
    white = (252, 250, 244, 255)
    if kind == "hit":
        draw.rounded_rectangle((27, 16, 36, 47), radius=3, fill=white)
        draw.rounded_rectangle((16, 27, 47, 36), radius=3, fill=white)
    elif kind == "stand":
        # Palm plus three fingers and a thumb: "stop".
        draw.rounded_rectangle((23, 33, 41, 50), radius=5, fill=white)
        for x in (24, 30, 36):
            draw.rounded_rectangle((x, 20, x + 4, 36), radius=2, fill=white)
        draw.rounded_rectangle((17, 32, 24, 42), radius=3, fill=white)
    else:
        # Two overlapping cards: "double down".
        draw.rounded_rectangle((17, 22, 35, 47), radius=3, fill=white,
                               outline=edge, width=1)
        draw.rounded_rectangle((29, 17, 47, 42), radius=3, fill=white,
                               outline=edge, width=1)
        draw_suit(draw, "d", 38, 29, 6, (192, 30, 46, 255))
    return image


def milk_texture() -> Image.Image:
    """A clean white pint glass for the sobering-up milk."""
    image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    # Nonics-style glass, wider at the rim and tapering to the base.
    draw.polygon([(18, 5), (46, 5), (40, 58), (24, 58)],
                 fill=(224, 232, 238, 255))
    # Milk, leaving a glass edge visible all round.
    draw.polygon([(21, 12), (43, 12), (37, 55), (27, 55)],
                 fill=(250, 250, 246, 255))
    # Cream line and rim.
    draw.polygon([(21, 11), (43, 11), (43, 15), (21, 15)],
                 fill=(246, 240, 222, 255))
    draw.ellipse((19, 6, 45, 14), fill=(252, 252, 249, 255),
                 outline=(210, 218, 224, 255))
    draw.line((20, 7, 24, 56), fill=(244, 249, 252, 255), width=2)
    draw.line((44, 7, 38, 56), fill=(186, 200, 210, 255), width=2)
    draw.line((24, 59, 40, 59), fill=(186, 200, 210, 255), width=2)
    # A few milk bubbles.
    for x, y, r in ((30, 27, 1), (35, 39, 1), (29, 46, 1), (34, 21, 1)):
        draw.ellipse((x - r, y - r, x + r, y + r), fill=(236, 240, 242, 255))
    image.putalpha(
        image.getchannel("A").point(lambda alpha: 255 if alpha >= 128 else 0))
    return image


def wind_charge_texture() -> Image.Image:
    image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.ellipse((7, 7, 56, 56), fill=(206, 236, 255, 255),
                 outline=(96, 164, 220, 255), width=2)
    draw.ellipse((14, 14, 49, 49), outline=(150, 204, 244, 255), width=2)
    draw.arc((12, 12, 51, 51), 20, 200, fill=(255, 255, 255, 255), width=3)
    draw.arc((20, 20, 43, 43), 200, 20, fill=(118, 182, 232, 255), width=2)
    draw.ellipse((25, 25, 38, 38), fill=(244, 252, 255, 255))
    return image


def jump_feather_texture() -> Image.Image:
    image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    # Quill running from bottom-left to top-right.
    for offset in range(0, 9):
        x = 10 + offset * 5
        y = 54 - offset * 5
        draw.line((x, y, x + 8, y - 14), fill=(236, 248, 255, 255), width=3)
        draw.line((x, y, x + 2, y - 16), fill=(126, 196, 238, 255), width=1)
    draw.line((9, 56, 51, 12), fill=(96, 150, 190, 255), width=2)
    draw.line((8, 57, 3, 62), fill=(222, 236, 244, 255), width=2)
    return image


def dart_texture() -> Image.Image:
    image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.line((17, 47, 50, 12), fill=(210, 214, 220, 255), width=4)
    draw.line((20, 50, 53, 15), fill=(160, 166, 174, 255), width=1)
    # Steel tip.
    draw.polygon([(50, 12), (58, 4), (53, 15)], fill=(198, 204, 212, 255))
    # Red flight at the tail.
    draw.polygon([(10, 54), (22, 42), (26, 52), (18, 58)],
                 fill=(214, 50, 58, 255))
    draw.polygon([(12, 52), (20, 44), (14, 58)], fill=(246, 236, 220, 255))
    # Grip rings.
    for step in range(3):
        x = 30 + step * 4
        draw.line((x, 36 - step * 4, x + 4, 40 - step * 4),
                  fill=(54, 54, 60, 255), width=2)
    return image


def dart_hand_texture() -> Image.Image:
    """A slightly angled dart so the held item does not mirror the HUD icon."""
    return dart_texture().rotate(10, resample=Image.NEAREST, expand=False)


def row_spans(image: Image.Image):
    """Return (row, first, last) for each 4px row of a 64x64 sprite."""
    if image.size != (64, 64):
        raise ValueError(f"texture must be 64x64, got {image.size}")
    alpha = np.asarray(image.convert("RGBA"))[:, :, 3]
    spans = []
    for row in range(16):
        block = alpha[row * 4:(row + 1) * 4, :]
        columns = np.where((block >= 24).any(axis=0))[0]
        if columns.size:
            first = int(columns[0])
            last = int(columns[-1]) + 1
            if last > first:
                spans.append((row, first, last))
    return spans


def extrude_model(texture: Image.Image, texture_ref: str) -> dict:
    """Extrude each 4px texture row into a 1-unit cuboid, like the beer pint."""
    spans = row_spans(texture)
    if not spans:
        raise ValueError(f"texture {texture_ref} has no opaque pixels")
    elements = []
    for row, first, last in spans:
        span = last - first
        depth = round(min(4.0, max(1.5, span * 0.5)), 3)
        x0 = round(first / 4.0, 3)
        x1 = round(last / 4.0, 3)
        y0 = 16 - (row + 1)
        y1 = 16 - row
        z0 = round(8.0 - depth / 2.0, 3)
        z1 = round(8.0 + depth / 2.0, 3)
        uv_first = round(first * 16 / 64, 3)
        uv_last = round(last * 16 / 64, 3)
        uv_top = round(row * 4 * 16 / 64, 3)
        uv_bottom = round((row + 1) * 4 * 16 / 64, 3)
        faces = {
            face: {"texture": "#item",
                   "uv": [uv_first, uv_top, uv_last, uv_bottom]}
            for face in ("north", "south", "east", "west", "up", "down")
        }
        elements.append({
            "from": [x0, y0, z0],
            "to": [x1, y1, z1],
            "faces": faces,
        })
    return {
        "gui_light": "side",
        "textures": {"item": texture_ref, "particle": texture_ref},
        "elements": elements,
        "display": ITEM_DISPLAY,
    }


CARD_IDS = ["card_back"] + [
    f"card_{rank}{suit}" for suit, _, _ in SUITS for rank in RANKS
]
CHIP_IDS = ["chip_10", "chip_100", "chip_500", "chip_1000", "chip_10000"]
UI_IDS = ["ui_hit", "ui_stand", "ui_double"]
PROP_IDS = ["milk", "wind_charge", "jump_feather", "dart", "dart_hand"]
ITEM_IDS = CARD_IDS + CHIP_IDS + UI_IDS + PROP_IDS


def write_item_assets(identifier: str, texture: Image.Image,
                      model: dict) -> None:
    save_png(texture, ITEM_TEXTURES / f"{identifier}.png")
    write_json(ITEM_MODELS / f"{identifier}.json", model)
    write_json(ITEM_DEFINITIONS / f"{identifier}.json",
               item_definition(f"guab:item/{identifier}"))


def render_items() -> dict[str, int]:
    for directory in (ITEM_TEXTURES, ITEM_MODELS, ITEM_DEFINITIONS):
        directory.mkdir(parents=True, exist_ok=True)

    for suit, _, colour in SUITS:
        for rank in RANKS:
            identifier = f"card_{rank}{suit}"
            write_item_assets(
                identifier, card_texture(rank, suit, colour),
                generated_model(f"guab:item/{identifier}"))
    write_item_assets("card_back", card_back_texture(),
                      generated_model("guab:item/card_back"))

    chips = (
        ("chip_10", "10", (238, 238, 240, 255), (70, 120, 190, 255)),
        ("chip_100", "100", (192, 40, 48, 255), (246, 224, 208, 255)),
        ("chip_500", "500", (36, 132, 76, 255), (238, 246, 224, 255)),
        ("chip_1000", "1K", (42, 42, 48, 255), (226, 204, 122, 255)),
        ("chip_10000", "10K", (196, 154, 44, 255), (64, 46, 18, 255)),
    )
    for identifier, label, base, accent in chips:
        write_item_assets(identifier, chip_texture(label, base, accent),
                          generated_model(f"guab:item/{identifier}"))

    for identifier in UI_IDS:
        write_item_assets(identifier, ui_icon(identifier.split("_", 1)[1]),
                          generated_model(f"guab:item/{identifier}"))

    milk = milk_texture()
    write_item_assets("milk", milk, extrude_model(milk, "guab:item/milk"))
    write_item_assets("wind_charge", wind_charge_texture(),
                      generated_model("guab:item/wind_charge"))
    write_item_assets("jump_feather", jump_feather_texture(),
                      generated_model("guab:item/jump_feather"))
    write_item_assets("dart", dart_texture(),
                      generated_model("guab:item/dart"))
    write_item_assets("dart_hand", dart_hand_texture(),
                      generated_model("guab:item/dart_hand", DART_HAND_DISPLAY))

    return {
        "cards": len(CARD_IDS),
        "chips": len(CHIP_IDS),
        "icons": len(UI_IDS),
        "props": len(PROP_IDS),
        "files": len(ITEM_IDS) * 3,
    }


def validate_item_assets() -> None:
    """Fail before the pack is built when a generated reference is broken."""
    for identifier in ITEM_IDS:
        definition_file = ITEM_DEFINITIONS / f"{identifier}.json"
        model_file = ITEM_MODELS / f"{identifier}.json"
        texture_file = ITEM_TEXTURES / f"{identifier}.png"
        for path in (definition_file, model_file, texture_file):
            if not path.is_file():
                raise FileNotFoundError(f"missing generated asset {path}")

        try:
            definition = json.loads(definition_file.read_text(encoding="utf-8"))
            model = json.loads(model_file.read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            raise ValueError(f"invalid generated JSON: {error}") from error
        expected = f"guab:item/{identifier}"
        if definition.get("model", {}).get("model") != expected:
            raise ValueError(
                f"{definition_file}: model must reference {expected}")
        textures = model.get("textures")
        if not isinstance(textures, dict):
            raise ValueError(f"{model_file}: model has no textures")
        references = [
            value for value in textures.values()
            if isinstance(value, str) and not value.startswith("#")
        ]
        if not references:
            raise ValueError(f"{model_file}: no concrete texture reference")
        for reference in references:
            namespace, _, path = reference.partition(":")
            if namespace != "guab" or not path.startswith("item/"):
                continue
            target = ITEM_TEXTURES / f"{path.removeprefix('item/')}.png"
            if not target.is_file():
                raise FileNotFoundError(
                    f"{model_file}: texture {reference} is missing ({target})")


def envelope(length: int, attack: float = 0.004, release: float = 0.08) -> np.ndarray:
    attack_samples = min(length, max(1, int(attack * SAMPLE_RATE)))
    release_samples = min(length - attack_samples, max(1, int(release * SAMPLE_RATE)))
    shape = np.ones(length, dtype=np.float64)
    shape[:attack_samples] = np.linspace(0.0, 1.0, attack_samples)
    if release_samples > 0:
        shape[-release_samples:] *= np.linspace(1.0, 0.0, release_samples)
    return shape


def silence(seconds: float) -> np.ndarray:
    return np.zeros(max(1, int(seconds * SAMPLE_RATE)), dtype=np.float64)


def sequence(*parts: np.ndarray) -> np.ndarray:
    return np.concatenate(parts) if parts else silence(0.01)


def tone(frequency: float, seconds: float, volume: float, decay: float = 6.0,
         harmonics: tuple[tuple[float, float], ...] = ((1.0, 1.0), (2.0, 0.22), (3.0, 0.08))) -> np.ndarray:
    """A soft bell tone made from sine partials, not a harsh square wave."""
    length = int(seconds * SAMPLE_RATE)
    time = np.arange(length) / SAMPLE_RATE
    wave_shape = np.zeros(length, dtype=np.float64)
    for multiple, amplitude in harmonics:
        wave_shape += amplitude * np.sin(2.0 * math.pi * frequency * multiple * time)
    wave_shape /= max(1e-9, sum(amplitude for _, amplitude in harmonics))
    shape = np.exp(-decay * time) * envelope(length, attack=0.006, release=0.06)
    return wave_shape * shape * volume


def chime(notes: tuple[float, ...], gap: float, volume: float, decay: float) -> np.ndarray:
    parts = []
    for index, frequency in enumerate(notes):
        if index:
            parts.append(silence(gap))
        duration = 0.34 if index == len(notes) - 1 else 0.16
        parts.append(tone(frequency, duration, volume, decay=decay))
    return sequence(*parts)


def quiz_correct() -> np.ndarray:
    return chime((1318.51, 1567.98), 0.015, 0.20, 7.0)


def one_eighty() -> np.ndarray:
    return sequence(
        silence(0.01),
        tone(783.99, 0.11, 0.18, 8.0),
        tone(987.77, 0.11, 0.18, 8.0),
        tone(1174.66, 0.13, 0.19, 8.0),
        tone(1567.98, 0.40, 0.22, 4.0,
             harmonics=((1.0, 1.0), (2.0, 0.30), (3.0, 0.12), (4.0, 0.05))),
        tone(1975.53, 0.40, 0.08, 4.0),
    )


def beer_pour() -> np.ndarray:
    rng = np.random.default_rng(180)
    length = int(0.85 * SAMPLE_RATE)
    noise = rng.uniform(-1.0, 1.0, length)
    # One-pole low-pass keeps the pour soft and fizzy instead of hissy.
    filtered = np.empty_like(noise)
    state = 0.0
    for index, sample in enumerate(noise):
        state += 0.12 * (sample - state)
        filtered[index] = state
    filtered *= np.linspace(0.9, 0.2, length)
    filtered *= envelope(length, attack=0.03, release=0.32)
    filtered *= 0.30

    time = np.arange(length) / SAMPLE_RATE
    pitch = np.linspace(240.0, 70.0, length)
    phase = np.cumsum(2.0 * math.pi * pitch / SAMPLE_RATE)
    glug = np.sin(phase) * np.linspace(0.16, 0.02, length)
    glug *= np.sin(np.minimum(1.0, time * 7.0) * math.pi)
    clink = tone(2093.0, 0.10, 0.10, decay=18.0)
    return sequence(silence(0.01), filtered + glug, silence(0.02), clink)


def milestone() -> np.ndarray:
    return sequence(
        tone(1046.50, 0.12, 0.18, 9.0),
        silence(0.01),
        tone(1318.51, 0.12, 0.18, 9.0),
        silence(0.01),
        tone(1567.98, 0.38, 0.20, 4.5),
        tone(2093.00, 0.38, 0.06, 4.5),
    )


def low_pass_noise(seconds: float, seed: int, smoothing: float = 0.12,
                   volume: float = 0.2, attack: float = 0.01,
                   release: float = 0.2) -> np.ndarray:
    """Seeded one-pole low-passed noise, used for thuds and swishes."""
    rng = np.random.default_rng(seed)
    length = max(1, int(seconds * SAMPLE_RATE))
    noise = rng.uniform(-1.0, 1.0, length)
    filtered = np.empty_like(noise)
    state = 0.0
    for index, sample in enumerate(noise):
        state += smoothing * (sample - state)
        filtered[index] = state
    filtered *= envelope(length, attack=attack, release=release)
    return filtered * volume


def sweep(start: float, stop: float, seconds: float, volume: float,
          decay: float = 5.0) -> np.ndarray:
    """A sine chirp from ``start`` to ``stop`` Hz."""
    length = max(1, int(seconds * SAMPLE_RATE))
    pitch = np.linspace(start, stop, length)
    phase = np.cumsum(2.0 * math.pi * pitch / SAMPLE_RATE)
    shape = np.exp(-decay * np.arange(length) / SAMPLE_RATE)
    shape *= envelope(length, attack=0.004, release=0.06)
    return np.sin(phase) * shape * volume


def card_flip() -> np.ndarray:
    return sequence(
        silence(0.008),
        low_pass_noise(0.13, seed=301, smoothing=0.35, volume=0.20,
                       attack=0.01, release=0.09),
        tone(1760.0, 0.035, 0.05, decay=38.0),
    )


def chip_click() -> np.ndarray:
    return sequence(
        tone(2469.0, 0.025, 0.14, decay=46.0),
        silence(0.012),
        tone(3136.0, 0.035, 0.10, decay=42.0),
        silence(0.02),
        tone(3520.0, 0.05, 0.05, decay=30.0),
    )


def blackjack() -> np.ndarray:
    winner = chime((523.25, 659.25, 783.99), 0.02, 0.19, 7.0)
    return sequence(
        tone(261.63, 0.23, 0.10, decay=5.0),
        winner,
        tone(1046.50, 0.42, 0.07, decay=4.0),
    )


def dart_thud() -> np.ndarray:
    return sequence(
        low_pass_noise(0.18, seed=411, smoothing=0.08, volume=0.34,
                       attack=0.002, release=0.14),
        tone(96.0, 0.20, 0.38, decay=16.0,
             harmonics=((1.0, 1.0), (2.0, 0.12))),
        silence(0.02),
        tone(1500.0, 0.04, 0.04, decay=34.0),
    )


def dart_miss() -> np.ndarray:
    return sequence(
        low_pass_noise(0.16, seed=412, smoothing=0.55, volume=0.16,
                       attack=0.005, release=0.12),
        tone(440.0, 0.07, 0.10, decay=18.0),
        silence(0.03),
        tone(311.13, 0.11, 0.08, decay=14.0),
    )


def pit_kill() -> np.ndarray:
    return sequence(
        low_pass_noise(0.30, seed=413, smoothing=0.05, volume=0.30,
                       attack=0.004, release=0.24),
        tone(65.41, 0.55, 0.34, decay=3.2,
             harmonics=((1.0, 1.0), (2.0, 0.24), (3.0, 0.10))),
        tone(98.0, 0.45, 0.16, decay=3.6),
        tone(196.0, 0.22, 0.07, decay=5.0),
    )


def drink_clink() -> np.ndarray:
    return sequence(
        tone(2093.0, 0.09, 0.14, decay=22.0,
             harmonics=((1.0, 1.0), (2.72, 0.30), (5.4, 0.12))),
        silence(0.015),
        tone(2637.02, 0.13, 0.11, decay=18.0,
             harmonics=((1.0, 1.0), (2.76, 0.26), (5.8, 0.10))),
    )


def hiccup() -> np.ndarray:
    return sequence(
        sweep(290.0, 720.0, 0.085, 0.24, decay=11.0),
        silence(0.02),
        sweep(360.0, 540.0, 0.055, 0.12, decay=18.0),
    )


SOUNDS = {
    "quiz_correct": quiz_correct,
    "one_eighty": one_eighty,
    "beer_pour": beer_pour,
    "milestone": milestone,
    "card_flip": card_flip,
    "chip_click": chip_click,
    "blackjack": blackjack,
    "dart_thud": dart_thud,
    "dart_miss": dart_miss,
    "pit_kill": pit_kill,
    "drink_clink": drink_clink,
    "hiccup": hiccup,
}


def write_wav(path: Path, samples: np.ndarray) -> None:
    peak = float(np.max(np.abs(samples))) if samples.size else 0.0
    if peak > 0:
        samples = samples * (0.65 / peak)
    # A short final fade avoids a click at the end of the OGG.
    fade = min(len(samples), int(0.01 * SAMPLE_RATE))
    if fade:
        samples[-fade:] *= np.linspace(1.0, 0.0, fade)
    pcm = np.clip(samples * 32767.0, -32768, 32767).astype("<i2")
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SAMPLE_RATE)
        handle.writeframes(pcm.tobytes())


def encode_ogg(wav_path: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            "ffmpeg",
            "-hide_banner",
            "-loglevel", "error",
            "-y",
            "-i", str(wav_path),
            "-map_metadata", "-1",
            "-fflags", "+bitexact",
            "-c:a", "libvorbis",
            "-q:a", "4",
            "-ar", str(SAMPLE_RATE),
            "-ac", "1",
            str(destination),
        ],
        check=True,
    )


def write_sounds_json() -> None:
    """Keep sounds.json in sync with the event factories above."""
    fragment = {
        event: {"sounds": [{"name": f"guab:{event}"}]}
        for event in SOUNDS
    }
    write_json(HUD / "sounds.json", fragment)


def validate_source() -> None:
    """Check the checked-in font/sound definitions match the generated files."""
    font_json = json.loads((HUD / "assets/guab/font/hud.json").read_text(encoding="utf-8"))
    providers = font_json.get("providers", [])
    if len(providers) != 1 or providers[0].get("type") != "bitmap":
        raise ValueError("hud.json must contain exactly one bitmap provider")
    chars = "".join(providers[0].get("chars", []))
    if len(chars) != len(GLYPHS):
        raise ValueError(f"hud.json maps {len(chars)} glyphs, expected {len(GLYPHS)}")

    fragment = json.loads((HUD / "sounds.json").read_text(encoding="utf-8"))
    if set(fragment) != set(SOUNDS):
        missing = sorted(set(SOUNDS) - set(fragment))
        extra = sorted(set(fragment) - set(SOUNDS))
        raise ValueError(
            f"sounds.json events are out of sync (missing={missing}, extra={extra})")
    for event, definition in fragment.items():
        for sound in definition.get("sounds", []):
            name = sound["name"]
            namespace, _, path = name.partition(":")
            if not path:
                namespace, path = "guab", namespace
            if namespace != "guab":
                continue
            ogg = HUD / "sounds/guab" / f"{path}.ogg"
            if not ogg.is_file():
                raise FileNotFoundError(f"sound event guab:{event} references missing {ogg}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font-only", action="store_true",
                        help="only regenerate the HUD font bitmap")
    parser.add_argument("--sounds-only", action="store_true",
                        help="only regenerate the OGG sounds and sounds.json")
    parser.add_argument("--items-only", action="store_true",
                        help="only regenerate the item textures/models")
    args = parser.parse_args()

    if not args.sounds_only and not args.items_only:
        font_path = HUD / "assets/guab/textures/font/hud.png"
        render_font(font_path)
        print(f"font   {font_path.relative_to(ROOT)}  "
              f"({font_path.stat().st_size} bytes)")

    if not args.font_only and not args.items_only:
        sound_dir = HUD / "sounds/guab"
        with tempfile.TemporaryDirectory() as temporary:
            temporary = Path(temporary)
            for name, factory in SOUNDS.items():
                wav = temporary / f"{name}.wav"
                write_wav(wav, factory())
                ogg = sound_dir / f"{name}.ogg"
                encode_ogg(wav, ogg)
                print(f"sound  {ogg.relative_to(ROOT)}  "
                      f"({ogg.stat().st_size} bytes)")
        write_sounds_json()

    if not args.font_only and not args.sounds_only:
        summary = render_items()
        validate_item_assets()
        print(
            f"items  {summary['cards']} cards + {summary['chips']} chips + "
            f"{summary['icons']} icons + {summary['props']} props "
            f"({summary['files']} files)")
        print(f"        {ITEM_ASSETS.relative_to(ROOT)}")

    validate_source()


if __name__ == "__main__":
    main()
