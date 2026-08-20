#!/usr/bin/env bash
#
# Run the dev app under a stable code signature, so the keychain remembers it.
#
# The problem this exists to solve: `tauri dev` links an **ad-hoc** binary
# (`codesign -dv` reports `flags=0x20002(adhoc,linker-signed)`). An ad-hoc
# signature has no certificate, so macOS identifies the program by its cdhash —
# the hash of that exact build. The keychain stores "Always Allow" against that
# identity, and every rebuild produces a different cdhash, so the permission you
# just granted now names a program that no longer exists. The prompt comes back
# on every single rebuild, forever, and clicking Always Allow can never fix it.
#
# Signing with a real certificate replaces the cdhash identity with one based on
# the identifier and the signing cert, both of which survive a rebuild. Grant it
# once and it stays granted.
#
# It cannot be done by signing before `tauri dev`, which is the obvious thing to
# try: cargo relinks and throws the signature away. The signature has to be
# applied after the link and before the process starts, which is why this drives
# the three steps itself instead of delegating to `tauri dev`.
#
# Usage:  ./scripts/dev-signed.sh [--features mcp]
set -euo pipefail

cd "$(dirname "$0")/.."

FEATURES=("$@")
BIN="src-tauri/target/debug/supermessage"
# Matches `identifier` in tauri.conf.json. It has to: the keychain entry is
# recorded against this, so a different string here would look like a different
# app and re-prompt.
IDENTIFIER="dev.supermessage.app"

IDENTITY="$(security find-identity -v -p codesigning \
  | awk '/Apple Development|Developer ID Application/ {print $2; exit}')"
if [ -z "${IDENTITY:-}" ]; then
  echo "No code-signing identity found." >&2
  echo "Open Xcode > Settings > Accounts and add an Apple Development certificate," >&2
  echo "or create a self-signed one in Keychain Access (Certificate Assistant)." >&2
  exit 1
fi
echo "signing identity: $IDENTITY"

# 1. The frontend, in the background — `tauri dev` would normally run this via
#    `beforeDevCommand`, and the binary expects it at the configured devUrl.
if ! curl -sf -o /dev/null --max-time 2 http://localhost:1420; then
  echo "starting vite…"
  pnpm dev >/dev/null 2>&1 &
  VITE_PID=$!
  trap 'kill "$VITE_PID" 2>/dev/null || true' EXIT
  until curl -sf -o /dev/null --max-time 2 http://localhost:1420; do sleep 0.5; done
fi

# 2. Build, then sign what was just linked.
( cd src-tauri && cargo build "${FEATURES[@]}" )
codesign --force --sign "$IDENTITY" --identifier "$IDENTIFIER" "$BIN"
codesign -dv "$BIN" 2>&1 | grep -E "Identifier|Signature=|flags" || true

# 3. Run the signed binary. The first launch after switching identities still
#    prompts once — the old ad-hoc entries in the keychain name programs that no
#    longer exist. Click Always Allow that one time; it holds from then on.
exec "$BIN"
