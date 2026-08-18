#!/usr/bin/env bash
#
# Builds the Rust core for iOS and packages it as an XCFramework.
#
# Run this whenever the FFI surface changes — a new method on `Core`, a new
# field on a DTO, a new event variant. Xcode consumes the resulting
# `.xcframework` as an ordinary binary dependency and never invokes cargo, so
# nothing regenerates behind your back: if you change Rust and do not run this,
# the app keeps the bindings it already had and you will get a link error
# rather than silent drift.
#
# The generated Swift is written into `apple/Generated` and **checked in**.
# That keeps Xcode builds hermetic and fast, and it makes a moved boundary show
# up in review as a diff rather than as a runtime surprise.
#
# Usage:  ./scripts/build-xcframework.sh [--debug]
set -euo pipefail

cd "$(dirname "$0")/.."

PROFILE="release"
PROFILE_DIR="release"
if [ "${1:-}" = "--debug" ]; then
    PROFILE="dev"
    PROFILE_DIR="debug"
fi

# The device slice and the simulator slice. No x86_64 simulator: every Mac that
# can run Xcode 16 is Apple silicon, and an unused slice is build time and disk
# for nobody.
TARGETS=(aarch64-apple-ios aarch64-apple-ios-sim)
LIB=libsupermessage_ffi.a
OUT=apple/Supermessage.xcframework
GENERATED=apple/Generated

# `--lib` matters: `cargo build -p` builds every target in the package, which
# includes the `uniffi-bindgen` binary — and a CLI binary cannot link for iOS.
# The generator is a host tool and is built for the host, further down.
#
# The deployment target is set explicitly because cargo's default for iOS is
# ancient (10.0), while the C dependencies — sqlite3, aws-lc — are compiled
# against the installed SDK. Linking those together produces a wall of
# "built for newer iOS version" warnings and, eventually, a failure. 17.0 is
# what the app targets.
export IPHONEOS_DEPLOYMENT_TARGET=17.0

for target in "${TARGETS[@]}"; do
    echo "==> building $target ($PROFILE)"
    cargo build -p supermessage-ffi --lib --profile "$PROFILE" --target "$target"
done

# Generate from the built library rather than from source: `--library` reads the
# metadata the macros actually emitted, so the Swift cannot describe a surface
# the binary does not have.
echo "==> generating Swift bindings"
rm -rf "$GENERATED"
mkdir -p "$GENERATED"
cargo run -q -p supermessage-ffi --bin uniffi-bindgen -- generate \
    --library "target/aarch64-apple-ios/$PROFILE_DIR/$LIB" \
    --language swift \
    --out-dir "$GENERATED"

# UniFFI emits `<name>FFI.modulemap`; an XCFramework wants it called
# `module.modulemap` inside the headers directory it is given.
echo "==> assembling headers"
HEADERS="$GENERATED/headers"
rm -rf "$HEADERS"
mkdir -p "$HEADERS"
mv "$GENERATED"/*.h "$HEADERS/" 2>/dev/null || true
cat "$GENERATED"/*.modulemap > "$HEADERS/module.modulemap" 2>/dev/null || true
rm -f "$GENERATED"/*.modulemap

echo "==> packaging the xcframework"
rm -rf "$OUT"
xcodebuild -create-xcframework \
    -library "target/aarch64-apple-ios/$PROFILE_DIR/$LIB" -headers "$HEADERS" \
    -library "target/aarch64-apple-ios-sim/$PROFILE_DIR/$LIB" -headers "$HEADERS" \
    -output "$OUT"

echo
echo "built $OUT"
echo "swift  $GENERATED/supermessage_ffi.swift"
echo
echo "The generated Swift is checked in on purpose. Commit it with the Rust"
echo "change that moved it, so the boundary and its bindings travel together."
