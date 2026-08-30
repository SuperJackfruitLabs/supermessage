#!/usr/bin/env bash
#
# Generates the checked-in Kotlin bindings from a HOST build of the FFI crate.
#
# Split out of `build-android-libs.sh` because it needs **no NDK and no
# cargo-ndk**. UniFFI reads the library's exported metadata, not its machine
# code, so the Kotlin it emits is the same whether the library it inspects was
# built for aarch64-linux-android or for the machine you are sitting at.
#
# That matters in two places:
#
#   * **Locally.** Anyone can keep the bindings current after changing the FFI
#     surface without installing a multi-gigabyte NDK. Before this existed the
#     only way to regenerate was the full four-ABI cross-compile, so a change
#     made on a machine without an NDK landed with stale Kotlin — which fails at
#     run time as an UnsatisfiedLinkError, or worse as a field read in the wrong
#     order, on whatever device runs it first.
#
#   * **In CI.** The staleness check can run seconds after a half-minute host
#     build instead of behind a thirteen-minute cross-compile it does not need.
#
# `--no-format` for the reason `build-xcframework.sh` gives at length: UniFFI
# shells out to ktlint when it happens to be on PATH and silently skips it when
# it is not, so the same Rust produces different files depending on whose
# machine ran it. These bindings are checked in and diffed against, so
# "formatted by whoever last ran this" is not a property they can have.
#
# Usage:  ./scripts/generate-kotlin-bindings.sh
set -euo pipefail

cd "$(dirname "$0")/.."

GENERATED=android/core/src/main/kotlin

echo "==> building the host library"
cargo build -q -p supermessage-ffi --lib

# The extension is the platform's, and this script is meant to run on a
# contributor's laptop as well as on a Linux runner.
LIB=""
for candidate in \
    target/debug/libsupermessage_ffi.dylib \
    target/debug/libsupermessage_ffi.so; do
    if [ -f "$candidate" ]; then
        LIB="$candidate"
        break
    fi
done
if [ -z "$LIB" ]; then
    echo "error: no host library under target/debug — did cargo build succeed?" >&2
    exit 1
fi

# Generate from the built library rather than from source, so the Kotlin cannot
# describe a surface the binary does not have. Staged and swapped in at the end:
# deleting the checked-in bindings first means any failure here leaves the tree
# with none at all.
echo "==> generating Kotlin bindings from $LIB"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

cargo run -q -p supermessage-ffi --bin uniffi-bindgen -- generate \
    --library "$LIB" \
    --language kotlin \
    --no-format \
    --out-dir "$STAGE"

# Narrowed to uniffi/, not the whole of $GENERATED: android/core/src/main/kotlin
# is :core's real auto-discovered source root, so wiping it outright would also
# take out any non-generated Kotlin ever added there.
rm -rf "${GENERATED:?}/uniffi"
mkdir -p "$GENERATED"
mv "$STAGE/uniffi" "$GENERATED/uniffi"
rm -rf "$STAGE"
trap - EXIT

echo "wrote  $GENERATED/uniffi"
