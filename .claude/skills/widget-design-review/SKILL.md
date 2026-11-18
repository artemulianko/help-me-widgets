---
name: widget-design-review
description: Review a Help Me widget or editor UI against the design system (Material 3, iOS/One UI layout, every size, light/dark, consistency with sibling widgets). Use before shipping any visual change, or when the owner says something looks off.
---

# Widget design review

Spec: `docs/design.md`. Review **rendered output**, not code alone.

## Gather the evidence

1. Run `WidgetRenderTest` and pull the PNGs (see `docs/testing.md` §2).
2. Pin the widget on the `pixel17` emulator next to the sibling widget(s) and screenshot the home screen in light and dark (`cmd uimode night yes|no`).
3. Screenshot the editor at the top and scrolled, in light and dark.

## Checklist (all must pass)

**Layout**
- [ ] Each size 1×1 … 4×4 and landscape has a deliberate layout: no clipping, no cramped or empty-feeling areas, nothing scrolls.
- [ ] 16 dp padding. Content edges line up between the header, the big number and the detail row.
- [ ] Detail rows are edge-aligned (first and last items on the content edges, even steps).
- [ ] The big number is clearly the hero. The secondary text is quieter (`onSurfaceVariant`, 12-14 sp).
- [ ] Touch targets are ≥ 48 dp. The refresh button doesn't overlap content when the header is hidden.
- [ ] Long names truncate cleanly. Huge and tiny numbers (e.g. 123,456.78 / 0.00001234) fit.

**Color and theme**
- [ ] Dynamic color follows the wallpaper and dark mode with no new render.
- [ ] Every palette × tone × theme has readable contrast (WCAG AA for text).
- [ ] Opacity 0 / 60 / 100 changes the background only, and stays legible on light and dark wallpapers.
- [ ] Icons have the right light/dark variant. Semantic colors (gain/loss) are tuned per theme.

**Consistency**
- [ ] It looks like one family with the sibling widgets: same radius, padding, header style and refresh glyph.
- [ ] The editor has the same group order, labels and controls for the shared options.
- [ ] Copy is plain and short. No jargon or exclamation marks, and nothing that sounds AI-generated.

**States**
- [ ] Empty setup, loading, refreshing (spinner), no network, error, and stale data all look intentional.

**Scale, grids and fit (each of these broke a widget once)**: run `widget-fit-matrix` for the real-launcher matrix and `widget-preview-check` for the previews; these items are what to look for in their sheets.
- [ ] Nothing is truncated or clipped at **display size** small / default / large (`adb shell wm density 420|480|540`) and **font size** 0.85 / 1.0 / 1.3 / 2.0 (`settings put system font_scale …`). Restore with `wm density reset` and `font_scale 1.0`. Default scale must look exactly as before any fix.
- [ ] Every **launcher grid** works (Wallpaper & style → Layout: Small / Medium / Large / XL). Grid changes reflow and shrink widgets, and taller rows must not flip a 1-row widget into a 2-row layout: choose layouts by shape, not by height alone.
- [ ] Text that changes without a re-render (dates rolling over at midnight, any `TextClock`) is measured for its **widest possible value** (every weekday and month), not today's.
- [ ] Measured text is drawn at the size it was measured at: if the layout caps font scaling, the text must be capped the same way (sp text in a dp-measured box overflows).
- [ ] Glance: a wrap_content `AndroidRemoteViews` can be measured short or as zero (next to a weighted `Spacer`). Give live views explicit sizes and place parts with aligned `Box` overlays.
- [ ] Optical alignment: side-by-side blocks share one center line (digits have no descenders), and attached labels hug their text (AM/PM right after "1:00", no fixed-width gap).
- [ ] Time-only / date-only modes use a calmer size than "fill the widget".

**App previews**
- [ ] The in-app preview renders on app start and **updates live** on every setting change, in the **minified release build**, not only debug.
- [ ] Previews swallow taps: their RemoteViews actions are live.
- [ ] The widget picker shows a correct preview. Generated previews (`setWidgetPreview`) must be checked in the real picker; if they can't be trusted, use the static `previewLayout` and remove published ones.

## Output

A short list of what passed, and for each failure the screenshot or render name, what's wrong, and the concrete fix. Fix what you can, re-render, and re-check.
