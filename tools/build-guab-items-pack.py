#!/usr/bin/env python3
"""Build the GuabItems resource pack from real product photos.

The beer photos are front-of-pack images from Open Food Facts
(https://world.openfoodfacts.org, CC BY-SA 3.0, contributed by its
photographers). The dartboard is Wikimedia Commons "Dartboard unlabeled.svg"
(CC BY-SA 3.0). The pack icon is a composite of those two assets.

This tool is deliberately not part of the Gradle build: media stays on the VM.
Run it from the repo root with Pillow installed:

    sudo tools/build-guab-items-pack.py

It rebuilds both the standalone guab-items pack and the copy embedded in the
rickroll pack, then prints the SHA-1 values that must go into server.properties
and GuabRickroll/pack.properties.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import os
import shutil
import tempfile
import urllib.request
import zipfile
from collections import deque
from pathlib import Path

from PIL import Image, ImageDraw

USER_AGENT = "GuabMinecraft/1.0 (private Paper server; pack build tool)"

BRAND_IMAGES = {
    "stella": "https://images.openfoodfacts.org/images/products/501/437/901/2873/front_en.19.400.jpg",
    "carling": "https://images.openfoodfacts.org/images/products/501/003/844/4252/front_en.19.400.jpg",
    "fosters": "https://images.openfoodfacts.org/images/products/503/576/618/5826/front_en.3.400.jpg",
    "sanmiguel": "https://images.openfoodfacts.org/images/products/841/065/500/0010/front_en.48.400.jpg",
    "peroni": "https://images.openfoodfacts.org/images/products/800/844/022/2008/front_en.64.400.jpg",
    "moretti": "https://images.openfoodfacts.org/images/products/000/008/011/4024/front_en.32.400.jpg",
    "heineken": "https://images.openfoodfacts.org/images/products/311/978/025/9625/front_fr.84.400.jpg",
    "corona": "https://images.openfoodfacts.org/images/products/000/007/503/3927/front_en.3.400.jpg",
    "budweiser": "https://images.openfoodfacts.org/images/products/006/206/733/5341/front_en.19.400.jpg",
    "johnsmiths": "https://images.openfoodfacts.org/images/products/503/576/664/1223/front_en.3.400.jpg",
    "strongbow": "https://images.openfoodfacts.org/images/products/503/576/604/9463/front_en.3.400.jpg",
    "thatchers": "https://images.openfoodfacts.org/images/products/502/062/800/3318/front_en.3.400.jpg",
    "londonpride": "https://images.openfoodfacts.org/images/products/501/188/500/3647/front_en.9.400.jpg",
    "guinness": "https://images.openfoodfacts.org/images/products/500/021/310/1223/front_fr.60.400.jpg",
}
DARTBOARD_IMAGE = (
    "https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f3/"
    "Dartboard_unlabeled.svg/960px-Dartboard_unlabeled.svg.png"
)
PINT_IMAGE = (
    "https://thumb.wikimedia.org/wikipedia/commons/thumb/6/64/"
    "Pint_Glass_with_some_beer.jpg/960px-Pint_Glass_with_some_beer.jpg"
)


def download(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def uniform_border(image: Image.Image, tolerance: int = 26):
    image = image.convert("RGB")
    width, height = image.size
    samples = []
    step = max(1, min(width, height) // 40)
    for x in range(0, width, step):
        samples += [image.getpixel((x, 0)), image.getpixel((x, height - 1))]
    for y in range(0, height, step):
        samples += [image.getpixel((0, y)), image.getpixel((width - 1, y))]
    average = tuple(sum(pixel[i] for pixel in samples) // len(samples) for i in range(3))
    worst = max(max(abs(pixel[i] - average[i]) for i in range(3)) for pixel in samples)
    return average, worst <= tolerance


def remove_flat_background(image: Image.Image, background, tolerance: int = 32) -> Image.Image:
    image = image.convert("RGBA")
    width, height = image.size
    pixels = image.load()
    seen = bytearray(width * height)
    queue = deque()

    def matches(x: int, y: int) -> bool:
        red, green, blue, alpha = pixels[x, y]
        return (
            alpha > 0
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
            if 0 <= nx < width and 0 <= ny < height and not seen[ny * width + nx] and matches(nx, ny):
                seen[ny * width + nx] = 1
                queue.append((nx, ny))
    return image


def product_texture(data: bytes, size: int = 64) -> Image.Image:
    image = Image.open(io.BytesIO(data)).convert("RGB")
    background, uniform = uniform_border(image)
    removed = False
    if uniform:
        image = remove_flat_background(image, background)
        box = image.getchannel("A").getbbox()
        if box:
            image = image.crop(box)
            removed = True

    if removed:
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        inner = size - 4
    else:
        canvas = Image.new("RGBA", (size, size), (30, 30, 36, 255))
        inner = size - 6
        ImageDraw.Draw(canvas).rectangle((0, 0, size - 1, size - 1), outline=(90, 78, 52, 255))

    image.thumbnail((inner, inner), Image.LANCZOS)
    if image.mode != "RGBA":
        image = image.convert("RGBA")
    canvas.alpha_composite(image, ((size - image.width) // 2, (size - image.height) // 2))
    return canvas


def pint_texture(data: bytes, size: int = 64) -> Image.Image:
    image = Image.open(io.BytesIO(data)).convert("RGBA")
    background, uniform = uniform_border(image)
    if uniform:
        image = remove_flat_background(image, background)
        box = image.getchannel("A").getbbox()
        if box:
            image = image.crop(box)
    canvas = Image.new("RGBA", (size, size), (30, 30, 36, 255))
    ImageDraw.Draw(canvas).rectangle((0, 0, size - 1, size - 1), outline=(90, 78, 52, 255))
    image.thumbnail((size - 6, size - 6), Image.LANCZOS)
    canvas.alpha_composite(image, ((size - image.width) // 2, (size - image.height) // 2))
    return canvas


def dartboard_texture(data: bytes, size: int = 128) -> Image.Image:
    image = Image.open(io.BytesIO(data)).convert("RGBA")
    box = image.getchannel("A").getbbox()
    if box:
        image = image.crop(box)
    canvas = Image.new("RGBA", (size, size), (22, 20, 18, 255))
    ImageDraw.Draw(canvas).rectangle((0, 0, size - 1, size - 1), outline=(64, 52, 34, 255), width=2)
    image.thumbnail((size - 10, size - 10), Image.LANCZOS)
    canvas.alpha_composite(image, ((size - image.width) // 2, (size - image.height) // 2))
    return canvas


def write_zip(folder: Path, destination: Path) -> None:
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for base, directories, files in os.walk(folder):
            directories.sort()
            files.sort()
            for name in files:
                full = Path(base) / name
                archive.write(full, full.relative_to(folder))


def sha1(path: Path) -> str:
    return hashlib.sha1(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--items-base", default="/opt/guab-server/resourcepack/guab-items.zip")
    parser.add_argument("--rickroll-base", default="/opt/guab-server/resourcepack/rickroll.zip")
    parser.add_argument("--out", default="/tmp/guab-pack")
    args = parser.parse_args()

    out = Path(args.out)
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)

    with tempfile.TemporaryDirectory() as temporary:
        temporary = Path(temporary)
        items = temporary / "items"
        rickroll = temporary / "rickroll"
        items.mkdir()
        rickroll.mkdir()
        with zipfile.ZipFile(args.items_base) as archive:
            archive.extractall(items)
        with zipfile.ZipFile(args.rickroll_base) as archive:
            archive.extractall(rickroll)

        beer_dir = items / "assets/guab/textures/item"
        target_dir = items / "assets/minecraft/textures/block"
        beer_dir.mkdir(parents=True, exist_ok=True)
        target_dir.mkdir(parents=True, exist_ok=True)

        pint_texture(download(PINT_IMAGE)).save(beer_dir / "beer.png", optimize=True)

        for name, url in BRAND_IMAGES.items():
            product_texture(download(url)).save(beer_dir / f"beer_{name}.png", optimize=True)

        dartboard_texture(download(DARTBOARD_IMAGE)).save(target_dir / "target_side.png", optimize=True)

        icon = Image.new("RGBA", (128, 128), (24, 22, 20, 255))
        icon.alpha_composite(
            Image.open(target_dir / "target_side.png").convert("RGBA").resize((64, 64), Image.LANCZOS),
            (0, 32))
        icon.alpha_composite(
            Image.open(beer_dir / "beer.png").convert("RGBA").resize((64, 64), Image.LANCZOS),
            (64, 32))
        icon.save(items / "pack.png", optimize=True)

        rickroll_beer = rickroll / "assets/guab/textures/item"
        rickroll_beer.mkdir(parents=True, exist_ok=True)
        for texture in beer_dir.glob("*.png"):
            shutil.copy2(texture, rickroll_beer / texture.name)
        rickroll_target = rickroll / "assets/minecraft/textures/block"
        rickroll_target.mkdir(parents=True, exist_ok=True)
        shutil.copy2(target_dir / "target_side.png", rickroll_target / "target_side.png")
        shutil.copy2(items / "pack.png", rickroll / "pack.png")

        items_zip = out / "guab-items.zip"
        rickroll_zip = out / "rickroll.zip"
        write_zip(items, items_zip)
        write_zip(rickroll, rickroll_zip)

    print(f"{items_zip}  {items_zip.stat().st_size} bytes  sha1={sha1(items_zip)}")
    print(f"{rickroll_zip}  {rickroll_zip.stat().st_size} bytes  sha1={sha1(rickroll_zip)}")


if __name__ == "__main__":
    main()
