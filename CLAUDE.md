@AGENTS.md

## Claude Code specifics

- **Skills** (`.claude/skills/`, invoke by name):
  - `e2e-test`: boot the `pixel17` emulator, install, pin the widget through the real launcher dialog, tap it, read the in-app Logs, screenshot light and dark.
  - `release-widget`: version bump → tests → release build → on-device check → notes + SHA-256 → GitHub Release (only when asked).
  - `new-widget`: scaffold a new `help-me-<name>/` sibling that follows every rule here.
  - `widget-design-review`: the design checklist to run on any widget or editor UI change.
  - `security-review`: a Codex/second-model review of a clean export, never including secrets.
- **Agents** (`.claude/agents/`): `widget-design-reviewer`, `e2e-tester`, `security-reviewer`. Use them for independent review after significant changes.
- **Scoped rules** (`.claude/rules/`) load automatically for the files they cover: widget code, data/refresh code, build and manifest files, and tests.
- Long commands (Gradle device tests, monkey runs, emulator boot) go in the background. Don't poll the emulator in tight loops.
- Use the emulator yourself to verify. Don't ask the user to check something you can check.
- The user often tests on a real Pixel 11 Pro and pastes in-app logs. Read them carefully: timestamps, whether "Refresh tapped" appears, whether a job started, and the process exit reasons.
