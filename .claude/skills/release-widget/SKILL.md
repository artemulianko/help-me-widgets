---
name: release-widget
description: Ship a new version of a Help Me widget. Bump the version, run tests, build and verify the minified release APK on the emulator, write release notes with the SHA-256, and (only when the owner asks) publish a GitHub Release and update the README download link.
---

# Release a widget

Argument: the widget folder (`help-me-weather` or `help-me-stocks`) and the bump kind (patch by default).

1. **Clean tree.** `git status` shows only intended changes. No secrets: `git grep -nE "AIza[0-9A-Za-z_-]{35}"` finds nothing.
2. **Bump** `versionCode` (+1) and `versionName` in `<widget>/app/build.gradle.kts`.
3. **Test:**
   ```bash
   cd <widget> && ./gradlew testDebugUnitTest
   ./gradlew connectedDebugAndroidTest      # emulator must be running (see e2e-test)
   ```
4. **Build the release:**
   ```bash
   ./gradlew assembleRelease                # weather: keystore/ release key
   ./gradlew assembleRelease -PallowDebugSigning=true   # stocks: debug key, so it installs over previous builds
   ```
   Output: `releases/help-me-<name>-<version>.apk` (git-ignored). Check the signer matches the previous release (`apksigner verify --print-certs`). A different key forces users to uninstall, so ask the owner first.
5. **Verify the release build** with the `e2e-test` skill: install over the previous version, pin, tap refresh, editor save, Logs. Check that `RefreshAction` survived R8 (dexdump).
   Then, on the same release build: `widget-preview-check` (previews draw and update live), `widget-fit-matrix` (every display size × font size), and `widget-perf-check` (registrations, CPU per update, idle drain). All three must pass; attach their sheets/output to your report.
6. **Check for leaked keys** in the APK: `unzip -p <apk> 'classes*.dex' | strings | grep -cE 'AIza[0-9A-Za-z_-]{35}'` must print 0.
7. **Notes:** `releases/RELEASE_NOTES-<version>.md` in the house style: one-line summary, upgrade note ("Install over it; your settings and widgets stay."), `### Fixed` / `### New`, then `SHA-256: \`<shasum -a 256>\``.
8. **Commit** (when asked): `Help Me <Name> <version>: <what changed>`.
9. **Publish only when the owner asks:**
   ```bash
   gh release create <name>-v<version> releases/help-me-<name>-<version>.apk \
     --repo artemulianko/help-me-widgets --title "Help Me <Name> <version>" --notes-file releases/RELEASE_NOTES-<version>.md
   ```
   Then update the download link, size and SHA-256 in `<widget>/README.md` and the root README table, commit, and push.
10. Hand the APK to the owner (they install it on their Pixel 11 Pro) and say what was and wasn't verified on a real phone.
