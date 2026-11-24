"""Builds app/src/main/assets/map/maitri-dark.json from OpenFreeMap's "liberty" style.

The light theme loads liberty straight from https://tiles.openfreemap.org/styles/liberty. The dark theme
needs a bundled style. This script downloads liberty and remaps every paint colour into the Maitri dark
palette: lightness is inverted, saturation muted, and neutral greys tinted plum. Sources, sprites and
glyphs still point at OpenFreeMap, so the tiles and the attribution stay the same.

Re-run after OpenFreeMap updates liberty:
    python tools/make_dark_map_style.py
"""
import colorsys
import json
import re
import sys
import urllib.request
from pathlib import Path

LIBERTY = "https://tiles.openfreemap.org/styles/liberty"
OUT = Path(__file__).resolve().parents[1] / "app/src/main/assets/map/maitri-dark.json"
PLUM_HUE = 322 / 360          # Maitri dark surfaces (#1E171E, #28212A, #36232F) sit around this hue
BACKGROUND = "#1C161C"

COLOUR = re.compile(r"^(#[0-9a-fA-F]{3,8}|rgba?\([^)]*\)|hsla?\([^)]*\))$")


def parse(value):
    v = value.strip()
    if v.startswith("#"):
        h = v[1:]
        if len(h) in (3, 4):
            h = "".join(c * 2 for c in h)
        r, g, b = (int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))
        a = int(h[6:8], 16) / 255 if len(h) == 8 else 1.0
        return r, g, b, a
    nums = [x.strip() for x in v[v.index("(") + 1:-1].split(",")]
    if v.startswith("rgb"):
        r, g, b = (float(x) / 255 for x in nums[:3])
    else:
        hue, sat, light = float(nums[0]) / 360, float(nums[1].rstrip("%")) / 100, float(nums[2].rstrip("%")) / 100
        r, g, b = colorsys.hls_to_rgb(hue, light, sat)
    a = float(nums[3]) if len(nums) > 3 else 1.0
    return r, g, b, a


def darken(value, prop):
    r, g, b, a = parse(value)
    hue, light, sat = colorsys.rgb_to_hls(r, g, b)
    if sat < 0.12:                      # greys: roads, buildings, land
        hue, sat = PLUM_HUE, 0.10
    else:                               # water, parks, highways keep their hue, muted
        sat *= 0.45
    if prop.startswith("text-halo"):
        light = 0.08                    # halos must stay dark behind light labels
    elif prop.startswith("text") or prop.startswith("icon"):
        light = 0.78 + 0.12 * (1 - light)
    else:
        light = 0.10 + (1 - light) * 0.42
    r, g, b = colorsys.hls_to_rgb(hue, min(max(light, 0), 1), min(max(sat, 0), 1))
    return f"rgba({round(r * 255)}, {round(g * 255)}, {round(b * 255)}, {round(a, 3)})"


def walk(node, prop):
    if isinstance(node, str):
        return darken(node, prop) if COLOUR.match(node.strip()) else node
    if isinstance(node, list):
        return [walk(x, prop) for x in node]
    if isinstance(node, dict):
        return {k: walk(v, prop) for k, v in node.items()}
    return node


def main():
    # OpenFreeMap refuses Python's default User-Agent.
    request = urllib.request.Request(LIBERTY, headers={"User-Agent": "maitri-style-builder/1.0"})
    with urllib.request.urlopen(request, timeout=30) as response:
        style = json.load(response)
    for layer in style["layers"]:
        paint = layer.get("paint", {})
        for prop in list(paint):
            if "color" in prop:
                paint[prop] = walk(paint[prop], prop)
        if layer["type"] == "background":
            paint["background-color"] = BACKGROUND
        if layer["type"] == "raster":       # the Natural Earth relief raster: dim it
            paint["raster-brightness-max"] = 0.35
    style["name"] = "Maitri dark (from OpenFreeMap liberty)"
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(style, separators=(",", ":")), encoding="utf8")
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes, {len(style['layers'])} layers)")


if __name__ == "__main__":
    sys.exit(main())
