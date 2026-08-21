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
# Built in a staging directory and swapped in at the end, rather than deleting
# the checked-in bindings first. Deleting up front means any failure in here —
# a compile error, a full disk — leaves the tree with no bindings at all, and
# what should have been a failed build becomes "restore them from git".
echo "==> generating Swift bindings"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
# `--no-format` is what makes this reproducible. UniFFI shells out to
# `swiftformat` if it happens to be on PATH and silently skips it if not, so the
# same Rust produced two very different files depending on whose machine ran it
# — 2,265 insertions and 2,828 deletions of pure reformatting between a laptop
# without the tool and a CI runner whose image ships it. The bindings are
# checked in and diffed against, so "formatted by whoever last ran this" is not
# a property they can have.
cargo run -q -p supermessage-ffi --bin uniffi-bindgen -- generate \
    --library "target/aarch64-apple-ios/$PROFILE_DIR/$LIB" \
    --language swift \
    --no-format \
    --out-dir "$STAGE"

# UniFFI emits `<name>FFI.modulemap`; an XCFramework wants it called
# `module.modulemap` inside the headers directory it is given.
echo "==> assembling headers"
mkdir -p "$STAGE/headers"
mv "$STAGE"/*.h "$STAGE/headers/" 2>/dev/null || true
cat "$STAGE"/*.modulemap > "$STAGE/headers/module.modulemap" 2>/dev/null || true
rm -f "$STAGE"/*.modulemap

# Everything generated cleanly, so it is safe to replace what is on disk.
rm -rf "$GENERATED"
mv "$STAGE" "$GENERATED"
trap - EXIT
HEADERS="$GENERATED/headers"

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
