#!/usr/bin/env bash
# Screenshot a pinned Help Me widget on the real home screen across display sizes and font
# sizes, then build one labelled contact sheet to review.
#
#   fit_matrix.sh <package> [out_dir] ["density:font ..."]
#   fit_matrix.sh app.helpmeclock
#   fit_matrix.sh app.helpmeweather /tmp/fit "480:1.0 540:1.15 560:2.0"
#
# Needs: a running emulator/device with the widget already pinned, adb, python3 with Pillow.
# Always restores display size and font size, even on Ctrl-C or failure.
set -uo pipefail

PKG="${1:?usage: fit_matrix.sh <package> [out_dir] [\"density:font ...\"]}"
OUT="${2:-${TMPDIR:-/tmp}/fit-matrix-$PKG}"
COMBOS="${3:-420:0.85 480:1.0 540:1.15 480:1.3 560:2.0}"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
ADB="$SDK/platform-tools/adb"
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"

restore() {
  "$ADB" shell wm density reset >/dev/null 2>&1
  "$ADB" shell settings put system font_scale 1.0 >/dev/null 2>&1
}
trap restore EXIT INT TERM

"$ADB" get-state >/dev/null 2>&1 || { echo "no device: start the emulator first" >&2; exit 2; }
if ! "$ADB" shell dumpsys appwidget | grep -A3 -E '^\s+\[[0-9]+\] id=' | grep -q "ComponentInfo{$PKG/"; then
  echo "no $PKG widget is pinned: pin one first (e2e-test skill, step 3)" >&2; exit 3
fi

# The launcher's view tree reports widget views with the widget app's own package.
dump() {
  for _ in 1 2 3; do
    "$ADB" shell rm -f /sdcard/fit.xml >/dev/null 2>&1
    "$ADB" shell uiautomator dump /sdcard/fit.xml >/dev/null 2>&1
    "$ADB" shell cat /sdcard/fit.xml 2>/dev/null | grep -q "<hierarchy" && { "$ADB" shell cat /sdcard/fit.xml; return; }
    sleep 1
  done
}

# Union of the bounds of every node from $PKG, as "x1 y1 x2 y2" (empty when not on screen).
widget_bounds() {
  dump | tr '>' '\n' | grep "package=\"$PKG\"" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' |
    grep -oE '[0-9]+' | paste - - - - |
    awk 'NR==1{a=$1;b=$2;c=$3;d=$4} {if($1<a)a=$1; if($2<b)b=$2; if($3>c)c=$3; if($4>d)d=$4} END{if(NR) print a,b,c,d}'
}

find_widget_page() {
  "$ADB" shell input keyevent HOME; sleep 2
  local w h; read -r w h < <("$ADB" shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr 'x' ' ')
  for _ in 0 1 2 3 4; do
    local b; b="$(widget_bounds)"
    [ -n "$b" ] && { echo "$b"; return; }
    "$ADB" shell input swipe $((w * 9 / 10)) $((h / 2)) $((w / 10)) $((h / 2)) 300; sleep 2
  done
}

: > "$OUT/index.tsv"
for combo in $COMBOS; do
  density="${combo%%:*}"; font="${combo##*:}"
  label="density${density}_font${font}"
  echo "== $label"
  "$ADB" shell wm density "$density" >/dev/null
  "$ADB" shell settings put system font_scale "$font" >/dev/null
  sleep 4
  # Opening the app re-renders every widget at the new configuration.
  "$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4
  bounds="$(find_widget_page)"
  "$ADB" exec-out screencap -p > "$OUT/$label.png"
  if [ -z "$bounds" ]; then echo "   widget not found on any home page" >&2; bounds="-"; fi
  printf '%s\t%s\t%s\t%s\n' "$label" "$density" "$font" "$bounds" >> "$OUT/index.tsv"
done

restore
python3 "$HERE/sheet.py" "$OUT" && echo "contact sheet: $OUT/sheet.png"
