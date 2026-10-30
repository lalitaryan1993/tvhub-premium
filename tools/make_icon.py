#!/usr/bin/env python3
"""Cut TV Hub Premium's launcher icons and Android TV banner from the artwork in docs/art/.

docs/art/icon.webp   square icon artwork (a rounded tile on a dark background; the corners are made transparent)
docs/art/banner.webp 16:9 banner artwork

Writes app/src/main/res/mipmap-*/ic_launcher.png, drawable-xhdpi/banner.png (640x360, the 320x180 dp
banner at xhdpi) and docs/icon-512.png for the README. Needs Pillow.

Usage: python tools/make_icon.py
"""
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(ROOT, "docs", "art")
RES = os.path.join(ROOT, "app", "src", "main", "res")


def icon(src: Image.Image, size: int) -> Image.Image:
    # Trim a hair off the edge (the artwork's dark rim), round the corners, then scale down.
    s = src.size[0]
    inset = int(s * 0.004)
    tile = src.crop((inset, inset, s - inset, s - inset)).convert("RGBA")
    t = tile.size[0]
    mask = Image.new("L", (t, t), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, t - 1, t - 1], radius=int(t * 0.18), fill=255)
    tile.putalpha(mask)
    return tile.resize((size, size), Image.LANCZOS)


def banner(src: Image.Image, w=640, h=360) -> Image.Image:
    # Centre-crop to exactly 16:9, then scale.
    sw, sh = src.size
    if sw / sh > w / h:
        nw = int(sh * w / h); box = ((sw - nw) // 2, 0, (sw + nw) // 2, sh)
    else:
        nh = int(sw * h / w); box = (0, (sh - nh) // 2, sw, (sh + nh) // 2)
    return src.convert("RGB").crop(box).resize((w, h), Image.LANCZOS)


if __name__ == "__main__":
    ic = Image.open(os.path.join(ART, "icon.webp"))
    for folder, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        icon(ic, px).save(os.path.join(RES, f"mipmap-{folder}", "ic_launcher.png"), optimize=True)
    icon(ic, 512).save(os.path.join(ROOT, "docs", "icon-512.png"), optimize=True)
    banner(Image.open(os.path.join(ART, "banner.webp"))).save(os.path.join(RES, "drawable-xhdpi", "banner.png"), optimize=True)
    print("icon and banner written")
