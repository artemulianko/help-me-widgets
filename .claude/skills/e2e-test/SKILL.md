---
name: e2e-test
description: End-to-end test a Help Me widget on the pixel17 Android 17 emulator. Boot it, install a debug or release build, pin the widget through the real launcher dialog, tap it, read the in-app logs, and screenshot it in light and dark. Use after any widget, refresh, editor or release change, or to reproduce a user's bug report.
---

# End-to-end test a widget

Full background: `docs/testing.md`. This is the runnable procedure.

## 1. Environment and emulator

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
SDK=/opt/homebrew/share/android-commandlinetools; export PATH=$SDK/platform-tools:$SDK/emulator:$PATH
adb devices | grep -q emulator || (emulator -avd pixel17 -no-snapshot-save -no-boot-anim > "$TMPDIR/emu.log" 2>&1 &)
adb wait-for-device; until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
```

Run the boot in the background. Don't poll in tight loops.

## 2. Build and install

- Debug: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Release (what users run, so test it before shipping): `./gradlew :app:assembleRelease` (stocks: `-PallowDebugSigning=true`) → `releases/…apk`
- `adb install -r <apk>`. If the signature differs from what's installed, uninstall first. **That removes pinned widgets**, so re-pin them.

## 3. Pin the widget

```bash
adb shell am start -n <pkg>/.ui.MainActivity; sleep 3
adb shell uiautomator dump /sdcard/u.xml >/dev/null
adb shell cat /sdcard/u.xml | grep -oE 'resource-id="add_widget"[^>]*bounds="[^"]*"'
adb shell input tap <center of add_widget>; sleep 3
adb shell uiautomator dump /sdcard/u.xml >/dev/null
adb shell cat /sdcard/u.xml | grep -oE 'text="Add to home screen"[^>]*bounds="[^"]*"' | tail -1   # the launcher's button (bottom sheet)
adb shell input tap <its center>
```

Confirm with `adb shell dumpsys appwidget | grep -A3 <pkg>.widget`. The launcher puts the widget on the first page with room, often page 2: `adb shell input keyevent KEYCODE_HOME; adb shell input swipe 1100 1400 200 1400 300`.

## 4. Exercise it

- Screenshot: `adb exec-out screencap -p > shot.png`, then **look at it** (Read the PNG). Screen coordinates are 1280×2856.
- Tap refresh (top-end of the widget, e.g. `input tap 1137 274` for a top-of-page 4×2). Screenshot ~0.8 s later (the spinner should show) and again after ~6 s (new timestamp or data).
- Logs: debug builds mirror `AppLog` to logcat (`adb logcat -d | grep HelpMe`). For release builds, open the app → Logs and read the text with `uiautomator dump`.
- Expect, in order: `Widget: Refresh tapped` → `Refresh: Started (refresh button, forced)` → `Fetch: … quote(s)/Google ·/Open-Meteo ·` → `Refresh: Done`. No `[ERROR]`, no stuck spinner.

## 5. Conditions (pick those relevant to the change)

`am kill <pkg>` then tap · `am force-stop <pkg>` then tap · `am set-standby-bucket <pkg> restricted` (restore: `active`) · `svc wifi disable; svc data disable` (restore!) · `cmd uimode night yes|no` · resize via long-press · `adb reboot`.

## 6. Report

What build, which conditions, the screenshots you looked at, the log lines that prove it, and **what the emulator couldn't reproduce** (e.g. real App Standby deferral on the owner's Pixel 11 Pro). Restore the emulator state (wifi, data, night mode, standby bucket) before finishing.
