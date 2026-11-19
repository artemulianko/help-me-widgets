# Testing guide

How to prove a change works in Help Me Widgets, for humans and AI agents. Run everything from inside a widget folder (`help-me-weather/` or `help-me-stocks/`) unless noted.

## 0. Environment

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export SDK=/opt/homebrew/share/android-commandlinetools
export PATH=$SDK/platform-tools:$SDK/emulator:$PATH
```

Boot the reference emulator (Android 17, Pixel profile, 1280×2856) in the background:

```bash
emulator -avd pixel17 -no-snapshot-save -no-boot-anim > /tmp/emu.log 2>&1 &
adb wait-for-device; until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
```

## 1. Unit tests (JVM, seconds)

```bash
./gradlew testDebugUnitTest
```

| Widget | Suites |
|---|---|
| Weather | `ParsingTest` (Google + Open-Meteo mapping, conditions, sanitizing), `HttpLimitsTest`, `AppLogTest` |
| Stocks | `ParsingTest` (Yahoo chart + search), `MarketTest` (sessions, stale rules, weekends, crypto), `FormatTest`, `HttpLimitsTest`, `AppLogTest` |

Write unit tests for: every parser and mapper (captured JSON fixtures, **relative** timestamps), time and market math, formatting (locales, 12/24 h, tiny and huge numbers), limits and redaction.

## 2. Render tests (device, ~1-2 min)

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.helpmestocks.WidgetRenderTest
```

`WidgetRenderTest` composes the **real** widget RemoteViews for every launcher size (1×1 … 4×4, short 4×2s, landscape) × a configuration matrix (palettes, themes, tones, opacity 0, change styles, minimal, single item, refreshing spinner) × edge states (empty, no data, loading, odd data). It inflates each like a launcher, draws it to a bitmap, asserts pixels were drawn, and writes PNGs:

```bash
adb pull /sdcard/Android/data/app.helpmestocks/files/renders ./renders
```

Gradle uninstalls the app after the run (and the PNGs with it). To keep them, install and run the instrumentation directly:

```bash
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w -e class app.helpmestocks.WidgetRenderTest app.helpmestocks.test/androidx.test.runner.AndroidJUnitRunner
```

**Look at the PNGs.** A passing render test only means "not blank". Check spacing, alignment, truncation and contrast, in light and dark.

## 3. End-to-end tests (device, real launcher)

```bash
./gradlew connectedDebugAndroidTest
```

Both widgets have a `WidgetFlowTest` (`app.helpmeweather.WidgetFlowTest`, `app.helpmestocks.WidgetFlowTest`) with the same flows:

1. **Pin → tap refresh → data arrives.** Open the app, tap `add_widget`, confirm in the **launcher's** pin dialog, find the widget's Refresh button on the home screen (swipe pages if needed), tap it, then assert `AppLog` gets "Refresh tapped" and a successful `Fetch` line with no `[ERROR]`, and that the widget shows a value.
2. **Offline → waits → recovers.** `svc wifi disable; svc data disable`, tap refresh, assert "No network", re-enable, assert the refresh ran on its own.
   **Blocked in the background → still instant.** Wi-Fi off, `am set-standby-bucket <pkg> restricted`, home screen for 60 s, `dumpsys deviceidle force-idle`; `dumpsys netpolicy` must show the app `blocked=DOZE|APP_BACKGROUND`. Tap refresh: "Refresh tapped (network ready N ms after the tap)", data within a few seconds, the spinner gone after, and **swipe the home screen during it** (nothing may block swipes). No notification may appear.
3. **Editor saves.** Open `ConfigActivity` for the widget id, toggle `sw_refresh`, Save, and assert the stored config changed.
4. **Editor rejects foreign ids.** Launch with ids `999999`, `-5`, `0` → `RESULT_CANCELED`, nothing created.
5. **Logs copy.** Copy puts the full log on the clipboard, starting with `<App name> v`, with no secrets.

Hard-won tricks:
- The launcher's confirm button has the same label as ours ("Add to home screen"). Pick the match whose `applicationPackage` isn't ours, and retry while the sheet animates in.
- RemoteViews report **our** package in the accessibility tree even though the launcher draws them. Find widget controls by `content-desc` (e.g. `By.desc("Refresh")`), not by package.
- Re-render with `showRefresh = true` before tapping, since tests share one widget.
- Wait **61 s** before a forced refresh: the quota guard reuses data younger than a minute.
- Restore wifi, data, night mode and standby bucket in `@After`.
- Stocks: a forced refresh fetches whatever the market hours, so its flow tests also pass on nights and weekends.

## 4. Manual end-to-end with adb (what agents do interactively)

Use the `e2e-test` skill. The core moves:

```bash
adb install -r releases/help-me-stocks-<v>.apk               # or app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.helpmestocks/.ui.MainActivity
adb shell uiautomator dump /sdcard/u.xml && adb shell cat /sdcard/u.xml   # find bounds by text / resource-id (test tags)
adb shell input tap X Y                                       # "Add to home screen", then the launcher's confirm
adb shell input keyevent KEYCODE_HOME; adb shell input swipe 1100 1400 200 1400 300   # find the widget's page
adb logcat -c; adb shell input tap X Y                        # tap the widget's refresh
adb exec-out screencap -p > shot.png                          # look at it
adb logcat -d | grep -E "HelpMe|AppWidgetServiceImpl|WM-"     # debug builds mirror AppLog to logcat
```

Release builds don't mirror `AppLog` to logcat (and aren't `run-as` debuggable). Read their logs through the in-app **Logs** screen (`uiautomator dump` shows the text) or use the Copy button.

**Conditions to cover when the refresh or widget path changes:**

| Condition | How |
|---|---|
| Process dead, then tap | `adb shell am kill <pkg>` (not force-stop), then tap the widget |
| Force-stopped, then tap | `adb shell am force-stop <pkg>`. Android wakes the app but may drop the first click; expect a system update instead |
| App Standby throttling | `adb shell am set-standby-bucket <pkg> restricted` (restore with `active`). The emulator doesn't fully reproduce real-phone job deferral, so say so |
| Doze | `adb shell dumpsys deviceidle force-idle` / `unforce` |
| Offline | `adb shell svc wifi disable; adb shell svc data disable` |
| Dark mode | `adb shell cmd uimode night yes` / `no`. Dynamic colors must switch with **no** new render |
| Time zone / locale | change them in Settings. `TimeChangeReceiver` must re-render |
| Reboot | `adb reboot`. Logs must say "Phone restarted", not "App restarted after being stopped" |
| Location (weather) | `adb emu geo fix <lon> <lat>`, `adb shell pm grant app.helpmeweather android.permission.ACCESS_FINE_LOCATION` (and `ACCESS_COARSE_LOCATION`) |

## 5. Release-build verification

R8 has broken features that worked in debug (the refresh button in weather 0.1.0). Before any release:

```bash
./gradlew assembleRelease            # stocks: add -PallowDebugSigning=true (no release key yet)
adb install -r releases/help-me-<widget>-<version>.apk
```

Then pin the widget, tap refresh, open the editor, change something, save, open Logs. Check that nothing reflective got stripped:

```bash
$SDK/build-tools/<ver>/dexdump -l plain <classes.dex> | grep "Class descriptor.*RefreshAction"
```

## 6. Stability and battery

```bash
adb shell monkey -p app.helpmestocks --throttle 50 -v 10000     # release build; expect 0 crashes
adb shell dumpsys jobscheduler | grep -A5 helpmestocks          # jobs, constraints
adb shell dumpsys batterystats --charged app.helpmestocks       # wakelocks should be none
adb shell dumpsys alarm | grep helpme                           # must be empty
```

Budget per refresh: ~1-2 s job, < 1 s CPU, a few KB of data. Between refreshes: nothing.

## 7. Reporting

State exactly what ran (build type, device or emulator, Android version), what passed, what failed with the output, and what couldn't be verified (real-phone-only behavior). Never claim "verified" for something you only reasoned about.
