---
name: new-widget
description: Scaffold a new Help Me widget (help-me-<name>/) as a sibling of the existing ones, with the same architecture, hardening, design system, editor, logs, tests and README shape. Use when the owner asks for a new kind of widget.
---

# Create a new widget

A new widget must be **as good as and consistent with** Help Me Weather and Help Me Stocks. Read `AGENTS.md`, `docs/design.md` and `docs/testing.md` first.

## 1. Agree on scope (keep it minimal)

Write down, in one paragraph: what's shown at a glance, which layout it borrows (iOS / One UI), the data source (free and keyless preferred; a key must come from the user at runtime, never built in), what a tap opens, and what is explicitly **out of scope** (no feeds, accounts or notifications). Confirm with the owner.

## 2. Scaffold by copying a sibling

Copy `help-me-stocks/` (the cleanest template) to `help-me-<name>/`, excluding `build/`, `.gradle/`, `.kotlin/`, `releases/`, `keystore/`, `local.properties` and `.env`. Then:
- Rename the package `app.helpmestocks` → `app.helpme<name>` (namespace, applicationId, source dirs, manifest, proguard, tests, `tools/`).
- Rename the classes (`StocksWidget`, `StocksWidgetReceiver`, `StocksRepository`, `HelpMeStocksApp`, theme, strings, `copyReleaseApk` rename pattern).
- `versionCode = 1`, `versionName = "0.1.0"`.
- Keep **as is**: `Http.kt`, `AppLog.kt`, `WidgetRenderer.kt`, `WidgetColors.kt` + `DynRes.kt` + `tools/gen_colors.py`, the receivers, the manifest hardening, `proguard-rules.pro`, data-extraction rules, the signing guard, `LogsActivity`, editor components, `RefreshAction` (inline refresh + worker fallback).
- Replace: the data source and models, `Settings.kt` (keep palette, themeMode, bgTone, opacity, showRefresh, tapAction; add only what's needed), layouts, sample data, icons (`tools/import_icons.py`).

## 3. Build every size

Implement all breakpoints from `docs/design.md`, plus the empty, loading, refreshing and error states. Pre-render via `compose()`. Nothing scrolls.

## 4. Tests

- Unit tests: the parser with fixtures, formatting, `HttpLimitsTest`, `AppLogTest`.
- `WidgetRenderTest`: sizes × configs × edge states.
- `WidgetFlowTest`: port weather's five flows (pin → refresh → data, offline recovery, editor save, foreign ids rejected, logs copy).

## 5. Verify like a user

`e2e-test` skill on debug **and** release builds, then `widget-design-review` next to the sibling widgets on one home screen (light and dark), a monkey run, and a battery check.

## 6. Docs and repo

- `help-me-<name>/README.md` in the same shape as the siblings: pitch, hero, "It's just a widget", Download, Why it fits a Pixel, Every size, Battery, Privacy, Data, Build from source, Project layout, Credits, License.
- Screenshots in `help-me-<name>/docs/screenshots/` (hero light/dark, all sizes, customization).
- Add a row (with download link) to the root `README.md` table and the folder table in `AGENTS.md` §1.
- Run the `security-review` skill before the first release.
