# Help Me Calendar

A calm, Material You calendar widget for Android — built for Pixel, styled after iOS and One UI, with zero bloat and per-widget calendar filtering.

## It's just a widget

No feeds, no accounts, no ads, no notifications, and **nothing is ever sent to the internet** (the app has no `INTERNET` permission and reads only your phone's local `CalendarContract`). It exists solely to preview, add, and customize your widgets.

## Download

**[⬇ Download Help Me Calendar 0.1.5 (APK)](https://github.com/artemulianko/help-me-widgets/releases/download/calendar-v0.1.5/help-me-calendar-0.1.5.apk)** · Android 12+ · 3.6 MB · [release notes](https://github.com/artemulianko/help-me-widgets/releases/tag/calendar-v0.1.5) · [all releases](https://github.com/artemulianko/help-me-widgets/releases)

SHA-256 `d815fcd91b6a79f794fbd309f8ee15b7366cae11fbf7eed341842dfe66533725`

1. Download the APK on your phone and open it.
2. Open **Help Me Calendar** once and allow **Calendar access** (read-only, stays on your phone).
3. Tap **Add to home screen** (or long-press the home screen → **Widgets** → **Help Me Calendar**). Long-press the widget → **Customize** to pick which calendars it shows, colors, and opacity.

## Features

- **Per-widget calendar filter:** Show all visible calendars, or pick any combination per widget (e.g. one widget for Personal, another for Work).
- **Vertical connected date rail (`4×2`):** Today's date on the left connected down to upcoming days with real calendar-color event dots, paired with your agenda on the right.
- **Mini month grid (`4×3` / `4×4`):** Full month overview with today highlighted and calendar-color dots under days with events, plus upcoming events below.
- **Every Pixel size:** Tailored layouts for `1×1`, `2×1`, `4×1`, `1×2`, `2×2`, `2×3`, `4×2`, `4×3`, `4×4`, and landscape.
- **Material You + 7 fixed palettes:** Dynamic wallpaper colors (following light/dark and wallpaper changes without re-rendering), background tone, and 0–100% background opacity.
- **Zero internet & invisible in battery stats:** Reads local `CalendarContract` on calendar edits, midnight rollover, and non-wakeup 30-min system redraws.

## Build from source

```bash
cd help-me-calendar
./gradlew testDebugUnitTest assembleRelease -PallowDebugSigning=true
```
