---
paths:
  - "help-me-*/app/src/test/**"
  - "help-me-*/app/src/androidTest/**"
---

# Test rules

- Unit tests (`src/test`) are pure JVM: parsing and mapping from captured fixtures, market and time math, formatting, HTTP limits, log format. Use **relative timestamps** in fixtures (sanitizing drops stale data, so absolute dates rot).
- `WidgetRenderTest` renders the real RemoteViews at every launcher size (including landscape) × a configuration matrix × the edge states (empty, loading, refreshing, odd data), inflates them like a launcher and asserts pixels were drawn. Add a case for every new layout, option or state.
- Flow tests (`WidgetFlowTest` pattern) go through the **real launcher pin dialog**. Find its "Add to home screen" button by elimination, since package visibility hides the launcher package. Widget views report the app's own package in the accessibility tree. Before tapping, re-render with `showRefresh = true`, and swipe through home pages to find the widget.
- Assert through `AppLog` entries (e.g. "Refresh tapped", then a `Fetch` line) instead of sleeping and hoping.
- Wait at least 61 s before a forced refresh in tests: the quota guard reuses data younger than a minute.
- Restore global state in `@After` (`svc wifi enable`, `svc data enable`, standby bucket, night mode).
- Tests must never print or require API keys, and must not hit keyed APIs in loops.
- Gradle uninstalls the app after `connectedAndroidTest`, which removes pinned widgets. To keep render PNGs, run with `adb shell am instrument`.
