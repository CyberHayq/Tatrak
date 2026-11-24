#!/usr/bin/env python3
"""Compose the README and store cards from raw emulator captures.

    python3 -I tools/store-screenshots/compose.py [<captures dir>] [--out <dir>] [--no-camera]

<captures dir> holds 01.png .. 06.png (adb exec-out screencap -p, e.g. 1080x2400). Writes
fastlane/metadata/android/en-US/images/phoneScreenshots/NN.png (1080x1920; README.md shows
the same files), and always the F-Droid feature graphic fastlane/.../images/featureGraphic.png
(1024x500), under the repository root, or under --out for a trial run. Runbook:
knowledge/ops/store-screenshots.md. Deterministic: every random choice uses a fixed seed.
"""
import argparse, math, random, sys
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

REPO = Path(__file__).resolve().parents[2]
# card order = capture number; stem, kicker, headline lines, accent, tint of the glow
CARDS = [
    ("01-organised-chats", "Organised", "Keep your chats", "in order", (94, 200, 190), (18, 70, 78)),
    ("02-encrypted-chats", "Encrypted by default", "Talk freely,", "privately", (110, 160, 255), (24, 40, 96)),
    ("03-group-chats", "Groups", "Bring everyone", "together", (190, 150, 255), (60, 34, 96)),
    ("04-your-style", "Your style", "Make it", "yours", (245, 140, 175), (92, 30, 58)),
    ("05-open-network", "Open network", "No phone number.", "Any server.", (95, 205, 240), (16, 58, 84)),
    ("06-compatible-backups", "Compatible", "Conversations and", "monocles backups", (235, 190, 105), (86, 60, 20)),
]
# the F-Droid feature graphic (fastlane featureGraphic.png): name, tagline and these lines
TAGLINE = "Messenger for the open XMPP network"
FEATURES = [
    "No phone number, any XMPP server",
    "End-to-end encryption with OMEMO",
    "Messages and files encrypted at rest",
    "Groups, calls and a style of your own",
]
W, H = 1080, 1920
INK = (10, 13, 21)
TEXT = (238, 242, 250)
FONT_BOLD = REPO / "src/main/res/font/tatrak_manrope_bold.ttf"
FONT_SEMI = REPO / "src/main/res/font/tatrak_manrope_semibold.ttf"
TITLE_X, KICKER_Y, RULE_Y, LINE_Y = 110, 150, 205, (250, 345)
SCREEN_W, PHONE_TOP = 580, 495  # the phone's screen on the card
SS = 3  # supersampling for the phone's curves
ICON = REPO / "fastlane/metadata/android/en-US/images/icon.png"
STORE = "fastlane/metadata/android/en-US/images"


def blank(size=(W, H), color=(0, 0, 0, 0)):
    return Image.new("RGBA", size, color)


def glow(img, xy, r, color, alpha):
    layer = blank(img.size)
    ImageDraw.Draw(layer).ellipse([xy[0] - r, xy[1] - r, xy[0] + r, xy[1] + r], fill=color + (alpha,))
    img.alpha_composite(layer.filter(ImageFilter.GaussianBlur(r // 2)))


def grain(img, seed, alpha=7):
    w, h = img.size
    noise = Image.frombytes("L", (w, h), random.Random(seed).randbytes(w * h)).convert("RGBA")
    noise.putalpha(alpha)
    img.alpha_composite(noise)


def title(img, kicker, line1, line2, accent):
    """Draws the title and returns its bounding box (the network keeps clear of it)."""
    d = ImageDraw.Draw(img)
    fk, fl = ImageFont.truetype(str(FONT_SEMI), 30), ImageFont.truetype(str(FONT_BOLD), 78)
    x = TITLE_X
    for ch in kicker.upper():  # letter-spaced by hand: Pillow has no tracking
        d.text((x, KICKER_Y), ch, font=fk, fill=accent)
        x += d.textlength(ch, font=fk) + 6
    d.line([TITLE_X, RULE_Y, TITLE_X + 56, RULE_Y], fill=accent, width=3)
    boxes = [(TITLE_X, KICKER_Y, x, RULE_Y + 2)]
    for y, line in zip(LINE_Y, (line1, line2)):
        d.text((TITLE_X, y), line, font=fl, fill=TEXT)
        boxes.append(d.textbbox((TITLE_X, y), line, font=fl))
    return (min(b[0] for b in boxes), min(b[1] for b in boxes), max(b[2] for b in boxes), max(b[3] for b in boxes))


def network(size, seed, count, min_dist, area, keep_out, line_w, line_a, node_r, node_a, accent, hubs):
    """One layer of hairline network: nodes joined to their two nearest neighbours."""
    rnd = random.Random(seed)
    pts = []
    for _ in range(20000):
        if len(pts) == count:
            break
        p = (rnd.uniform(area[0], area[2]), rnd.uniform(area[1], area[3]))
        if keep_out[0] <= p[0] <= keep_out[2] and keep_out[1] <= p[1] <= keep_out[3]:
            continue
        if all(math.dist(p, q) > min_dist for q in pts):
            pts.append(p)
    edges = sorted({tuple(sorted((i, j))) for i, p in enumerate(pts)
                    for j in sorted(range(len(pts)), key=lambda j: math.dist(p, pts[j]))[1:3]})
    layer = blank(size)
    d = ImageDraw.Draw(layer)
    for i, j in edges:  # long edges dimmer
        a = int(line_a * max(0.35, 1 - math.dist(pts[i], pts[j]) / 700))
        d.line([pts[i], pts[j]], fill=(200, 215, 240, a), width=line_w)
    hub = set(rnd.sample(range(len(pts)), hubs))
    for i, (x, y) in enumerate(pts):
        if i in hub:
            r = node_r * 3
            d.ellipse([x - r, y - r, x + r, y + r], outline=accent + (110,), width=2)
            r = node_r * 1.3
            d.ellipse([x - r, y - r, x + r, y + r], fill=accent + (230,))
        else:
            d.ellipse([x - node_r, y - node_r, x + node_r, y + node_r], fill=(205, 218, 240, node_a))
    return layer, pts, edges, hub


def fade_under(layer, box, margin=70):
    """Fades a layer out over the title, so no node or edge reaches the text."""
    m = Image.new("L", layer.size, 255)
    ImageDraw.Draw(m).rectangle([0, 0, box[2] + margin, box[3] + margin], fill=0)
    m = m.filter(ImageFilter.GaussianBlur(margin))
    ImageDraw.Draw(m).rectangle([0, 0, box[2] + 20, box[3] + 20], fill=0)  # hard floor right at the text
    layer.putalpha(ImageChops.multiply(layer.getchannel("A"), m))


# glows of a card: behind the phone, and a softer one at the top right
CARD_GLOWS = ((0.5, 0.78, 640, 170), (0.95, 0.2, 380, 110))


def background(i, accent, tint, title_box, size=(W, H), glows=CARD_GLOWS, nodes=((60, 120), (26, 210))):
    w, h = size
    img = blank(size, INK + (255,))
    for gx, gy, r, a in glows:
        glow(img, (w * gx, h * gy), r, tint, a)
    area = (-60, min(380, h // 5), w + 60, h + 60)
    keep_out = (title_box[0] - 60, 0, title_box[2] + 60, title_box[3] + 60)
    far, *_ = network(size, 100 + i, *nodes[0], area, keep_out, 1, 34, 3, 80, accent, 0)
    far = far.filter(ImageFilter.GaussianBlur(1.2))
    fade_under(far, title_box)
    img.alpha_composite(far)
    near, pts, edges, hub = network(size, 200 + i, *nodes[1], area, keep_out, 2, 70, 5, 170, accent, 4)
    fade_under(near, title_box)
    img.alpha_composite(near)
    # messages in flight: a bright head with a tapering trail along an edge leaving a hub
    rnd = random.Random(300 + i)
    live = [e for e in edges if e[0] in hub or e[1] in hub]
    rnd.shuffle(live)
    for a, b in live[:5]:
        a, b = (pts[a], pts[b]) if a in hub else (pts[b], pts[a])
        t = rnd.uniform(0.45, 0.75)
        hx, hy = a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t
        if hx < title_box[2] + 110 and hy < title_box[3] + 110:  # keep clear of the text
            continue
        trail = blank(size)
        d = ImageDraw.Draw(trail)
        for s in range(18):
            tt = t - s * 0.012
            x, y, r = a[0] + (b[0] - a[0]) * tt, a[1] + (b[1] - a[1]) * tt, 4.5 - s * 0.2
            d.ellipse([x - r, y - r, x + r, y + r], fill=accent + (int(210 * (1 - s / 18)),))
        img.alpha_composite(trail)
        glow(img, (hx, hy), 22, accent, 170)
        ImageDraw.Draw(img).ellipse([hx - 5, hy - 5, hx + 5, hy + 5], fill=(255, 255, 255, 255))
    grain(img, 400 + i)
    return img


def punch_hole(shot):
    """A punch-hole camera at the capture's resolution (the emulator does not draw one)."""
    dp = shot.width / 411
    cx, cy = shot.width / 2, 12.5 * dp
    d = ImageDraw.Draw(shot)
    for r, color in ((9.5 * dp, (4, 4, 6)), (4.2 * dp, (16, 20, 32)), (2.6 * dp, (8, 10, 16))):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)
    g = 1.1 * dp  # lens glint
    gx, gy = cx - 1.6 * dp, cy - 1.6 * dp
    d.ellipse([gx - g, gy - g, gx + g, gy + g], fill=(92, 104, 138))


def phone(img, shot, camera):
    shot = shot.convert("RGB")
    if camera:
        punch_hole(shot)
    w = SCREEN_W
    h = round(shot.height * w / shot.width)
    dp = w / 411
    bezel, rad = 9, round(26 * dp)
    x, top = (W - w) // 2, PHONE_TOP
    if top + h + bezel > H - 20:
        sys.exit(f"capture {shot.size} is too tall for the card: screen would end at {top + h}")
    screen = shot.resize((w, h), Image.LANCZOS)
    # shadow
    sh = blank()
    ImageDraw.Draw(sh).rounded_rectangle([x - bezel + 20, top - bezel + 50, x + w + bezel - 20, top + h + bezel + 50],
                                         rad + bezel, fill=(0, 0, 0, 130))
    img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(50)))
    # body, rim and the right-edge buttons, drawn supersampled
    pad = 8
    bw, bh = w + 2 * (bezel + pad), h + 2 * (bezel + pad)
    body = Image.new("RGBA", (bw * SS, bh * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(body)
    o = pad * SS
    right = (pad + bezel + w + bezel) * SS
    for y0, y1 in ((120, 170), (200, 290)):  # power, then volume (dp from the top)
        d.rounded_rectangle([right - 3 * SS, round((pad + y0 * dp) * SS), right + 5 * SS, round((pad + y1 * dp) * SS)],
                            4 * SS, fill=(48, 52, 60, 255))
    outer = [o, o, right, (pad + bezel + h + bezel) * SS]
    d.rounded_rectangle(outer, (rad + bezel) * SS, fill=(20, 22, 27, 255), outline=(92, 98, 112, 255), width=2 * SS)
    inner = [v + (3 * SS if k < 2 else -3 * SS) for k, v in enumerate(outer)]
    d.rounded_rectangle(inner, (rad + bezel - 3) * SS, outline=(40, 44, 52, 255), width=SS)
    img.alpha_composite(body.resize((bw, bh), Image.LANCZOS), (x - bezel - pad, top - bezel - pad))
    mask = Image.new("L", (w * SS, h * SS), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w * SS - 1, h * SS - 1], rad * SS, fill=255)
    img.paste(screen, (x, top), mask.resize((w, h), Image.LANCZOS))


def card(i, shot, camera):
    stem, kicker, line1, line2, accent, tint = CARDS[i]
    probe = blank()  # measure the title first: the network must keep clear of it
    box = title(probe, kicker, line1, line2, accent)
    img = background(i, accent, tint, box)
    title(img, kicker, line1, line2, accent)
    phone(img, shot, camera)
    return img.convert("RGB")


def banner():
    """1024x500 feature graphic: icon, name, tagline and FEATURES with the first four card accents."""
    size, x = (1024, 500), 64
    probe = blank(size)
    d = ImageDraw.Draw(probe)
    f_name, f_tag, f_line = (ImageFont.truetype(str(FONT_BOLD), 60), ImageFont.truetype(str(FONT_SEMI), 24),
                             ImageFont.truetype(str(FONT_SEMI), 29))
    rows = [(x + 40, 228 + k * 56, line) for k, line in enumerate(FEATURES)]
    right = max([x + 112 + d.textlength("Tatrak", font=f_name), x + d.textlength(TAGLINE, font=f_tag)] +
                [rx + d.textlength(t, font=f_line) for rx, _, t in rows])
    box = (x, 50, right, rows[-1][1] + 34)
    img = background(6, CARDS[0][4], CARDS[1][5], box, size, glows=((0.85, 0.5, 320, 200), (0.05, 0.05, 260, 110)),
                     nodes=((50, 55), (16, 95)))
    icon = Image.open(ICON).convert("RGBA").resize((92, 92), Image.LANCZOS)
    img.alpha_composite(icon, (x, 50))
    d = ImageDraw.Draw(img)
    d.text((x + 112, 54), "Tatrak", font=f_name, fill=TEXT)
    d.text((x, 160), TAGLINE, font=f_tag, fill=(170, 182, 204))
    for k, (rx, ry, line) in enumerate(rows):
        accent, cy = CARDS[k][4], ry + 17
        d.ellipse([x + 4, cy - 10, x + 24, cy + 10], outline=accent + (255,), width=2)  # a hub node
        d.ellipse([x + 9, cy - 5, x + 19, cy + 5], fill=accent + (255,))
        d.text((rx, ry), line, font=f_line, fill=TEXT)
    return img.convert("RGB")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("captures", type=Path, nargs="?", help="omit to write only the feature graphic")
    p.add_argument("--out", type=Path, default=REPO, help="root to write fastlane/ under (default: the repo)")
    p.add_argument("--no-camera", dest="camera", action="store_false",
                   help="the captures already show a punch-hole camera")
    a = p.parse_args()
    store_images = a.out / STORE
    store_images.mkdir(parents=True, exist_ok=True)
    banner().save(store_images / "featureGraphic.png", optimize=True)
    print(store_images / "featureGraphic.png")
    if a.captures is None:
        return
    shots = [a.captures / f"{n:02d}.png" for n in range(1, len(CARDS) + 1)]
    missing = [s.name for s in shots if not s.is_file()]
    if missing:
        sys.exit(f"missing captures in {a.captures}: {', '.join(missing)}")
    store = store_images / "phoneScreenshots"
    store.mkdir(parents=True, exist_ok=True)
    for i, s in enumerate(shots):
        img = card(i, Image.open(s), a.camera)
        img.save(store / f"{i + 1:02d}.png", optimize=True)
        print(store / f"{i + 1:02d}.png")


if __name__ == "__main__":
    main()
