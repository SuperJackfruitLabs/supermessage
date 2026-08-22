#!/usr/bin/env bash
#
# Start an AVD HEADLESS, and never any other way.
#
# `-no-window` is not a preference, it is the fix for a compositor memory leak.
# Under Xwayland with software rendering the emulator pushes a continuous stream
# of frames at cosmic-comp, which allocates a buffer per frame and never frees
# them: ~54 GB/hour while it runs, and it froze this machine twice (31.5 GB with
# two AVDs up). Growth stops dead the instant the emulator exits — measured at
# +0.0 MB over 40s, with 4.1 GB returned. It is also the reason the package
# temperature sat at 100°C with the CPU otherwise idle.
#
# Headless costs nothing here: `adb exec-out screencap` still works, so UI
# checks and screenshots are unaffected, and instrumented tests never needed a
# window at all.
#
# Do NOT swap in `-gpu host` to "fix" the rendering cost: it segfaults on this
# machine (booted, then core-dumped).
#
# Usage:  scripts/android-emulator.sh [avd-name]   # default: supermessage-phone
set -euo pipefail
AVD="${1:-supermessage-phone}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
LOG="${TMPDIR:-/tmp}/emulator-$AVD.log"

# Ask adb, not pgrep. A `pgrep -f` guard here matches ANY process whose command
# line happens to contain the pattern — including the shell that is running this
# very script, if the script's own text is on that command line. That
# false-positive silently skipped the launch once already.
if adb devices 2>/dev/null | grep -q "^emulator-.*device$"; then
  echo "an emulator is already attached; not launching a second one"
  exit 0
fi

setsid nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD" \
  -no-window -gpu swiftshader_indirect -no-boot-anim -cores 2 \
  > "$LOG" 2>&1 < /dev/null &
disown

for _ in $(seq 1 60); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && {
    echo "$AVD booted (headless); log: $LOG"; exit 0; }
  sleep 5
done
echo "timed out waiting for $AVD; see $LOG" >&2
exit 1
