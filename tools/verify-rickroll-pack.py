#!/usr/bin/env python3
"""Validate a GuabRickroll resource pack and its pack.properties.

Checks every frame's item definition -> model -> texture chain, the rickroll
sound event/file, duration*fps vs frame count and the pack SHA-1.

    tools/verify-rickroll-pack.py \
        --pack /opt/guab-server/resourcepack/rickroll.zip \
        --properties /opt/guab-server/data/plugins/GuabRickroll/pack.properties
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path


def read_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", "!")) or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def split_id(reference: str) -> tuple[str, str]:
    namespace, separator, path = reference.partition(":")
    if not separator:
        return "minecraft", namespace
    return namespace, path


def texture_path(reference: str) -> str:
    namespace, path = split_id(reference)
    return f"assets/{namespace}/textures/{path}.png"


def model_path(reference: str) -> str:
    namespace, path = split_id(reference)
    return f"assets/{namespace}/models/{path}.json"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", default="/opt/guab-server/resourcepack/rickroll.zip")
    parser.add_argument(
        "--properties",
        default="/opt/guab-server/data/plugins/GuabRickroll/pack.properties",
    )
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    pack = Path(args.pack)
    properties = Path(args.properties)
    errors: list[str] = []

    def say(message: str) -> None:
        if not args.quiet:
            print(message)

    if not pack.is_file():
        print(f"FAIL: pack missing: {pack}", file=sys.stderr)
        return 1

    try:
        values = read_properties(properties)
        frames = int(values["frames"])
        fps = int(values["fps"])
        duration = int(values["duration"])
        expected_sha1 = values["sha1"]
    except (OSError, KeyError, ValueError) as error:
        print(f"FAIL: cannot read {properties}: {error}", file=sys.stderr)
        return 1

    actual_sha1 = hashlib.sha1(pack.read_bytes()).hexdigest()
    if actual_sha1.lower() != expected_sha1.lower():
        errors.append(f"sha1 mismatch: pack={actual_sha1} properties={expected_sha1}")
    else:
        say(f"sha1 ok: {actual_sha1}")

    try:
        archive = zipfile.ZipFile(pack)
    except zipfile.BadZipFile as error:
        print(f"FAIL: not a ZIP: {error}", file=sys.stderr)
        return 1

    with archive:
        broken = archive.testzip()
        if broken:
            errors.append(f"corrupt ZIP entry: {broken}")
        names = set(archive.namelist())
        if "pack.mcmeta" not in names:
            errors.append("pack.mcmeta missing")
        else:
            try:
                json.loads(archive.read("pack.mcmeta"))
            except json.JSONDecodeError as error:
                errors.append(f"pack.mcmeta is invalid JSON: {error}")

        missing_frames: list[int] = []
        for index in range(1, frames + 1):
            item_name = f"assets/guab/items/frame/{index:04d}.json"
            if item_name not in names:
                missing_frames.append(index)
                continue
            try:
                definition = json.loads(archive.read(item_name))
            except json.JSONDecodeError:
                missing_frames.append(index)
                continue
            model = definition.get("model")
            model_id = model.get("model") if isinstance(model, dict) else None
            if not isinstance(model_id, str):
                missing_frames.append(index)
                continue
            model_name = model_path(model_id)
            if model_name not in names:
                missing_frames.append(index)
                continue
            try:
                model_json = json.loads(archive.read(model_name))
            except json.JSONDecodeError:
                missing_frames.append(index)
                continue
            textures = model_json.get("textures")
            layer = textures.get("layer0") if isinstance(textures, dict) else None
            if not isinstance(layer, str) or texture_path(layer) not in names:
                missing_frames.append(index)

        if missing_frames:
            preview = missing_frames[:8]
            errors.append(
                f"{len(missing_frames)}/{frames} frames are broken: {preview}"
                + ("..." if len(missing_frames) > 8 else "")
            )
        else:
            say(f"frames ok: {frames} item definitions resolve to a model and texture")

        sound_file = "assets/guab/sounds/rickroll.ogg"
        sounds_name = "assets/guab/sounds.json"
        if sound_file not in names:
            errors.append(f"missing sound file {sound_file}")
        if sounds_name not in names:
            errors.append(f"missing sound event file {sounds_name}")
        else:
            try:
                sounds = json.loads(archive.read(sounds_name))
            except json.JSONDecodeError as error:
                errors.append(f"{sounds_name} is invalid JSON: {error}")
            else:
                if "rickroll" not in sounds:
                    errors.append("sounds.json has no guab:rickroll event")
                else:
                    say("sound ok: guab:rickroll event + rickroll.ogg")

    expected_frames = round(duration * fps)
    tolerance = max(2, round(expected_frames * 0.02))
    difference = abs(expected_frames - frames)
    if difference > tolerance:
        errors.append(
            f"duration mismatch: {duration}s*{fps}fps={expected_frames} frames "
            f"but pack says {frames} (tolerance {tolerance})"
        )
    else:
        say(f"duration ok: {duration}s*{fps}fps={expected_frames}, frames={frames}")

    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(f"PASS: {pack} ({frames} frames @ {fps}fps, {duration}s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
