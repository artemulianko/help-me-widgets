---
name: security-review
description: Independent security review of a Help Me widget, usually by a second model (e.g. Codex CLI), run on a clean git export so secrets can never be sent. Use before a first release, after network/storage/manifest changes, or when the owner asks for a review.
---

# Security review

## 1. Make a clean export (never review the working tree)

```bash
EXPORT=$(mktemp -d); git archive HEAD <widget> | tar -x -C "$EXPORT"
grep -rnE "AIza[0-9A-Za-z_-]{35}|storePassword=.|keyPassword=." "$EXPORT" && echo "STOP: secret in export"
```

`git archive` contains only committed, non-ignored files, so `.env`, `keystore/` and `releases/` are excluded by construction.

## 2. Run the reviewer

With Codex CLI (close stdin or it hangs):

```bash
cd "$EXPORT/<widget>" && codex exec --sandbox read-only "<prompt below>" < /dev/null > review.md
```

Or use the `security-reviewer` agent in `.claude/agents/`.

Prompt focus:
- Exported components, intent and PendingIntent handling, widget-id validation in `ConfigActivity`.
- Network: bounded reads, timeouts, TLS only, input validation and sanitizing of every field.
- Storage: backup exclusion, what's persisted, redaction in logs and stored errors, no keys in logs.
- Secrets: nothing embedded in code, resources, `BuildConfig` or the release APK.
- Privacy: location precision, what each request sends, no identifiers.
- Manifest: permissions minimal, notifications and foreground service removed.
- Release config: R8 rules, signing guard.
- Rank findings Critical / High / Medium / Low with file:line and a concrete fix.

## 3. Act on it

Fix Critical and High first. Verify each fix (test or device check), re-run a verification pass on a fresh export, and summarize the findings and fixes for the owner. Weather 0.1.1 and stocks 0.1.0 both went through this with no open Critical or High findings. Keep it that way.
