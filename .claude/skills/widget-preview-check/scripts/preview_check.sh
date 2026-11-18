#!/usr/bin/env bash
# Check a Help Me widget's in-app and editor previews on the installed build (use release).
#
#   preview_check.sh <package> [out_dir]
#
# Needs: emulator running, the app installed, at least one widget pinned (for the editor),
# python3 with Pillow. Doesn't save any editor change (closes the editor with Back).
set -uo pipefail

PKG="${1:?usage: preview_check.sh <package> [out_dir]}"
OUT="${2:-${TMPDIR:-/tmp}/preview-check-$PKG}"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
ADB="$SDK/platform-tools/adb"
mkdir -p "$OUT"
fail=0

dump() {
  for _ in 1 2 3 4; do
    "$ADB" shell rm -f /sdcard/pc.xml >/dev/null 2>&1
    "$ADB" shell uiautomator dump /sdcard/pc.xml >/dev/null 2>&1
    local x; x="$("$ADB" shell cat /sdcard/pc.xml 2>/dev/null)"
    case "$x" in *"<hierarchy"*) printf '%s' "$x"; return;; esac
    sleep 1
  done
}
# bounds of the node with resource-id $1, as "x1 y1 x2 y2"
bounds() { dump | tr '>' '\n' | grep "resource-id=\"$1\"" | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' '; }
# Like bounds, but scrolls the screen down until the node appears (lists below the fold).
find_scroll() {
  local b w h; read -r w h < <("$ADB" shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr 'x' ' ')
  for _ in 1 2 3 4 5 6 7 8; do
    b="$(bounds "$1")"; [ -n "$b" ] && { echo "$b"; return; }
    "$ADB" shell input swipe $((w / 2)) $((h * 4 / 5)) $((w / 2)) $((h * 2 / 5)) 300; sleep 1
  done
}
center_tap() { read -r a b c d <<< "$1"; "$ADB" shell input tap $(((a + c) / 2)) $(((b + d) / 2)); }
shot() { "$ADB" exec-out screencap -p > "$OUT/$1.png"; }
top_pkg() { # retried: it reads empty while an activity transition is running
  local t; for _ in 1 2 3 4 5; do
    t="$("$ADB" shell dumpsys activity activities | grep -m1 topResumedActivity | grep -oE 'u0 [a-z0-9_.]+' | cut -d' ' -f2)"
    [ -n "$t" ] && { echo "$t"; return; }; sleep 1
  done; }

"$ADB" logcat -c
echo "== App home (cold start)"
"$ADB" shell am force-stop "$PKG"
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
home_b="$(bounds widget_preview)"; shot home
[ -n "$home_b" ] && echo "  ✓ preview present at [$home_b]" || { echo "  ✗ no widget_preview on the home screen"; fail=1; }

echo "== Editor"
row="$(find_scroll widget_row_0)"
if [ -z "$row" ]; then
  echo "  - no pinned widget: editor checks skipped (pin one, see e2e-test)"
else
  center_tap "$row"; sleep 5
  ed_b="$(bounds widget_preview)"; shot editor_before
  [ -n "$ed_b" ] && echo "  ✓ editor preview present" || { echo "  ✗ no editor preview"; fail=1; }
  dark="$(find_scroll theme_2)"
  # Scrolling moved the preview? It's pinned above the list in every editor; re-read it.
  sleep 2; ed_b="$(bounds widget_preview)"; [ -n "$dark" ] && shot editor_before
  if [ -n "$dark" ]; then
    center_tap "$dark"; sleep 3; shot editor_after
    python3 - "$OUT" "$ed_b" <<'PY' || fail=1
import sys
from PIL import Image, ImageChops
out, b = sys.argv[1], list(map(int, sys.argv[2].split()))
a = Image.open(f"{out}/editor_before.png").convert("RGB").crop(b)
c = Image.open(f"{out}/editor_after.png").convert("RGB").crop(b)
# 1.5%: a transparent widget only recolors its text (~3%); a minute tick changes <1%.
diff = ImageChops.difference(a, c).convert("L").point(lambda v: 255 if v > 40 else 0)
changed = sum(diff.histogram()[255:]) / (diff.width * diff.height)
print(f"  {'✓' if changed > 0.015 else '✗'} preview updated after Theme → Dark ({changed:.0%} of pixels changed)")
sys.exit(0 if changed > 0.015 else 1)
PY
    auto="$(bounds theme_0)"; [ -n "$auto" ] && center_tap "$auto"; sleep 1
  else
    echo "  - no theme control (tag theme_2) found: update check skipped"
  fi
  if [ -n "$ed_b" ]; then
    center_tap "$ed_b"; sleep 3
    t="$(top_pkg)"; [ "$t" = "$PKG" ] && echo "  ✓ preview tap: stayed in $PKG" || { echo "  ✗ preview tap opened $t"; fail=1; "$ADB" shell input keyevent BACK; }
  fi
  "$ADB" shell input keyevent BACK; sleep 1
fi

echo "== Preview errors in logs"
# AppLog entries tagged Preview (mirrored to logcat in debug builds only; on release, read the
# in-app Logs screen instead).
errs="$("$ADB" logcat -d | grep -E "\] \[Preview\] |: \[Preview\] " | tail -5)"
[ -z "$errs" ] && echo "  ✓ none" || { echo "$errs" | sed 's/^/  ✗ /'; fail=1; }

python3 - "$OUT" "$home_b" "${ed_b:-}" <<'PY'
import sys, os
from PIL import Image, ImageDraw
out = sys.argv[1]
parts = [("app home", "home", sys.argv[2]), ("editor before", "editor_before", sys.argv[3]), ("editor after Dark", "editor_after", sys.argv[3])]
tiles = []
for label, name, b in parts:
    p = f"{out}/{name}.png"
    if not os.path.exists(p) or not b.strip():
        continue
    im = Image.open(p).convert("RGB").crop(tuple(map(int, b.split())))
    tiles.append((label, im))
if tiles:
    W = max(t.width for _, t in tiles) + 40
    H = sum(t.height + 50 for _, t in tiles) + 20
    s = Image.new("RGB", (W, H), (238, 240, 245)); d = ImageDraw.Draw(s); y = 20
    for label, t in tiles:
        d.text((20, y), label, fill=(20, 20, 30)); y += 24; s.paste(t, (20, y)); y += t.height + 26
    s.save(f"{out}/preview_sheet.png"); print(f"sheet: {out}/preview_sheet.png")
PY
[ "$fail" -eq 0 ] && echo "RESULT: pass" || echo "RESULT: review the ✗ lines"
exit "$fail"
