#!/usr/bin/env bash
# Measure what a Help Me widget costs: background registrations, CPU per update in each
# process state, and idle battery drain on the real home screen.
#
#   perf_check.sh <package> [idle_seconds] [trigger_command]
#   perf_check.sh app.helpmeweather 180
#   perf_check.sh app.helpmeclock 180 'adb shell am start -a android.intent.action.SET_ALARM \
#       --ei android.intent.extra.alarm.HOUR 5 --ei android.intent.extra.alarm.MINUTES $((RANDOM % 60)) \
#       --ez android.intent.extra.alarm.SKIP_UI true'
#
# The trigger is any host command that makes the widget update (an alarm change, a time zone
# change, a refresh tap). It runs through `eval`, so $RANDOM etc. vary per run.
# Restores the battery state on exit. Needs the widget pinned and visible on a home page.
set -uo pipefail

PKG="${1:?usage: perf_check.sh <package> [idle_seconds] [trigger_command]}"
IDLE="${2:-180}"
TRIGGER="${3:-}"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
ADB="$SDK/platform-tools/adb"
adb() { "$ADB" "$@"; }
export -f adb 2>/dev/null || true
trap '"$ADB" shell dumpsys battery reset >/dev/null 2>&1' EXIT INT TERM

"$ADB" get-state >/dev/null 2>&1 || { echo "no device" >&2; exit 2; }
APPID="$("$ADB" shell dumpsys package "$PKG" | grep -m1 -oE 'appId=[0-9]+' | cut -d= -f2)"
[ -n "$APPID" ] || { echo "$PKG is not installed" >&2; exit 3; }
UIDNAME="u0a$((APPID - 10000))"

pid() { "$ADB" shell pidof "$PKG" | tr -d '\r'; }
cpu_ms() { local p; p="$(pid)"; [ -z "$p" ] && { echo 0; return; }; "$ADB" shell cat "/proc/$p/stat" | awk '{print ($14+$15)*10}'; }
# Records look like "ProcessRecord{1a2b3c 16618:app.helpmeclock/u0a248}".
frozen() { "$ADB" shell dumpsys activity processes | grep -A60 -E "ProcessRecord\{[^}]*:$PKG/" | grep -m1 -oE 'isFrozen=(true|false)' | cut -d= -f2; }
fail=0
check() { if [ "$2" = ok ]; then printf '  ✓ %s\n' "$1"; else printf '  ✗ %s\n' "$1"; fail=1; fi; }

echo "== Background registrations ($PKG, $UIDNAME)"
jobs="$("$ADB" shell dumpsys jobscheduler | grep -cE "JOB #$UIDNAME/")"
alarms="$("$ADB" shell dumpsys alarm | grep -E '^\s+(RTC|ELAPSED)' | grep -c "$PKG")"
locks="$("$ADB" shell dumpsys power | sed -n '/Wake Locks:/,/^$/p' | grep -c "$PKG")"
services="$("$ADB" shell dumpsys activity services "$PKG" | grep -c 'ServiceRecord{')"
check "scheduled jobs: $jobs (periodic refresh widgets expect 1-2)" "$( [ "$jobs" -le 2 ] && echo ok)"
check "pending alarms: $alarms" "$( [ "$alarms" -eq 0 ] && echo ok)"
check "wake locks held now: $locks" "$( [ "$locks" -eq 0 ] && echo ok)"
check "running services: $services" "$( [ "$services" -eq 0 ] && echo ok)"
echo "  permissions:"; "$ADB" shell dumpsys package "$PKG" | sed -n '/requested permissions:/,/install permissions:/p' | grep -vE 'requested|install' | sed 's/^ */    /'

if [ -n "$TRIGGER" ]; then
  echo "== CPU per update (trigger: ${TRIGGER:0:60}…)"
  # Cost of one update = CPU over 6 s after the trigger, minus the same 6 s without it.
  measure() { # $1 = label
    local p0 b0 b1 before after p1 cost noise
    p0="$(pid)"; b0="$(cpu_ms)"; sleep 6; b1="$(cpu_ms)"; noise=$((b1 - b0))
    before="$(cpu_ms)"; eval "$TRIGGER" >/dev/null 2>&1; sleep 6
    after="$(cpu_ms)"; p1="$(pid)"
    if [ -n "$p0" ] && [ "$p0" = "$p1" ]; then cost="$((after - before - noise)) ms (baseline noise ${noise} ms)"
    else cost="$after ms total, including starting the process"; fi
    printf '  %-34s %s\n' "$1" "$cost"
  }
  "$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4
  "$ADB" shell input keyevent HOME; sleep 15   # let the app's own screen work settle
  measure "alive (cached)"
  for _ in $(seq 1 18); do [ "$(frozen)" = true ] && break; sleep 10; done
  if [ "$(frozen)" = true ]; then measure "frozen (Android thaws it)"; else echo "  frozen: process never froze in 3 min (skipped)"; fi
  "$ADB" shell am kill "$PKG" >/dev/null 2>&1; sleep 2
  [ -n "$(pid)" ] && "$ADB" shell run-as "$PKG" kill "$(pid)" >/dev/null 2>&1; sleep 2
  if [ -z "$(pid)" ]; then
    before=0; eval "$TRIGGER" >/dev/null 2>&1; sleep 8
    printf '  %-34s %s\n' "dead (process started for it)" "$(cpu_ms) ms total, including starting the process"
  else echo "  dead: couldn't kill the process (release build in the foreground?)"; fi
fi

echo "== Idle on the home screen for ${IDLE}s (unplugged)"
"$ADB" shell input keyevent HOME; sleep 2
c0="$(cpu_ms)"; p0="$(pid)"
"$ADB" shell dumpsys battery unplug >/dev/null
"$ADB" shell dumpsys batterystats --reset >/dev/null
sleep "$IDLE"
c1="$(cpu_ms)"; p1="$(pid)"
power="$("$ADB" shell dumpsys batterystats --charged | grep -m1 -E "^\s+UID $UIDNAME:" | awk '{print $3}')"
launcher="$("$ADB" shell dumpsys batterystats --charged | grep -E '^\s+UID u0a[0-9]+:' | sort -k3 -gr | head -1 | awk '{print $2, $3}')"
"$ADB" shell dumpsys battery reset >/dev/null
if [ "$p0" = "$p1" ] && [ -n "$p0" ]; then idle_cpu="$((c1 - c0)) ms"; elif [ -z "$p1" ]; then idle_cpu="0 ms (process not running)"; else idle_cpu="$c1 ms (process restarted)"; fi
check "process CPU while idle: $idle_cpu" "$( [ "${idle_cpu%% *}" -le $((IDLE * 2)) ] 2>/dev/null && echo ok)"
echo "  battery attributed: ${power:-0} mAh   (top consumer: ${launcher:-n/a} mAh)"

[ "$fail" -eq 0 ] && echo "RESULT: pass" || echo "RESULT: review the ✗ lines"
exit "$fail"
