#!/usr/bin/env bash
#
# Builds the Rust core for Android and generates the Kotlin bindings.
#
# The sibling of `build-xcframework.sh`, and it exists for the same reason:
# run it whenever the FFI surface changes — a new method on `Core`, a new field
# on a DTO, a new event variant. Gradle consumes the `.so` files and the
# generated Kotlin as ordinary sources and never invokes cargo, so nothing
# regenerates behind your back. Change Rust without running this and the app
# keeps the bindings it had, which surfaces as a link error rather than as
# silent drift.
#
# The generated Kotlin is written into `android/core/src/main/kotlin` and
# **checked in**, for the reason the iOS script gives: it keeps Gradle builds
# hermetic and makes a moved boundary show up in review as a diff.
#
# The `.so` files are **not** checked in — `.gitignore` drops `jniLibs/`, since
# they are 362MB across four ABIs and x86 alone is over GitHub's per-file
# limit. So a fresh checkout has the Kotlin and no libraries behind it: run
# this once after cloning, or the first call into `Core` fails to link.
#
# Requires the NDK. `cargo-ndk` handles the toolchain plumbing — the sysroot,
# the right clang per ABI, the linker flags — which is otherwise a page of
# per-target environment variables that go stale with every NDK release:
#
#   cargo install cargo-ndk
#   rustup target add aarch64-linux-android armv7-linux-androideabi \
#                     x86_64-linux-android i686-linux-android
#
# Usage:  ./scripts/build-android-libs.sh [--debug]
set -euo pipefail

cd "$(dirname "$0")/.."

PROFILE="release"
PROFILE_FLAG="--release"
if [ "${1:-}" = "--debug" ]; then
    PROFILE="dev"
    PROFILE_FLAG=""
fi

# Four ABIs, because Play requires 64-bit and the 32-bit pair still covers
# devices in the field. `x86_64` is the emulator on an Intel host; `i686` is
# rarer but costs little to carry.
ABIS=(arm64-v8a armeabi-v7a x86_64 x86)

JNI_LIBS=android/core/src/main/jniLibs
GENERATED=android/core/src/main/kotlin

: "${ANDROID_NDK_HOME:=${NDK_HOME:-}}"
if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "error: set ANDROID_NDK_HOME (or NDK_HOME) to your NDK, e.g." >&2
    echo "  export ANDROID_NDK_HOME=\"\$HOME/Android/Sdk/ndk/29.0.14206865\"" >&2
    exit 1
fi
export ANDROID_NDK_HOME

# **16 KB page sizes.** Play requires support for them, and the linker only
# emits a compatible layout when asked. Without this the app loads on most
# devices and crashes on a Pixel 9, which is the worst possible failure shape.
# See `core::tls` for the other half of this story: aws-lc-rs crashes on the
# same devices, which is why ring is installed as the active provider.
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"

# `--lib` for the reason the iOS script gives: `cargo build -p` would also
# build the `uniffi-bindgen` binary, and a host CLI cannot link for Android.
echo "==> building ${#ABIS[@]} ABIs ($PROFILE)"
cargo ndk -o "$JNI_LIBS" $(printf -- '-t %s ' "${ABIS[@]}") \
    build -p supermessage-ffi --lib $PROFILE_FLAG

# Generate from the built library rather than from source, so the Kotlin cannot
# describe a surface the binary does not have. Staged and swapped in at the
# end: deleting the checked-in bindings first means any failure here leaves the
# tree with none at all.
echo "==> generating Kotlin bindings"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

HOST_LIB="target/aarch64-linux-android/$( [ "$PROFILE" = release ] && echo release || echo debug )/libsupermessage_ffi.so"
cargo run -q -p supermessage-ffi --bin uniffi-bindgen -- generate \
    --library "$HOST_LIB" \
    --language kotlin \
    --out-dir "$STAGE"

rm -rf "$GENERATED"
mkdir -p "$(dirname "$GENERATED")"
mv "$STAGE" "$GENERATED"
trap - EXIT

echo
echo "built  $JNI_LIBS/<abi>/libsupermessage_ffi.so"
echo "kotlin $GENERATED/uniffi/"
echo
echo "The generated Kotlin is checked in on purpose. Commit it with the Rust"
echo "change that moved it, so the boundary and its bindings travel together."
echo "The .so files are gitignored — every checkout runs this script once."
