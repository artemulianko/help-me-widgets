#!/usr/bin/env python3
"""Imports Material Symbols Rounded (filled, Apache-2.0) as VectorDrawables.

Conversion uses Android's own Svg2Vector (tools/svg2vector), the converter behind Android
Studio's Vector Asset tool.  Run from repo root:  python3 tools/import_icons.py
Upstream assets can change: review the generated drawables (git diff) before committing.
"""
import os, shutil, subprocess, tempfile, urllib.request

SYMBOLS = ["alarm", "schedule"]
RES = "app/src/main/res"

MAX_SVG = 256 * 1024

def fetch(url, path):
    """Bounded, timed download; rejects anything that isn't a plain SVG (no DOCTYPE/entities)."""
    with urllib.request.urlopen(url, timeout=20) as r:
        data = r.read(MAX_SVG + 1)
    if len(data) > MAX_SVG:
        raise SystemExit(f"refusing oversized download: {url}")
    text = data.decode("utf-8")
    if "<!DOCTYPE" in text or "<!ENTITY" in text or "<svg" not in text:
        raise SystemExit(f"unexpected content from {url}")
    with open(path, "w") as f:
        f.write(text)

def convert(src, dst):
    gradlew = os.path.abspath("gradlew")
    subprocess.run([gradlew, "-q", "-p", "tools/svg2vector", "run", f"--args={src} {dst}"], check=True)

tmp = tempfile.mkdtemp()
svg, vec = os.path.join(tmp, "svg"), os.path.join(tmp, "vec")
os.makedirs(svg)
for n in SYMBOLS:
    fetch(f"https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/{n}/fill1/48px.svg", f"{svg}/ms_{n}.svg")
convert(svg, vec)
for n in SYMBOLS:
    shutil.copy(f"{vec}/ms_{n}.xml", f"{RES}/drawable/ms_{n}.xml")
print(f"imported {len(SYMBOLS)} Material Symbols")
