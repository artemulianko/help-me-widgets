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

## Output

A short list of what passed, and for each failure the screenshot or render name, what's wrong, and the concrete fix. Fix what you can, re-render, and re-check.
