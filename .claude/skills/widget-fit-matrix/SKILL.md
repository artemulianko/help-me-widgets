---
name: widget-fit-matrix
description: Prove a Help Me widget fits at every display size and font size on the real home screen. Screenshots the pinned widget at each density × font-scale combination, crops it, restores the settings, and builds one contact sheet to inspect for clipping, truncation and broken hierarchy. Use after any widget layout, text or sizing change, and before every release.
---

# Widget fit matrix

Layouts are fitted in dp, but users change **display size** (density) and **font size** (font scale). Each of these has broken a widget here: Weather's hourly temperatures clipped at a large display size, Stocks' change chips truncated at 1.3×, and the clock's labels outgrew its time at 2×. Render tests run at one configuration, so this skill checks the real launcher across all of them.

## 1. Prerequisites

- The `pixel17` emulator is running (see `e2e-test` step 1) and the widget is **pinned** (e2e-test step 3), at the size you want to check. Pin several sizes if the change affects several.
- Install the build you're checking (debug for iteration, **release before shipping**).

## 2. Run

```bash
.claude/skills/widget-fit-matrix/scripts/fit_matrix.sh <package> [out_dir] ["density:font ..."]
```

Default combinations: `420:0.85` (small display, small font), `480:1.0` (default), `540:1.15` (large display), `480:1.3` (large font), `560:2.0` (largest of both). The script:
- refuses to run if no widget of `<package>` is pinned,
- opens the app at each setting so every widget re-renders, then swipes home pages until it finds the widget (widget views report the app's own package in the launcher's view tree),
- saves `<label>.png` per combination and `sheet.png` (full screen + full-resolution crop per row),
- **always restores** `wm density reset` and `font_scale 1.0`, even on Ctrl-C.

Takes about 20 s per combination.

## 3. Inspect `sheet.png` (actually look at it)

For every row:
- [ ] Nothing is clipped at an edge, and no text ends in "…" unless that's a designed fallback.
- [ ] The **hierarchy** holds: the hero (time, temperature, price) is still the largest thing, and labels don't outgrow it.
- [ ] Spacing and alignment stay consistent: 16 dp edges, shared center lines, no overlaps.
- [ ] The default row (`480 · 1.0`) is **pixel-identical to before your change**, unless you meant to change it.
- [ ] Sibling widgets on the same page still look like one family.

## 4. Fix patterns that worked

- Text measured in dp must be drawn at the same size: cap font scaling (the clock caps labels at 1.15×) and draw with `COMPLEX_UNIT_DIP` at `sp × cappedScale`, or divide the font scale back out of Glance `sp` sizes.
- Fit text to the widest possible value (every weekday and month for live dates), with shorter-format fallbacks instead of ellipsis.
- Choose layouts by shape (width/height ratio), not height alone, so other launcher grids don't flip layouts.
- After fixing, re-run this skill and the widget's `WidgetRenderTest`.

## 5. Launcher grids (manual, once per release)

Grid changes reflow and **shrink** pinned widgets, so check them by hand: long-press an empty home-screen spot → Wallpaper & style → Layout → Small / Large / XL → Apply. Screenshot each, then restore **Medium**, and re-pin or resize widgets afterwards. Render tests cover grid cell sizes (`grid5_*`, `grid4x4_*` in the clock's `WidgetRenderTest`); copy those sizes to other widgets.

## Report

The sheet path, each failing row with what's wrong, the fix, and the re-run sheet. Say which sizes were pinned, since unpinned sizes weren't checked by this run.
