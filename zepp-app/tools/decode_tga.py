"""Decodes the palette TGAs that Zeus's PNG2TGA step writes into a build (they keep the .png name)
back into normal PNGs, to see what the watch will actually draw:

    python -I tools/decode_tga.py <converted.png> <out.png> [--bgra|--rgba]

Header: standard 18-byte TGA (colour-mapped, 8 bpp, 32-bit palette entries) with an image-id block.
"""
import struct
import sys

from PIL import Image

src, dst = sys.argv[1], sys.argv[2]
order = sys.argv[3] if len(sys.argv) > 3 else "--bgra"
data = open(src, "rb").read()
id_len, cmap_type, img_type = data[0], data[1], data[2]
cmap_first, cmap_len, cmap_bits = struct.unpack_from("<HHB", data, 3)
w, h, bpp, desc = struct.unpack_from("<HHBB", data, 12)
off = 18 + id_len
entry = cmap_bits // 8
palette = []
for i in range(cmap_len):
    e = data[off + i * entry: off + (i + 1) * entry]
    if order == "--bgra":
        b, g, r, a = e
    else:
        r, g, b, a = e
    palette.append((r, g, b, a))
off += cmap_len * entry
print(f"type {img_type} cmap {cmap_len}x{cmap_bits}bit size {w}x{h} bpp {bpp} desc {desc:#x}")
im = Image.new("RGBA", (w, h))
px = im.load()
top_down = desc & 0x20
for y in range(h):
    row = data[off + y * w: off + (y + 1) * w]
    yy = y if top_down else h - 1 - y
    for x in range(w):
        px[x, yy] = palette[row[x]]
im.save(dst)
cols = sorted(set(palette[i] for i in data[off: off + w * h]), key=lambda c: -c[3])[:6]
print("sample colours:", cols)
