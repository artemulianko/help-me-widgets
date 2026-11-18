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
- We render session-free (`compose()` + `updateAppWidget`), so advice about Glance sessions,
  `updateAppWidgetState` and `provideContent` revisions applies only in spirit.
- No `FileProvider` / `content://` images, and no new dependencies (Glance Material 3, Hilt, …)
  without the owner's approval.
- Widget refresh taps go through `RefreshActivity` (see AGENTS.md §5); don't move them back
  to a broadcast or WorkManager-only path.

To update: re-copy the folders from a newer android/skills commit and change the commit above.
