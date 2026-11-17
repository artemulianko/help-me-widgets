---
paths:
  - "help-me-*/app/src/main/java/**/data/**"
  - "help-me-*/app/src/main/java/**/work/**"
---

# Data, network and refresh rules

- All network goes through `Http.kt`: `HttpURLConnection`, bounded bodies (2 MB, 16 KB on errors), a 30 s deadline. No new HTTP or JSON libraries.
- One refresh entry point (`<X>Repository.refresh`), serialized by a `Mutex`. It decides whether anything needs fetching (fresh cache, market closed, etc.) before touching the network.
- Quota guard: a forced refresh reuses data fetched under 60 s ago. Never write loops, retries or tests that hammer a keyed API (the Google Weather key belongs to the user).
- Always rethrow `CancellationException`. A cancelled job isn't an error and must not log `[ERROR]`.
- Spinner state is a timestamp that expires. Clear it in `finally` under `withContext(NonCancellable)`.
- WorkManager: unique work only (`periodic-refresh`, `refresh-now`, `refresh-follow-up`). Periodic work needs network + battery-not-low. Manual work is expedited with `RUN_AS_NON_EXPEDITED_WORK_REQUEST`. Never add alarms, foreground services or wakelocks.
- Sanitize and validate everything from the network (timestamps, NaN prices, missing fields) before caching.
- Privacy: round coordinates to 2 decimals before storing or sending. Redact keys and errors in `AppLog` and in stored error strings.
- Log meaningful events in the `[datetime] [LEVEL] Tag: message` format: start/skip/done with the source and timing, failures with a human reason. Don't log every render.
