---
name: widget-perf-check
description: Prove a Help Me widget is cheap on battery and CPU. Checks background registrations (jobs, alarms, wake locks, services, permissions), measures CPU per update with the process alive, frozen and dead, and measures idle drain from battery stats on the real home screen. Use after any change to rendering, refresh, receivers or the manifest, and before every release.
---

# Widget perf check

AGENTS.md §6 promises "invisible in battery stats". This skill proves it with numbers, not reasoning.

## 1. Prerequisites

- `pixel17` emulator running, the widget **pinned and visible** on a home page (e2e-test steps 1-3).
- For real numbers, compile the installed APK the way a few days of use would: `adb shell cmd package compile -m speed -f <package>`. An `adb install`ed APK runs interpreted and verifies code on first use, which inflates every CPU number several times.
- Debug builds log `render <id>: compose N ms, update N ms` (tag `<App>Render`, debug only). Use `adb logcat -s HelpMeClockRender:D` (etc.) to time individual renders.

## 2. Run

```bash
.claude/skills/widget-perf-check/scripts/perf_check.sh <package> [idle_seconds] ['<trigger command>']
```

The **trigger** is a host command that makes the widget update. Examples:
- Clock (next alarm must actually change, so set it **earlier** than the current next alarm):
  `'"$ADB" shell am start -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR 2 --ei android.intent.extra.alarm.MINUTES $((RANDOM % 50 + 1)) --ez android.intent.extra.alarm.SKIP_UI true'`
- Weather / Stocks: tap the refresh button (`'"$ADB" shell input tap <x> <y>'`), but mind the 61 s quota guard and never loop keyed APIs.

The script prints ✓/✗ for registrations, CPU per update in each process state (with the no-trigger baseline subtracted), idle process CPU, and the battery attributed to the app versus the top consumer. It always runs `dumpsys battery reset` on exit.

## 3. Budgets (fail the change if exceeded)

| Measure | Budget |
|---|---|
| Scheduled jobs | 0 for the clock; 1-2 unique works for refreshing widgets |
| Pending alarms, wake locks held, running services | 0 |
| Permissions | only those listed in AGENTS.md §2.4 |
| Idle process CPU (home screen, minutes) | ~0 ms; the launcher draws `TextClock`/RemoteViews, not us |
| Idle battery attributed | orders of magnitude below the launcher |
| One update | a single `compose()` + `updateAppWidget`. Glance composes once per size the launcher reports (often 2-3, incl. landscape), so expect a few hundred ms on the emulator and much less on a Pixel. It must not repeat by itself |

## 4. Gotchas learned the hard way

- **Delivery is batched.** Manifest broadcasts to background apps (e.g. `NEXT_ALARM_CLOCK_CHANGED`) can arrive ~20 s late; launchers also apply updates to off-screen widgets lazily. Wait before concluding an update "didn't happen", and check `dumpsys activity broadcasts history` for the receiver's `DELIVERED scheduled +Ns` line.
- **Frozen apps still get manifest broadcasts**: Android thaws the process, delivers, and refreezes it. The script measures this case; don't skip it.
- **Monkey ANRs** on an uncompiled APK on a loaded emulator are often environmental. Compare cold starts with a sibling (`am start -W`), compile with `speed`, and re-run the same seed before blaming app code. Still keep renders off the main thread.
- Never measure right after opening the app: its own screen work pollutes the number (the script waits 15 s and subtracts a baseline).

## Report

The script's full output, the budget table with pass/fail, and anything you couldn't measure (e.g. App Standby deferral on the owner's real Pixel).
