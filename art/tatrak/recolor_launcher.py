#!/usr/bin/env python3
# Usage: python3 art/tatrak/recolor_launcher.py src/conversations/res/mipmap-*/new_launcher*.png (needs Pillow; run once on the upstream green PNGs).
# Recolour the Conversations green (#4BAE4F) launcher PNGs to Tatrak blue (#1682E8), keeping white and shadows.
import colorsys, sys
from PIL import Image
src = colorsys.rgb_to_hsv(0x4B/255, 0xAE/255, 0x4F/255)
dst = colorsys.rgb_to_hsv(0x16/255, 0x82/255, 0xE8/255)
for path in sys.argv[1:]:
    im = Image.open(path).convert("RGBA"); px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            h, s, v = colorsys.rgb_to_hsv(r/255, g/255, b/255)
            if s < 0.12 or abs(h - src[0]) > 0.12: continue  # white, grey shadow, non-green
            h2 = dst[0]; s2 = min(1, s * dst[1] / src[1]); v2 = min(1, v * dst[2] / src[2])
            r2, g2, b2 = colorsys.hsv_to_rgb(h2, s2, v2)
            px[x, y] = (round(r2*255), round(g2*255), round(b2*255), a)
    im.save(path, optimize=True)
