"""Draws Hex's bubbles as smooth images (the watch draws FILL_RECT circles without anti-aliasing):
a softly shaded disc in each bubble's colour with its label in crisp text, at 140 px; the app
scales them with IMG auto_scale. The order and keys must match ITEMS in page/index.js.

Run from zepp-app/:  python -I tools/make_bubbles.py
"""
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

SIZE = 140
SS = 4  # drawn at 4x, scaled down: anti-aliased edges and text

# key, colour, label (label "" = the time bubble), shortcut (a play glyph instead of a reading)
ITEMS = [
    ("time", 0xa6e36e, "", False),
    ("heart", 0xff5a5a, "BPM", False),
    ("steps", 0x64d2ff, "STEPS", False),
    ("battery", 0xa6e36e, "BATTERY", False),
    ("weather", 0xffbe3c, "HI / LO", False),
    ("kcal", 0xff9f0a, "KCAL", False),
    ("km", 0x50dcc8, "KM", False),
    ("spo2", 0x5ac8fa, "SpO2", False),
    ("stress", 0xbf5af2, "STRESS", False),
    ("stand", 0x30d158, "STAND", False),
    ("pai", 0xff375f, "PAI", False),
    ("sun", 0xffd60a, "SUN", False),
    ("rest", 0xff6961, "REST", False),
    ("fat", 0xff9500, "FAT BURN", False),
    ("sleep", 0x7d7aff, "SLEEP", False),
    ("alarm", 0xff9f0a, "ALARM", True),
    ("workout", 0xa6e36e, "WORKOUT", True),
    ("compass", 0xff453a, "COMPASS", True),
    ("timer", 0x64d2ff, "TIMER", True),
]

here = os.path.dirname(os.path.abspath(__file__))
out_dir = os.path.join(os.path.dirname(here), "assets", "default.r", "b")
os.makedirs(out_dir, exist_ok=True)
FONT = "C:/Windows/Fonts/arialbd.ttf"


def rgb(c):
    return ((c >> 16) & 255, (c >> 8) & 255, c & 255)


def bubble(color, label, shortcut, is_time):
    S = SIZE * SS
    r, g, b = rgb(color)
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    # Disc: a dark tint of the colour, a little lighter toward the top, with a faint rim.
    base = (0x1c, 0x1e, 0x1c) if is_time else (int(r * 0.22), int(g * 0.22), int(b * 0.22))
    top = (0x2a, 0x2d, 0x2a) if is_time else (int(r * 0.34), int(g * 0.34), int(b * 0.34))
    grad = Image.new("RGBA", (S, S))
    gd = ImageDraw.Draw(grad)
    for y in range(S):
        t = y / (S - 1)
        gd.line([(0, y), (S, y)], fill=tuple(int(top[i] + (base[i] - top[i]) * t) for i in range(3)) + (255,))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, S - 1, S - 1], fill=255)
    im.paste(grad, (0, 0), mask)
    d = ImageDraw.Draw(im)
    rim = (min(255, base[0] + 40), min(255, base[1] + 40), min(255, base[2] + 40), 255)
    d.ellipse([SS, SS, S - 1 - SS, S - 1 - SS], outline=rim, width=2 * SS)
    if label:
        size = 16 if len(label) <= 6 else 13
        f = ImageFont.truetype(FONT, size * SS)
        d.text((S / 2, S * 0.3), label, font=f, fill=(r, g, b, 255), anchor="mm")
    if shortcut:
        # A play triangle where a reading would go.
        cx, cy, k = S / 2, S * 0.62, 18 * SS
        d.polygon([(cx - k * 0.6, cy - k), (cx - k * 0.6, cy + k), (cx + k, cy)], fill=(255, 255, 255, 255))
    return im.resize((SIZE, SIZE), Image.LANCZOS)


for key, color, label, shortcut in ITEMS:
    bubble(color, label, shortcut, key == "time").save(os.path.join(out_dir, key + ".png"))
print("ok", len(ITEMS))
