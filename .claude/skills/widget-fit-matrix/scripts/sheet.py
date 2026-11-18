#!/usr/bin/env python3
"""Builds a labelled contact sheet from fit_matrix.sh output.

Each row is one display-size x font-size combination: the full home screen (small) next to a
crop of the widget area at full resolution, so clipping and truncation are easy to spot.
Usage: sheet.py <out_dir>   (reads <out_dir>/index.tsv, writes <out_dir>/sheet.png)
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw

out = Path(sys.argv[1])
rows = [line.rstrip("\n").split("\t") for line in (out / "index.tsv").read_text().splitlines() if line.strip()]
if not rows:
    sys.exit("index.tsv is empty")

PAD, THUMB_H, CROP_W, LABEL_H = 24, 700, 1100, 40
tiles = []
for label, density, font, bounds in rows:
    shot = Image.open(out / f"{label}.png").convert("RGB")
    thumb = shot.resize((int(shot.width * THUMB_H / shot.height), THUMB_H))
    if bounds != "-":
        x1, y1, x2, y2 = map(int, bounds.split())
        m = 24
        crop = shot.crop((max(0, x1 - m), max(0, y1 - m), min(shot.width, x2 + m), min(shot.height, y2 + m)))
        if crop.width > CROP_W:
            crop = crop.resize((CROP_W, int(crop.height * CROP_W / crop.width)))
    else:
        crop = Image.new("RGB", (CROP_W, 200), (200, 60, 60))
    tiles.append((f"display {density} dpi  ·  font {font}x" + ("" if bounds != "-" else "  ·  WIDGET NOT FOUND"), thumb, crop))

width = PAD * 3 + max(t[1].width for t in tiles) + CROP_W
height = sum(LABEL_H + max(t[1].height, t[2].height) + PAD for t in tiles) + PAD
sheet = Image.new("RGB", (width, height), (238, 240, 245))
draw = ImageDraw.Draw(sheet)
y = PAD
for label, thumb, crop in tiles:
    draw.text((PAD, y + 8), label, fill=(20, 20, 30))
    y += LABEL_H
    sheet.paste(thumb, (PAD, y))
    sheet.paste(crop, (PAD * 2 + thumb.width, y))
    y += max(thumb.height, crop.height) + PAD
sheet.save(out / "sheet.png")
