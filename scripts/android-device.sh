#!/usr/bin/env bash
# Connect a physical Android device over Wi-Fi and install the debug build.
#
# Wireless debugging (Android 11+) needs a one-time pairing per machine:
#   On the phone: Settings → Developer options → Wireless debugging → ON
#                 → "Pair device with pairing code"
#   That screen shows an IP:PORT and a 6-digit code. Note that the PAIRING
#   port differs from the CONNECT port shown on the parent screen — pairing
#   uses the one in the dialog, connecting uses the one behind it.
#
# Usage:
#   scripts/android-device.sh pair    <ip:pairing_port> <code>
#   scripts/android-device.sh connect <ip:connect_port>
#   scripts/android-device.sh install            # installs on every connected device
#   scripts/android-device.sh status
set -euo pipefail
cd "$(dirname "$0")/.."

case "${1:-status}" in
  pair)
    [ $# -eq 3 ] || { echo "usage: $0 pair <ip:pairing_port> <code>" >&2; exit 2; }
    adb pair "$2" "$3"
    echo "Paired. Now: $0 connect <ip:connect_port>  (the port on the Wireless debugging screen itself)"
    ;;
  connect)
    [ $# -eq 2 ] || { echo "usage: $0 connect <ip:connect_port>" >&2; exit 2; }
    adb connect "$2"
    adb devices -l
    ;;
  install)
    (cd android && ./gradlew :app:installDebug)
    for s in $(adb devices | awk '/\tdevice$/{print $1}'); do
      adb -s "$s" shell am start -n dev.supermessage/.MainActivity >/dev/null 2>&1 \
        && echo "$s: launched"
    done
    ;;
  status)
    adb devices -l
    for s in $(adb devices | awk '/\tdevice$/{print $1}'); do
      printf '%s: ' "$s"
      adb -s "$s" shell pm list packages 2>/dev/null | grep -q supermessage \
        && echo "supermessage installed" || echo "NOT installed"
    done
    ;;
  *) echo "unknown: $1" >&2; exit 2 ;;
esac
