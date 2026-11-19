# Google Android skills (vendored)

Five technical skills copied unmodified from Google's official
[android/skills](https://github.com/android/skills) repository (commit `c729425`,
2026-11-15, Apache-2.0; each folder carries `LICENSE.txt`):

| Skill | Use it for |
|---|---|
| `glance-appwidget-updates-and-memory` | RemoteViews bitmap memory limits, widget updates, WorkManager pitfalls in widget receivers |
| `anr-audit-and-fix` | Static ANR-risk audits and ANR trace diagnosis |
| `r8-analyzer` | Redundant or overly broad R8 keep rules (needs `pip install "protobuf>=6.33.4"`; writes only to `tmp/`) |
| `android-intent-security` | Exported components, PendingIntents, intent redirection |
| `android-permissions-security` | Permissions, protection levels, receivers, runtime permission flows |

Picked for technique only. Design and layout skills (for example `glance-canonical-layouts`)
are deliberately **not** installed: how our widgets look is defined by `docs/design.md`.

**Precedence.** `AGENTS.md` and `docs/` win over these skills. Known differences:
- Since 1.0.0 we render the standard Glance way (`GlanceAppWidgetReceiver`, `update()` /
  `updateAll()`, content that `collectAsState`s its data), so the session advice applies as is.
- No `FileProvider` / `content://` images, and no new dependencies (Glance Material 3, Hilt, …)
  without the owner's approval.
- Widget refresh taps: `actionRunCallback` that starts `RefreshService`, a `shortService`
  foreground service (AGENTS.md §5). Never open an activity from a widget tap.

To update: re-copy the folders from a newer android/skills commit and change the commit above.
