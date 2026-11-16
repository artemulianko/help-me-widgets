#!/usr/bin/env python3
"""Imports weather icons as VectorDrawables.

* Google weather icons (maps.gstatic.com/weather/v1) — the exact set Pixel Weather and
  At a Glance use. Light variants go to drawable/, dark ones to drawable-night/, and explicit
  *_light / *_dark copies are kept for widgets with a forced theme.
* Material Symbols Rounded (filled, Apache-2.0) for the monochrome "themed" icon style.

Conversion uses Android's own Svg2Vector (tools/svg2vector), the converter behind Android
Studio's Vector Asset tool.  Run from repo root:  python3 tools/import_icons.py
"""
import os, re, shutil, subprocess, tempfile, urllib.request

GOOGLE = ["sunny", "clear", "mostly_sunny", "mostly_clear", "partly_cloudy", "partly_clear", "mostly_cloudy",
          "mostly_cloudy_night", "cloudy", "windy_breezy", "showers", "drizzle", "scattered_showers", "heavy",
          "flurries", "snow_showers", "scattered_snow", "heavy_snow", "blowing_snow", "wintry_mix", "sleet_hail",
          "strong_tstorms", "isolated_tstorms"]
SYMBOLS = ["sunny", "clear_night", "partly_cloudy_day", "partly_cloudy_night", "cloud", "foggy", "air", "rainy_light",
           "rainy", "rainy_heavy", "weather_snowy", "cloudy_snowing", "snowing", "snowing_heavy", "weather_mix",
           "weather_hail", "thunderstorm", "refresh", "near_me", "location_on"]
RES = "app/src/main/res"

def fetch(url, path):
    with urllib.request.urlopen(url) as r, open(path, "wb") as f:
        f.write(r.read())

def mask_to_clip(path):
    """Svg2Vector can't do alpha masks; these icons only use opaque masks as clips."""
    s = open(path).read()
    if "<mask" not in s:
        return
    s = re.sub(r'<mask id="([^"]+)"[^>]*>', r'<clipPath id="\1">', s).replace("</mask>", "</clipPath>")
    s = re.sub(r'mask="url\(#([^)]+)\)"', r'clip-path="url(#\1)"', s)
    open(path, "w").write(s)

# Google draws these for colourful or dark surfaces (wallpaper, the Pixel Weather sky). On a
# neutral *light* widget container the pale clouds (#F1F3F4) nearly vanish, so light variants
# get their neutral greys deepened ~10-15%. Sun yellows and rain blues are left untouched.
LIGHT_SURFACE_GREYS = {
    "F1F3F4": "D8DDE4", "DADCE0": "B8BFC9", "E4E5E5": "C8CDD5", "DEDFDF": "C2C8D0",
    "CDD0D5": "A9B1BD", "F0F4F9": "D8DFE9", "FFFFFF": "C4D0DF",
}

def deepen_greys(path):
    s = open(path).read()
    for a, b in LIGHT_SURFACE_GREYS.items():
        s = re.sub(rf"#(FF)?{a}\b", lambda m: f"#{m.group(1) or ''}{b}", s, flags=re.I)
    open(path, "w").write(s)

def convert(src, dst):
    gradlew = os.path.abspath("gradlew")
    subprocess.run([gradlew, "-q", "-p", "tools/svg2vector", "run", f"--args={src} {dst}"], check=True)

tmp = tempfile.mkdtemp()
svg, vec = os.path.join(tmp, "svg"), os.path.join(tmp, "vec")
os.makedirs(svg)
for n in GOOGLE:
    for v in ("", "_dark"):
        p = f"{svg}/wx_{n}{v}.svg"
        fetch(f"https://maps.gstatic.com/weather/v1/{n}{v}.svg", p)
        mask_to_clip(p)
for n in SYMBOLS:
    fetch(f"https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/{n}/fill1/48px.svg", f"{svg}/ms_{n}.svg")
convert(svg, vec)

os.makedirs(f"{RES}/drawable-night", exist_ok=True)
for n in GOOGLE:
    light, dark = f"{vec}/wx_{n}.xml", f"{vec}/wx_{n}_dark.xml"
    deepen_greys(light)
    shutil.copy(light, f"{RES}/drawable/wx_{n}.xml")
    shutil.copy(dark, f"{RES}/drawable-night/wx_{n}.xml")
    shutil.copy(light, f"{RES}/drawable/wx_{n}_light.xml")
    shutil.copy(dark, f"{RES}/drawable/wx_{n}_dark.xml")
for n in SYMBOLS:
    shutil.copy(f"{vec}/ms_{n}.xml", f"{RES}/drawable/ms_{n}.xml")
print(f"imported {len(GOOGLE)} Google icons (x4) and {len(SYMBOLS)} Material Symbols")
