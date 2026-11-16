# Nimbus Weather

**The weather widget Pixel should have shipped with.**

The stock Pixel weather options are a one-line At a Glance and a widget you can barely customize. iOS and One UI have had proper weather widgets for years, with the temperature up front and a clean hourly row. Nimbus brings that layout to Pixel and builds it the Pixel way: Material 3, your wallpaper colors, Google's own weather icons, and Google's forecast, so the widget agrees with Pixel Weather and At a Glance.

<p align="center">
  <img src="docs/screenshots/hero.png" alt="Nimbus Weather 4×2 widget in light and dark mode" width="900">
</p>

> **It's just a widget.** No notifications (the permission is removed from the manifest), no ads, no analytics, no accounts. Location is optional.

## Download

**[⬇ Download Nimbus Weather 0.1.0 (APK)](https://github.com/artemulianko/help-me-weather/releases/download/v0.1.0/nimbus-weather-0.1.0.apk)** · Android 12+ · 4.3 MB · [release notes](https://github.com/artemulianko/help-me-weather/releases/tag/v0.1.0)

SHA-256 `c81bf47a1524fb563842c42effb92b9a2c1c4751e3847430398269cad3c2b875`

1. Download the APK on your phone and open it (allow "Install unknown apps" for your browser when asked).
2. Open **Nimbus Weather** once and allow approximate location, or skip that and pick a city.
3. Long-press the home screen → **Widgets** → **Nimbus Weather** → drag it out. Long-press the widget → **Customize** to change it.

## Why it fits a Pixel

| | |
|---|---|
| **Same forecast as your Pixel** | With a Google Maps Platform key (see [below](#use-googles-forecast-same-as-pixel-weather)) the data comes from Google's weather service, the backend behind Pixel Weather and At a Glance, so you see the same temperatures and conditions everywhere. Without a key it uses [Open-Meteo](https://open-meteo.com) (free, open data) automatically. |
| **Google's weather icons** | The exact icon set Pixel Weather uses, including night variants, with dark-mode art that switches with the system theme. A monochrome Material Symbols set that follows your accent color is also available. |
| **Matches your theme** | Material You colors come straight from the system's color resources, so the widget follows your wallpaper and light/dark mode immediately, even with a transparent background. |
| **Material 3, by the guidelines** | Follows Android's canonical widget specs: 16dp padding, system corner radius, 48dp touch targets, Google Sans on Pixel, and edge-to-edge fill at every size. |
| **Tap opens Pixel Weather** | Tapping the widget opens Google's weather (the Pixel Weather app or the Google app's weather page). You can change it to open this app, refresh, or do nothing. |

## Every size, laid out properly

The layout adapts to the real cell size the launcher reports, from 1×1 up to 4×4, plus landscape and foldables. All of these are pre-rendered, so resizing or rotating never wakes the app.

<p align="center"><img src="docs/screenshots/all-sizes.png" alt="Widget at every size from 1x1 to 4x4 and landscape" width="900"></p>

## Make it yours

<p align="center"><img src="docs/screenshots/customization.png" alt="Customization variants: styles, palettes, opacity, icon sets" width="900"></p>

Per widget, so you can run several (home city plus where you are):

- **Location**: current location (approximate, no GPS) or any city via search
- **Style**: Classic (iOS-like) or Large icon (One UI-like)
- **Forecast row**: hourly, daily, or none
- **Colors**: wallpaper colors (Material You) or Blue, Teal, Green, Amber, Rose, Violet, Mono
- **Theme**: follow the system, always light, or always dark
- **Background**: neutral, tonal, or vibrant, with opacity from 0% to 100%
- **Icons**: Google (colorful) or monochrome Material Symbols
- **Show or hide**: location name, condition, high/low, feels-like, last-updated time, **refresh button**
- **On tap**: open Google weather, open this app, refresh, or nothing

App-wide settings: °C / °F / follow regional preferences, 12-hour / 24-hour / follow system, refresh interval (30 min, 1, 2, 3 or 6 hours, or manual only), Wi‑Fi-only updates, and the data source.

<p align="center">
  <img src="docs/screenshots/app-home.png" alt="App home screen" width="270">
  &nbsp;
  <img src="docs/screenshots/editor-dark.png" alt="Widget editor in dark mode" width="270">
  &nbsp;
  <img src="docs/screenshots/widget-picker.png" alt="Adding the widget" width="270">
</p>

## Battery

Designed to be invisible in your battery stats. Measured on an Android 17 emulator:

| What | Cost |
|---|---|
| Background refresh (hourly by default) | ~1.5 s job, ~0.5 s CPU, ~7 KB of data |
| Rendering the widget | ~100 ms, one-shot |
| Between refreshes | Nothing: no alarms, no services, no wakelocks, no listeners |

How:

- **WorkManager only.** Android batches refreshes with other apps' jobs, respects Doze and App Standby, waits for a network, and **skips background refreshes on low battery**. Manual refresh always works.
- **No Glance sessions.** Jetpack Glance normally keeps a background job (and a wakelock) alive for about 45 s after every widget update. Nimbus renders straight to RemoteViews instead, so each update is a single short render.
- **Location without GPS.** Approximate (cell/Wi‑Fi) location only, reusing Android's cached fix when it's recent. No location listener ever stays registered.
- **Fetches only what's shown.** One request set per location, cached and shared by every widget; nothing is downloaded while the data is still fresh.
- **No notifications, no foreground service.** WorkManager's foreground-service path is stripped from the manifest.

## Privacy

- Location is optional and approximate. Coordinates are **rounded to about 1 km** before being sent to the weather provider.
- No analytics, crash reporting, ads or accounts. Settings stay on the device.
- Network requests go only to the weather provider you choose (Google or Open-Meteo) and to Open-Meteo's geocoding when you search for a city.

## Use Google's forecast (same as Pixel Weather)

Google only offers its weather data to other apps through the [Google Maps Platform Weather API](https://developers.google.com/maps/documentation/weather/overview), which needs your own API key. Typical personal use should stay within the free monthly usage, but check Google's current pricing before you rely on that.

1. Open the [Google Cloud Console](https://console.cloud.google.com/) and create a project (a billing account is required, even for free usage).
2. Enable the **Weather API** in **APIs & Services → Library**.
3. Create a key in **APIs & Services → Credentials → Create credentials → API key**.
4. **Restrict the key** (recommended), so it's useless if it ever leaks:
   - *API restrictions*: Weather API only.
   - *Application restrictions → Android apps*: package `app.nimbus.weather`, SHA-1 `F1:85:1D:9D:C0:E3:01:FB:77:F9:81:66:11:B9:81:2D:21:59:9B:32` (the release signing certificate; Nimbus sends these on every request).
5. In Nimbus, go to **Forecast source → Google**, paste the key, and tap **Save**.

The key is stored only on your phone. If Google is unreachable or the key is wrong, Nimbus falls back to Open-Meteo, so the widget never goes blank.

## Build from source

Requirements: JDK 21, Android SDK with platform 37, and an emulator or device running Android 12 or later.

```bash
./gradlew assembleDebug                 # debug APK
./gradlew assembleRelease               # minified release (signed with keystore/ if present, else the debug key)
./gradlew testDebugUnitTest             # parsing and mapping unit tests
./gradlew connectedDebugAndroidTest     # on-device: live network, every widget size and config rendered, UI flow
```

`WidgetRenderTest` renders the real widget at every size and in a matrix of configurations and saves PNGs to `/sdcard/Android/data/app.nimbus.weather/files/renders`.

To regenerate the icons (downloaded from Google and converted with Android's own SVG converter), run `python3 tools/import_icons.py`. To regenerate the dynamic color resources, run `python3 tools/gen_colors.py`.

### Project layout

```
app/src/main/java/app/nimbus/weather/
  data/     providers (Google, Open-Meteo), settings + cache (DataStore), location, formatting
  widget/   Glance layouts, colors, session-free renderer, receivers
  work/     WorkManager refresh scheduling
  ui/       Compose (Material 3 Expressive) app + per-widget editor
tools/      icon import, color generation, svg2vector
```

Built with Kotlin, Jetpack Glance, Compose Material 3, WorkManager and DataStore. No Retrofit, OkHttp or image libraries.

## Credits

- Weather icons © Google, from Google's weather icon set (`maps.gstatic.com/weather`). Light-theme variants have slightly deepened cloud greys for contrast on light widget backgrounds.
- [Material Symbols](https://fonts.google.com/icons) © Google, Apache License 2.0.
- Fallback weather data and geocoding by [Open-Meteo.com](https://open-meteo.com), CC BY 4.0.
- Not affiliated with Google. Pixel, Pixel Weather and Google are trademarks of Google LLC.

## License

Code: [MIT](LICENSE). Third-party assets are under their own terms (see Credits).
