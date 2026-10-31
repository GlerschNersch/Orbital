#!/usr/bin/env python3
"""Generate an Orbital theme pack (.orbitalpack.json) from a mood phrase,
using the local Ollama models on this PC (Aloy).

Usage:  python make_pack.py "rainy Tokyo night" [output_dir]

The model (aloy-assistant, falling back to qwen3.5:9b) is asked for ONLY a
JSON object of colour tokens. This script validates the reply, clamps the
colours into sane saturation/lightness bands, fills defaults for anything
missing, and writes a pack file in exactly the format Orbital's importer
accepts (CustomPacks.parse). Provenance: the script prints the model that
answered and its raw reply, so what the model produced is never confused
with the clamped result.
"""

import colorsys
import json
import os
import re
import sys
import urllib.request

OLLAMA = "http://localhost:11434"
MODELS = ["aloy-assistant:latest", "qwen3.5:9b"]
HEX_RE = re.compile(r"^#(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

PROMPT = """You are designing a colour theme for a phone launcher.
Mood: "{mood}"

Reply with ONLY a JSON object, no other text, no code fences, in exactly this shape:
{{"name": "<short evocative pack name, max 30 chars>", "accent": "#RRGGBB", "glow": "#RRGGBB", "surface": "#RRGGBB", "lattice": "#RRGGBB", "font": "system" or "rajdhani", "background": "none" or "wave" or "grid"}}

Rules: accent is the main highlight colour for the mood; glow is a brighter companion; surface is a very dark background colour; lattice is a muted mid-tone of the accent family. All colours as #RRGGBB hex."""


def ask_ollama(model, mood):
    body = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": PROMPT.format(mood=mood)}],
        "stream": False,
        "format": "json",
        "options": {"temperature": 0.8, "num_predict": 400, "num_ctx": 8192},
    }).encode("utf-8")
    req = urllib.request.Request(OLLAMA + "/api/chat", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data["message"]["content"]


def extract_json(raw):
    """Parse the model's reply as JSON; tolerate code fences."""
    text = raw.strip()
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z]*\n?", "", text)
        text = re.sub(r"\n?```$", "", text).strip()
    start, end = text.find("{"), text.rfind("}")
    if start >= 0 and end > start:
        text = text[start:end + 1]
    return json.loads(text)


def norm_hex(value):
    """A valid #RRGGBB string (alpha dropped), or None."""
    if not isinstance(value, str):
        return None
    v = value.strip()
    if not HEX_RE.match(v):
        return None
    if len(v) == 9:  # #AARRGGBB -> #RRGGBB
        v = "#" + v[3:]
    return v.upper()


def hex_to_rgb(h):
    return tuple(int(h[i:i + 2], 16) / 255.0 for i in (1, 3, 5))


def rgb_to_hex(r, g, b):
    return "#%02X%02X%02X" % (round(r * 255), round(g * 255), round(b * 255))


def clamp_colour(h, s_min, s_max, l_min, l_max):
    r, g, b = hex_to_rgb(h)
    hue, light, sat = colorsys.rgb_to_hls(r, g, b)
    sat = min(max(sat, s_min), s_max)
    light = min(max(light, l_min), l_max)
    return rgb_to_hex(*colorsys.hls_to_rgb(hue, light, sat))


def scale_lightness(h, factor):
    r, g, b = hex_to_rgb(h)
    hue, light, sat = colorsys.rgb_to_hls(r, g, b)
    return rgb_to_hex(*colorsys.hls_to_rgb(hue, min(max(light * factor, 0.02), 0.95), sat))


def main():
    mood = sys.argv[1] if len(sys.argv) > 1 else "rainy Tokyo night"
    out_dir = sys.argv[2] if len(sys.argv) > 2 else os.getcwd()

    raw = None
    used_model = None
    data = None
    errors = []
    for model in MODELS:
        try:
            raw = ask_ollama(model, mood)
            data = extract_json(raw)
            used_model = model
            break
        except Exception as e:  # noqa: BLE001 - report and fall back
            errors.append(f"{model}: {e}")
    if data is None:
        print("ERROR: no model produced usable JSON. " + " | ".join(errors))
        return 1

    print(f"MODEL={used_model}")
    print(f"RAW={raw}")

    accent = norm_hex(data.get("accent"))
    if accent is None:
        accent = "#7FB2D9"  # rainy default if the model gave nothing usable
    accent = clamp_colour(accent, 0.15, 0.95, 0.30, 0.85)

    glow = norm_hex(data.get("glow")) or accent
    glow = clamp_colour(glow, 0.15, 0.95, 0.35, 0.90)

    surface = norm_hex(data.get("surface")) or "#14161C"
    surface = clamp_colour(surface, 0.0, 0.50, 0.03, 0.18)
    surface_alt = scale_lightness(surface, 0.7)

    lattice = norm_hex(data.get("lattice")) or scale_lightness(accent, 0.6)
    lattice = clamp_colour(lattice, 0.05, 0.80, 0.15, 0.70)

    name = str(data.get("name") or "").strip()
    if not name:
        name = (mood.title() + " Pack")
    name = name[:40]

    font = str(data.get("font") or "system").strip().lower()
    if font not in ("system", "rajdhani"):
        font = "system"
    background = str(data.get("background") or "none").strip().lower()
    if background not in ("none", "wave", "grid"):
        background = "none"

    pack = {
        "format": "orbital-pack",
        "version": 1,
        "name": name,
        "subtitle": "Generated by Aloy",
        "tokens": {
            "accent": accent,
            "accentNight": accent,
            "glow": glow,
            "glowNight": glow,
            "surface": surface,
            "surfaceAlt": surface_alt,
            "lattice": lattice,
        },
        "font": font,
        "background": background,
        "material": "matte",
    }
    final = json.dumps(pack, indent=2)
    print(f"FINAL={final}")

    safe = re.sub(r"[^A-Za-z0-9 _-]", "", name).strip() or "Aloy Pack"
    path = os.path.join(out_dir, safe + ".orbitalpack.json")
    with open(path, "w", encoding="utf-8") as f:
        f.write(final + "\n")
    print(f"WROTE={path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
