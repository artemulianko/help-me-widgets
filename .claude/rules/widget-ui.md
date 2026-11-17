---
paths:
  - "help-me-*/app/src/main/java/**/widget/**"
  - "help-me-*/app/src/main/res/layout/**"
  - "help-me-*/app/src/main/res/xml/*widget_info*.xml"
---

# Widget UI rules

- Material 3 widget specs: 16 dp padding, `android.R.dimen.system_app_widget_background_radius`, 48 dp touch targets, edge-to-edge background at every size.
- Design and test at real Pixel cells (~97 dp per column, ~105 dp per row; 4×2 ≈ 387×212 dp; landscape ≈ 836×105 dp). Every size from 1×1 to 4×4 needs a layout that looks deliberate.
- Nothing scrolls. No lazy lists or collections. Max 10 children per Glance container.
- Colors come from `widgetColors(cfg)`. Dynamic colors must stay **resource-backed** (`DynRes`) so they follow wallpaper and dark mode without a re-render. Never hard-code a color in a layout.
- Opacity affects the background only.
- Rendering goes only through `WidgetRenderer.render()` / `renderAll()` (session-free `compose()` → `updateAppWidget`). Never call Glance's `update()` / `updateAll()`, since that starts a Glance session and holds a wakelock for ~45 s.
- Click actions: `actionRunCallback<…>()` classes must keep a no-arg constructor (R8 keep rule in `proguard-rules.pro`). A refresh callback must do the work inline with a timeout and fall back to WorkManager (AGENTS.md §5).
- The static `previewLayout` / `initialLayout` XML may only use RemoteViews-safe views (no `Space`).
- After any change: render every size in light and dark (`WidgetRenderTest`), look at the PNGs, and update `docs/screenshots` if what users see changed.
