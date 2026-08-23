#!/usr/bin/env bash
#
# Make the local emulator match CI's, so device-dependent test failures are
# found here instead of three CI round-trips later.
#
# Three differences have already cost real time on this repo:
#   timezone      local Asia/Kolkata rendered a fixture time as "5:30 PM";
#                 CI's UTC rendered one containing "2", and a
#                 `onNodeWithText("2", substring = true)` matcher then hit two
#                 nodes instead of one.
#   screen height local 1080x2400 put a Leave button above the fold; CI's
#                 shorter default put it below, so a click never landed.
#   profile       CI's AVD had no pinned profile at all, so nobody could say
#                 what device the failures were even on.
#
# Run this before pushing anything that touches an instrumented test.
set -euo pipefail
S="${1:-$(adb devices | awk '/\tdevice$/{print $1; exit}')}"
[ -n "$S" ] || { echo "no device; start one with scripts/android-emulator.sh" >&2; exit 1; }

echo "==> matching CI on $S"
# CI runners are UTC.
adb -s "$S" shell "su 0 setprop persist.sys.timezone UTC" 2>/dev/null \
  || adb -s "$S" shell "setprop persist.sys.timezone UTC" 2>/dev/null \
  || echo "    (could not set timezone; note it in any test that formats a clock)"
# pixel_6 geometry, which is what ci.yml pins.
adb -s "$S" shell wm size 1080x2400 >/dev/null
adb -s "$S" shell wm density 420 >/dev/null

echo "==> now: $(adb -s "$S" shell wm size | tr -d '\r'), $(adb -s "$S" shell wm density | tr -d '\r')"
echo "==> tz:  $(adb -s "$S" shell getprop persist.sys.timezone | tr -d '\r')"
cat <<'NOTE'

Also worth running before a push, because pixel_6 parity alone will not catch a
height dependency:

    adb shell wm size 1080x1280   # a deliberately short screen
    ./gradlew :app:connectedDebugAndroidTest
    adb shell wm size reset

Anything that only passes at one height is asserting on layout luck.
NOTE
