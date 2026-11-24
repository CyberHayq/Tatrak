#!/usr/bin/env python3
"""Generate the showcase avatars and photos (no personal files), for seed_showcase.py and the
OMEMO chat typed on the emulators (knowledge/ops/store-screenshots.md):

    python3 -I tools/store-screenshots/make_media.py <out dir>

Writes <out>/avatars/<user>.png (192 px; users without one keep the app's letter avatar) and
<out>/photos/mountains.jpg, <out>/photos/balcony.jpg. Deterministic.
"""
import math, random, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter


def gradient(size, top, bottom):
    w, h = size
    g = Image.linear_gradient("L").resize((w, h))
    return Image.composite(Image.new("RGB", size, bottom), Image.new("RGB", size, top), g)


def ridge(rnd, w, base, amp, steps):
    """A mountain ridge as polygon points from left to right (closed at the bottom)."""
    pts, y = [], base
    for i in range(steps + 1):
        y = min(base + amp, max(base - amp, y + rnd.uniform(-amp, amp) * 0.45))
        pts.append((w * i / steps, y))
    return pts


def mountains(size, seed, sky, sun, layers, snow=False, peaks=None):
    w, h = size
    rnd = random.Random(seed)
    img = gradient(size, *sky)
    glow = Image.new("RGB", size, (0, 0, 0))
    sx, sy, sr = sun
    ImageDraw.Draw(glow).ellipse([sx - sr * 3, sy - sr * 3, sx + sr * 3, sy + sr * 3], fill=(120, 80, 40))
    img = Image.blend(img, Image.composite(Image.new("RGB", size, (255, 214, 160)), img,
                                           glow.convert("L").filter(ImageFilter.GaussianBlur(sr * 2))), 0.6)
    d = ImageDraw.Draw(img)
    d.ellipse([sx - sr, sy - sr, sx + sr, sy + sr], fill=(255, 236, 200))
    if peaks:  # two snow-capped peaks, big and small, far away
        for cx, top, half in peaks:
            body = [(cx - half * 2.2, h * 0.62), (cx, top), (cx + half * 2.2, h * 0.62)]
            d.polygon(body, fill=layers[0])
            cap = [(cx - half * 0.55, top + half * 0.32), (cx, top), (cx + half * 0.55, top + half * 0.32),
                   (cx + half * 0.2, top + half * 0.24), (cx - half * 0.15, top + half * 0.36)]
            d.polygon(cap, fill=(246, 246, 250))
    for k, color in enumerate(layers[1:] if peaks else layers):
        base = h * (0.55 + 0.12 * k)
        pts = ridge(rnd, w, base, h * 0.05, 24)
        d.polygon(pts + [(w, h), (0, h)], fill=color)
        if snow and k == 0:
            for x, y in pts[1:-1:3]:
                d.polygon([(x - 18, y + 14), (x, y), (x + 18, y + 14)], fill=(235, 238, 245))
    img = img.filter(ImageFilter.GaussianBlur(0.8))
    noise = Image.frombytes("L", size, random.Random(seed + 1).randbytes(w * h)).convert("RGB")
    return Image.blend(img, noise, 0.04)


def city(size, seed):
    """Low sun over roofs and hills, as seen from a balcony."""
    w, h = size
    rnd = random.Random(seed)
    img = mountains(size, seed, ((250, 190, 140), (120, 110, 170)), (w * 0.68, h * 0.42, h * 0.06),
                    [(150, 120, 160), (110, 92, 140)])
    d = ImageDraw.Draw(img)
    x = 0
    while x < w:  # roofs
        bw, bh = rnd.randint(60, 150), rnd.randint(int(h * 0.12), int(h * 0.3))
        shade = rnd.randint(52, 78)
        d.rectangle([x, h - bh, x + bw, h], fill=(shade + 10, shade - 6, shade + 22))
        for wy in range(h - bh + 18, h - 20, 34):
            for wx in range(x + 12, x + bw - 16, 28):
                if rnd.random() < 0.35:
                    d.rectangle([wx, wy, wx + 12, wy + 16], fill=(255, 212, 150))
        x += bw + rnd.randint(2, 10)
    d.rectangle([0, h - 70, w, h], fill=(40, 34, 50))  # the balcony rail
    for px in range(0, w, 46):
        d.rectangle([px, h - 150, px + 8, h - 70], fill=(40, 34, 50))
    d.rectangle([0, h - 160, w, h - 146], fill=(40, 34, 50))
    return img


def flower(size, bg, petal, centre):
    s = size * 4
    img = gradient((s, s), *bg)
    d = ImageDraw.Draw(img)
    c, r = s / 2, s * 0.17
    for k in range(6):
        a = k * math.pi / 3
        x, y = c + math.cos(a) * r, c + math.sin(a) * r
        d.ellipse([x - r * 0.9, y - r * 0.9, x + r * 0.9, y + r * 0.9], fill=petal)
    d.ellipse([c - r * 0.6, c - r * 0.6, c + r * 0.6, c + r * 0.6], fill=centre)
    return img.resize((size, size), Image.LANCZOS)


def sea(size):
    s = size * 4
    img = gradient((s, s), (120, 190, 235), (20, 70, 140))
    d = ImageDraw.Draw(img)
    for k in range(5):
        y = s * (0.58 + k * 0.09)
        d.arc([-s * 0.2, y - 40, s * 0.6, y + 40], 200, 340, fill=(220, 240, 255), width=10)
        d.arc([s * 0.4, y - 30, s * 1.2, y + 50], 200, 340, fill=(200, 230, 250), width=10)
    d.polygon([(s * 0.5, s * 0.18), (s * 0.5, s * 0.5), (s * 0.28, s * 0.5)], fill=(255, 255, 255))
    d.polygon([(s * 0.53, s * 0.24), (s * 0.53, s * 0.5), (s * 0.68, s * 0.5)], fill=(255, 236, 210))
    d.polygon([(s * 0.3, s * 0.53), (s * 0.72, s * 0.53), (s * 0.64, s * 0.6), (s * 0.36, s * 0.6)], fill=(120, 60, 40))
    return img.resize((size, size), Image.LANCZOS)


def night(size, seed):
    s = size * 4
    rnd = random.Random(seed)
    img = gradient((s, s), (24, 30, 70), (70, 60, 120))
    d = ImageDraw.Draw(img)
    for _ in range(40):
        x, y, r = rnd.uniform(0, s), rnd.uniform(0, s * 0.7), rnd.uniform(3, 7)
        d.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 230))
    d.ellipse([s * 0.52, s * 0.18, s * 0.82, s * 0.48], fill=(250, 240, 200))
    d.ellipse([s * 0.6, s * 0.14, s * 0.9, s * 0.44], fill=(28, 34, 76))  # crescent
    d.polygon(ridge(rnd, s, s * 0.78, s * 0.05, 12) + [(s, s), (0, s)], fill=(16, 18, 40))
    return img.resize((size, size), Image.LANCZOS)


def main():
    out = Path(sys.argv[1])
    (out / "avatars").mkdir(parents=True, exist_ok=True)
    (out / "photos").mkdir(parents=True, exist_ok=True)
    big = (1600, 1200)
    mountains(big, 7, ((255, 196, 150), (150, 170, 220)), (1180, 300, 55),
              [(150, 160, 200), (84, 96, 140), (52, 62, 98), (32, 38, 64)],
              peaks=[(560, 250, 260), (1000, 430, 140)]).save(out / "photos/mountains.jpg", quality=88)
    city(big, 11).save(out / "photos/balcony.jpg", quality=88)
    avatars = {
        "ani": mountains((768, 768), 3, ((255, 170, 140), (140, 110, 200)), (520, 300, 50),
                         [(170, 120, 190), (110, 80, 150), (70, 50, 110)], peaks=[(330, 250, 120), (560, 370, 70)]),
        "aram": sea(192),
        "lilit": flower(192, ((250, 210, 230), (200, 170, 240)), (255, 255, 255), (250, 190, 80)),
        "gor": night(192, 5),
        "karen": mountains((768, 768), 9, ((170, 220, 200), (90, 170, 150)), (200, 220, 40),
                           [(80, 150, 110), (50, 120, 80), (30, 90, 60)]),
    }
    for name, img in avatars.items():
        img.resize((192, 192), Image.LANCZOS).save(out / f"avatars/{name}.png", optimize=True)
    print(out)


if __name__ == "__main__":
    main()
