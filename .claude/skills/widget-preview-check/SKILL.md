---
name: widget-preview-check
description: Verify every preview of a Help Me widget on the minified release build. Checks the in-app preview (appears on start, updates live on each setting change, ignores taps), the editor preview, and the launcher's widget-picker preview, with screenshots. Use after any change to widget rendering, the editor, Components.kt or the picker preview, and before every release.
---

# Widget preview check

Previews are how users judge a widget before it's on their home screen. The clock shipped with an in-app preview that stayed empty and didn't update on the owner's Pixel, even though it worked in debug on the emulator. So this check runs on the **release** build, with screenshots, every time.

## 1. Build and install the release

```bash
./gradlew assembleRelease            # stocks/clock without a key: -PallowDebugSigning=true
adb install -r releases/<widget>-<version>.apk
adb shell cmd package compile -m speed -f <package>
```

## 2. Run

```bash
.claude/skills/widget-preview-check/scripts/preview_check.sh <package> [out_dir]
```

The script opens the app cold, screenshots the home preview, opens the editor for the first pinned widget, flips **Theme → Dark** and back, screenshots before and after, taps the preview, and checks that no other app came to the foreground. It also lists any `Preview:` errors from the in-app Logs (debug builds mirror them to logcat) and builds `preview_sheet.png`.

## 3. Inspect (look at the sheet)

- [ ] **App home**: the widget preview is drawn on first open, over the wallpaper backdrop, at the default size.
- [ ] **Editor before/after**: the preview changed to the dark theme within a moment of the tap. Repeat by hand for one setting per group (colors, a Show switch, a size tab) if the change touched the editor.
- [ ] **Tap on preview**: the script prints `preview tap: stayed in <package>`. A preview must never launch the clock, a forecast or a refresh.
- [ ] **Widget picker**: long-press home → Widgets → find the app → the preview shows the real design at the default size. Generated previews (`setWidgetPreview`) are only trusted after this visual check; if in doubt, use the static `previewLayout` and remove published ones (`removeWidgetPreview`).
- [ ] **Pin dialog** ("Add to home screen" in the app) shows the same preview.

## 4. Rules that came out of real bugs

- Compose the preview the same way across the family (`Components.kt` `WidgetPreview`). A failure must be **logged** (`AppLog` tag `Preview`), never swallowed into an empty box.
- Key the preview state on the settings only (`remember(cfg)`), so it re-renders exactly on change, and not on every recomposition (a timestamp in the state restarts it constantly).
- Swallow touches on the preview: its RemoteViews actions are live.

## Report

The sheet path, each check with pass/fail, and anything only checkable on the owner's real Pixel.
