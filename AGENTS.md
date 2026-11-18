# AGENTS.md: Help Me Widgets

Rules for anyone changing this repository, human or AI agent. Read this file in full before your first change. The rules below came out of real bugs and real user feedback. Don't relax one without the owner's explicit approval.

Deeper references:
- [docs/design.md](docs/design.md): the design system (layout, color, type, sizes, editor).
- [docs/testing.md](docs/testing.md): unit, render, end-to-end and release testing, step by step.
- `.claude/skills/`: runnable procedures (e2e, release, new widget, design review, security review).

---

## 1. What this repository is

A family of **home screen widgets for Android**, built for Pixel and tuned on a **Pixel 11 Pro running Android 17**. Each widget is a standalone Android project in its own folder:

| Folder | Widget | Package | Data |
|---|---|---|---|
| `help-me-weather/` | Weather: temperature, condition, hourly or daily row | `app.helpmeweather` | Google Maps Platform Weather API (user's own key), Open-Meteo fallback |
| `help-me-stocks/` | Stocks watchlist with intraday charts | `app.helpmestocks` | Yahoo Finance public chart endpoints |
| `help-me-clock/` | Digital clock: time, date and the next alarm (or date + alarm with the time off) | `app.helpmeclock` | None: `TextClock` + `AlarmManager.getNextAlarmClock()`, no internet permission |

The projects share **no code and no Gradle build**. They share these rules, the design system and the same architecture. A fix to a shared pattern (rendering, refresh, logs, editor, security) must be considered for **every** widget, not only the one you're in.

## 2. Philosophy (non-negotiable)

1. **Widgets, not apps.** The app exists to configure the widget: a home screen with a live preview, an "Add to home screen" button, a per-widget editor, settings shared by all widgets, and Logs. No feeds, no detail screens, no onboarding flows, no trading, no "pro" features.
2. **No ads, no tracking, no accounts.** No analytics, crash reporting, telemetry, ad SDKs or sign-in. No identifiers sent anywhere.
3. **No notifications, ever.** `POST_NOTIFICATIONS` and `FOREGROUND_SERVICE` are removed in the manifest with `tools:node="remove"`, and so is WorkManager's `SystemForegroundService`. Nothing may add them back.
4. **Minimal permissions.** Only what the widget truly needs: `INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, plus optional coarse location for weather. Every new permission needs the owner's approval.
5. **Minimal functionality.** One job per widget, done well. Prefer removing a setting to adding one. If a setting doesn't change what you see on the home screen, it probably shouldn't exist. (Example: the "Match At a Glance" switch was removed because a tonal theme at 60-70% opacity already does it.)
6. **Clean design.** Material 3 by the guidelines, with layouts inspired by **iOS and Samsung One UI** widgets. Professional, calm, "not AI slop". See §4.
7. **Invisible in battery stats.** See §6.
8. **Consistency across the family.** Same editor structure, same color/theme/opacity controls, same Logs format, same README shape. A user with two of our widgets on one screen should feel they come from one designer.

## 3. Hard guardrails (do not break)

**Secrets**
- API keys live only in the git-ignored `.env` (e.g. `help-me-weather/.env` → `GOOGLE_WEATHER_KEY=…`). Never commit them, never embed them in `BuildConfig` or resources, never print them in output, logs or commit messages, and never send them to third-party tools.
- Weather has **no built-in key**: users paste their own in the app. Keep it that way.
- Before any push, check for leaked keys: `git grep -nE "AIza[0-9A-Za-z_-]{35}"` (the shape of a real Google key) must find nothing, and the release APK must not contain one.
- **Don't burn API quotas.** Never loop requests in tests or scripts against keyed APIs. The quota guard (a manual refresh reuses data fetched under a minute ago) is deliberate.

**Repository**
- Never commit APKs, `keystore/`, `*.jks`, `.env`, `releases/` or `.DS_Store` (all git-ignored at the root). APKs ship only through **GitHub Releases**.
- Never force-push, rewrite history, delete branches or delete remote repositories without the owner asking for that specific action.
- Commit or push only when asked. Commit messages say what changed and why. End them with the attribution line your tool requires.

**Product**
- No notifications, ads, analytics, accounts or new permissions (see §2).
- Nothing scrollable inside a widget. No `LazyColumn` or collections in widget layouts.
- No network or heavy work on the main thread. No alarms, no foreground services, no wakelocks, no registered location listeners.

**Security hardening (from the Codex security reviews, keep all of it)**
- Widget and system receivers are `exported="false"`. Only the system delivers widget broadcasts.
- `allowBackup="false"` plus data-extraction rules that exclude everything.
- `ConfigActivity` rejects widget ids that don't belong to this app (`RESULT_CANCELED`, nothing created).
- HTTP reads are bounded (2 MB body, 16 KB error body) with a 30 s deadline. No Retrofit or OkHttp, just `HttpURLConnection` with `Http.kt` helpers.
- Errors are redacted before they're stored or shown. Logcat mirroring is debug-only. Location is rounded to 2 decimals (~1 km) before it's stored or sent.
- Release builds refuse debug signing unless `-PallowDebugSigning=true` is passed explicitly.
- R8 keep rule for Glance callbacks. Without it the refresh button silently does nothing in release builds:
  `-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }`

## 4. Design rules (summary; full spec in [docs/design.md](docs/design.md))

- **Material 3 / Material You.** Dynamic colors come from the system color resources (resource-backed color state lists generated by `tools/gen_colors.py` into `DynRes.kt`), so widgets follow wallpaper and light/dark changes **without re-rendering**, even when transparent. Fixed palettes: Blue, Teal, Green, Amber, Rose, Violet, Mono.
- **Canonical widget specs.** 16 dp edge padding, system corner radius (`android.R.dimen.system_app_widget_background_radius`), 48 dp minimum touch targets, Google Sans on Pixel, edge-to-edge fill at every size.
- **Real Pixel cell sizes.** About 97 dp per column and 105 dp per row. A 4×2 is about **387×212 dp**. Design and test at those sizes, not at the theoretical minimums.
- **Breakpoints by span.** Height < 130 dp is one row (tiny / compact / strip). Width < 270 dp is two columns. Every size from 1×1 to 4×4, plus landscape (≈836×105 dp), gets a layout designed for it.
- **iOS / One UI layout language.** The big number (temperature, price) up front. A clean, edge-aligned row of detail below (hourly forecast, watchlist rows). A quiet header (location or market status) and an optional refresh button top-end.
- **Opacity applies to the background only**, never to text or icons.
- **Glance limits.** Max 10 children per container. No `Space` in static RemoteViews layouts (use `FrameLayout`). No unsupported views.
- **Every editor is the same.** A live preview over a wallpaper backdrop (so opacity is visible), then segmented M3 Expressive settings groups: Content → Appearance (Colors / Theme / Background / Opacity) → What to show → On tap → Updates & diagnostics.
- **Tap actions are configurable.** The default opens the system's natural app (Pixel Weather / Google; Yahoo Finance). Other options are this app, refresh, or nothing.

## 5. Architecture (both widgets)

**Stack.** Kotlin 2.4, AGP 9.5 (built-in Kotlin), Gradle 9.9, JDK 21, `compileSdk`/`targetSdk` 37, `minSdk` 31. Jetpack Glance 1.2 for the widget, Compose Material 3 1.5 (Expressive) for the app, WorkManager 2.11, DataStore, kotlinx-serialization. No DI framework, no networking or image libraries.

**Package layout** (`app/src/main/java/app/<pkg>/`):
- `data/`: sources (HTTP), models, `Settings.kt` (DataStore: `AppSettings` global, `WidgetConfig` per widget id, cache), `Format.kt`, `AppLog.kt`, `<X>Repository.kt` (the single refresh entry point, serialized by a `Mutex`).
- `widget/`: `<X>Widget.kt` (GlanceAppWidget + state), `Layouts.kt`, `WidgetColors.kt`, `DynRes.kt` (generated), `WidgetRenderer.kt`, `Receiver.kt`.
- `work/`: `RefreshWorker.kt` (periodic + one-time unique work).
- `ui/`: `MainActivity` (singleTask), `ConfigActivity` (editor, exported for `APPWIDGET_CONFIGURE`), `LogsActivity`, `Components.kt`.

**Session-free rendering.** Glance normally hosts a composition in a WorkManager job and holds a wakelock for ~45 s after each update. We don't use that path: `WidgetRenderer.render()` calls `GlanceAppWidget.compose(ctx, glanceId, options)` (no explicit size, so Glance returns a sized RemoteViews map for every size the launcher reports) and hands it to `AppWidgetManager.updateAppWidget`. The receiver is a plain `AppWidgetProvider`. `SizeMode.Exact`. Renders are serialized by a `Mutex`. `CancellationException` is always rethrown, never logged as an error.

**Refresh rules** (each was a shipped bug):
- **A widget tap must act immediately.** Do the refresh in the `ActionCallback` (it runs inside Glance's `goAsync()` window). Don't rely only on WorkManager: Android's App Standby can hold even expedited jobs back for minutes when the app is rarely opened, and the tap then looks dead. Fall back to the worker when offline, when the attempt fails, or when it times out (≈8 s budget).
- **A spinner must never stick.** Store the spinner state as a start timestamp that expires (90 s), clear it in `finally` with `NonCancellable`, and clear stale spinners on process start.
- **Quota guard.** A manual refresh reuses data fetched under 1 minute ago. Extra taps while a refresh is running are ignored.
- **Fetch only when something can change.** Stocks: market-hours aware, with no network on nights and weekends. Weather: cached and shared by every widget for the same location.
- One request set per refresh, shared by all widgets.

**Logs.** `AppLog` writes `filesDir/events.log` with 3-day retention and redaction. The format is exactly `[yyyy-MM-dd HH:mm:ss] [LEVEL] Tag: message`. `LogsActivity` shows it as plain text with **Copy** and **Clear**, and the copied text starts with `<App name> v<version> · Android <n> · <device>`. Log what a user needs to understand: process start and the previous exit reason (`ApplicationExitInfo`), crashes, refresh start/skip/done with the source and timing, job start/retry/stop reasons, schedule changes, reboot vs. force-stop. Don't log routine renders, and never log secrets.

## 6. Battery rules

- WorkManager only: a periodic job (network + battery-not-low constraints) and expedited one-time work for manual or catch-up refreshes. No alarms, services, wakelocks or listeners.
- Approximate (cell/Wi-Fi) location only, reusing Android's recent cached fix. No GPS, and no listener stays registered.
- A render is one short `compose()` call. Charts are drawn once as white masks and tinted, so theme switches don't need a re-render.
- Target budget per refresh: about 1-2 s of job time, under 1 s of CPU, a few KB of data. Measure it after any change to the refresh path.

## 7. Testing (summary; full guide in [docs/testing.md](docs/testing.md))

Run these before you call any change done:

| Layer | Command (from the widget folder) | What it proves |
|---|---|---|
| Unit | `./gradlew testDebugUnitTest` | Parsing, mapping, market hours, formatting, HTTP limits, log format |
| Render | `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.<pkg>.WidgetRenderTest` | Every size × config inflates like a launcher would and draws pixels. PNGs go to `/sdcard/Android/data/<pkg>/files/renders` |
| End-to-end | `./gradlew connectedDebugAndroidTest` | Real launcher pin dialog → widget → tap refresh → data arrives; offline → recovers; editor saves; foreign ids rejected; logs copy |
| Release | Build the minified release, install it, tap through the widget | R8-only bugs (the refresh button was dead in weather 0.1.0) |
| Stability | `adb shell monkey -p <pkg> --throttle 50 -v 10000` on the release build | No crashes, no activity stacking or OOM |

Rules:
- **Look at the real thing.** Change a layout → render it and look at the PNGs or a screenshot, in light **and** dark. Change refresh → tap the actual widget on the emulator home screen and read the in-app Logs.
- **Test release builds**, not only debug builds. Minification has broken features before.
- Test the conditions that bit us: offline, airplane mode, rapid taps, force-stop then tap, process killed then tap, restricted standby bucket, reboot, time zone change, permission revoked.
- Never call a change "done and verified" unless you verified it. Say what you couldn't verify, especially behavior that only shows on a real phone.

## 8. Release process

1. Bump **both** `versionCode` (+1) and `versionName` (semver) in `app/build.gradle.kts`.
2. Run unit tests and the device tests, then build the release:
   `./gradlew testDebugUnitTest assembleRelease`
   The APK is copied to `releases/help-me-<widget>-<version>.apk` (git-ignored).
3. Signing: weather uses `keystore/release.jks` (git-ignored; never commit, never print passwords). Stocks and Clock have **no release key yet**: their builds are debug-signed, so they need `-PallowDebugSigning=true` to install over earlier versions. Changing the key forces users to uninstall first, so it needs the owner's decision.
4. Install the release APK on the emulator, then tap through the widget (pin, refresh, editor, logs).
5. Write `releases/RELEASE_NOTES-<version>.md`: what's new or fixed in plain words, upgrade notes, and the SHA-256 (`shasum -a 256 <apk>`).
6. Publish only when asked: a GitHub Release in this repo tagged `<widget>-v<version>` (e.g. `stocks-v0.1.2`, `weather-v0.1.4`), with the APK attached and the notes as the body. Update the download link and SHA-256 in that widget's README.

## 9. Environment (owner's machine)

- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
- Android SDK: `/opt/homebrew/share/android-commandlinetools` (adb in `platform-tools/`, emulator in `emulator/`).
- AVD **`pixel17`** (Android 17 / API 37, Pixel 10 Pro profile, 1280×2856). Start it with `emulator -avd pixel17 -no-snapshot-save -no-boot-anim`.
- The real target is the owner's **Pixel 11 Pro on Android 17**. The emulator doesn't reproduce everything (App Standby deferral, OEM launcher quirks), so say so when a fix is emulator-verified only.

## 10. Adding a new widget

Follow `.claude/skills/new-widget/SKILL.md`. In short: a sibling folder `help-me-<name>/` that copies the structure, build setup, manifest hardening, renderer, refresh, logs, editor and tests of the existing widgets; package `app.helpme<name>`; same design system; its own detailed README; a row in the root README.

## 11. Working style for agents

- Be direct and finish the job. Iterate on the emulator until it's genuinely right, not just compiling.
- When the user reports a bug, reproduce it first (emulator + logs), then fix it, then verify the fix the same way.
- Ask before anything outward-facing or irreversible: pushing, publishing a release, deleting, force-pushing, changing signing keys, or spending API quota.
- Keep READMEs current. Each widget's README is the user-facing source of truth (pitch, download, features, sizes, battery, privacy, data, build, credits).
