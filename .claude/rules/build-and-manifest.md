---
paths:
  - "help-me-*/app/build.gradle.kts"
  - "help-me-*/app/proguard-rules.pro"
  - "help-me-*/app/src/main/AndroidManifest.xml"
  - "help-me-*/gradle/libs.versions.toml"
  - "help-me-*/settings.gradle.kts"
---

# Build and manifest rules

- No new permissions without the owner's approval. `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` and `SystemForegroundService` stay removed with `tools:node="remove"`.
- Receivers stay `exported="false"`. `ConfigActivity` is the only exported activity besides the launcher activity, and it validates the widget id.
- `allowBackup="false"` and data-extraction rules that exclude everything.
- Keep the R8 keep rule for `ActionCallback` constructors. Any new class Glance or WorkManager instantiates by reflection needs the same treatment, verified on a **release** build.
- Never embed API keys in `BuildConfig`, `resValue` or resources. Keys come from the user at runtime.
- Release signing refuses the debug key unless `-PallowDebugSigning=true` is passed. Keep that guard.
- Version bump = `versionCode` +1 **and** `versionName`. Release APKs land in the git-ignored `releases/`.
- Dependencies: AndroidX/Jetpack and kotlinx only. Adding any third-party library needs a strong reason and the owner's approval.
