#!/usr/bin/env python3
"""Generate the Guab HUD bitmap font and the custom sound effects.

Everything here is deterministic: the PNG is drawn from a fixed pixel map and
the OGG files are synthesised from fixed waveforms with a seeded noise source,
then encoded with libvorbis. Re-running the script on the same Pillow/ffmpeg
versions produces the same bytes.

Run from anywhere with Pillow, numpy, and ffmpeg installed:

    python3 tools/build-guab-hud-assets.py

Outputs:

    pack/hud/assets/guab/textures/font/hud.png
    pack/hud/sounds/guab/quiz_correct.ogg
    pack/hud/sounds/guab/one_eighty.ogg
    pack/hud/sounds/guab/beer_pour.ogg
    pack/hud/sounds/guab/milestone.ogg

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
from PIL import Image

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


def envelope(length: int, attack: float = 0.004, release: float = 0.08) -> np.ndarray:
    attack_samples = min(length, max(1, int(attack * SAMPLE_RATE)))
    release_samples = min(length - attack_samples, max(1, int(release * SAMPLE_RATE)))
    shape = np.ones(length, dtype=np.float64)
    shape[:attack_samples] = np.linspace(0.0, 1.0, attack_samples)
    if release_samples > 0:
        shape[-release_samples:] *= np.linspace(1.0, 0.0, release_samples)
    return shape


def square(frequency: float, seconds: float, volume: float, duty: float = 0.5) -> np.ndarray:
    length = int(seconds * SAMPLE_RATE)
    phase = (np.arange(length) * frequency / SAMPLE_RATE) % 1.0
    wave_shape = np.where(phase < duty, 1.0, -1.0)
    return wave_shape * envelope(length) * volume


def triangle(frequency: float, seconds: float, volume: float) -> np.ndarray:
    length = int(seconds * SAMPLE_RATE)
    phase = (np.arange(length) * frequency / SAMPLE_RATE) % 1.0
    return (4.0 * np.abs(phase - 0.5) - 1.0) * envelope(length) * volume


def silence(seconds: float) -> np.ndarray:
    return np.zeros(max(1, int(seconds * SAMPLE_RATE)), dtype=np.float64)


def sequence(*parts: np.ndarray) -> np.ndarray:
    return np.concatenate(parts) if parts else silence(0.01)


def buzz(frequency: float, seconds: float, volume: float) -> np.ndarray:
    """A slightly detuned square pair for the 8-bit pub sound."""
    return square(frequency, seconds, volume, 0.45) + square(frequency * 1.005, seconds, volume * 0.35, 0.5)


def quiz_correct() -> np.ndarray:
    return sequence(
        silence(0.01),
        buzz(1318.51, 0.10, 0.34),   # E6
        silence(0.012),
        buzz(1567.98, 0.24, 0.38),   # G6
        triangle(3135.96, 0.24, 0.08),
    )


def one_eighty() -> np.ndarray:
    return sequence(
        silence(0.01),
        buzz(783.99, 0.10, 0.30),    # G5
        buzz(987.77, 0.10, 0.32),    # B5
        buzz(1174.66, 0.12, 0.34),   # D6
        buzz(1567.98, 0.34, 0.38),   # G6
        triangle(3135.96, 0.34, 0.06),
    )


def beer_pour() -> np.ndarray:
    rng = np.random.default_rng(180)
    length = int(0.92 * SAMPLE_RATE)
    noise = rng.uniform(-1.0, 1.0, length)
    # One-pole low-pass: keeps the pour fizzy instead of hissy.
    filtered = np.empty_like(noise)
    state = 0.0
    for index, sample in enumerate(noise):
        state += 0.18 * (sample - state)
        filtered[index] = state
    filtered *= np.linspace(1.0, 0.15, length)
    filtered *= envelope(length, attack=0.02, release=0.30)
    filtered *= 0.45

    time = np.arange(length) / SAMPLE_RATE
    pitch = np.linspace(310.0, 88.0, length)
    phase = np.cumsum(2.0 * math.pi * pitch / SAMPLE_RATE)
    glug = np.sin(phase) * np.linspace(0.35, 0.05, length)
    glug *= np.sin(np.minimum(1.0, time * 9.0) * math.pi)
    burp = square(92.0, 0.92, 0.10, 0.35)
    return sequence(silence(0.01), filtered + glug * 0.55 + burp)


def milestone() -> np.ndarray:
    return sequence(
        silence(0.01),
        buzz(1975.53, 0.07, 0.30),   # B6
        buzz(2637.02, 0.10, 0.32),   # E7
        silence(0.02),
        buzz(2637.02, 0.26, 0.34),   # E7
        triangle(5274.04, 0.26, 0.05),
    )


SOUNDS = {
    "quiz_correct": quiz_correct,
    "one_eighty": one_eighty,
    "beer_pour": beer_pour,
    "milestone": milestone,
}


def write_wav(path: Path, samples: np.ndarray) -> None:
    peak = float(np.max(np.abs(samples))) if samples.size else 0.0
    if peak > 0:
        samples = samples * (0.82 / peak)
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
    parser.add_argument("--font-only", action="store_true")
    parser.add_argument("--sounds-only", action="store_true")
    args = parser.parse_args()

    if not args.sounds_only:
        font_path = HUD / "assets/guab/textures/font/hud.png"
        render_font(font_path)
        print(f"font   {font_path.relative_to(ROOT)}  ({font_path.stat().st_size} bytes)")

    if not args.font_only:
        sound_dir = HUD / "sounds/guab"
        with tempfile.TemporaryDirectory() as temporary:
            temporary = Path(temporary)
            for name, factory in SOUNDS.items():
                wav = temporary / f"{name}.wav"
                write_wav(wav, factory())
                ogg = sound_dir / f"{name}.ogg"
                encode_ogg(wav, ogg)
                print(f"sound  {ogg.relative_to(ROOT)}  ({ogg.stat().st_size} bytes)")

    validate_source()


if __name__ == "__main__":
    main()
