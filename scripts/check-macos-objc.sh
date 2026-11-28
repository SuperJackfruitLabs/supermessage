#!/bin/sh
# Checks that a macOS SuperMessage binary carries libwebrtc's Objective-C categories.
#
# Calls link LiveKit's prebuilt libwebrtc, whose video codec factories call methods that
# libwebrtc adds to classes in categories (`RTCVideoCodecInfo (Private)`, `NSString (StdString)`).
# A static library's category-only objects are linked only with `-ObjC`. webrtc-sys asks for it
# with `cargo:rustc-link-arg`, which Cargo applies to webrtc-sys's own targets and never to the
# app, so the app's build script passes it (src-tauri/build.rs). Without it, v0.1.4's first call
# would call methods that are not there when LiveKit builds its peer connection factory.
#
# Usage: check-macos-objc.sh <Mach-O binary>   (each architecture slice is checked)
# Needs `otool` (macOS) or `llvm-objdump`.
set -eu

bin=${1:?usage: check-macos-objc.sh <Mach-O binary>}
[ -f "$bin" ] || { echo "no such file: $bin" >&2; exit 2; }

# A method each category defines. Neither is in v0.1.4's binary.
methods="nativeSdpVideoFormat stringForStdString:"
# A method of a libwebrtc class (not a category) that every build links. If it is not found,
# the dump's format is not one this script reads, and that must not pass as "categories missing".
control="initWithName:"

if command -v lipo >/dev/null 2>&1; then
  archs=$(lipo -archs "$bin")
elif command -v llvm-lipo >/dev/null 2>&1; then
  archs=$(llvm-lipo -archs "$bin")
else
  archs="arm64 x86_64"
fi

dump() {
  if command -v otool >/dev/null 2>&1; then
    otool -arch "$1" -ov "$bin"
  else
    llvm-objdump --macho --arch="$1" --objc-meta-data "$bin"
  fi
}

# A method list entry names its selector in one of two ways: absolute lists (x86_64) print
# `name 0x<addr> <sel>`, relative lists (arm64) print `name 0x<off> (0x<addr>) <sel>`. Its
# `imp` line also names it as `-[Class(Category) sel]` while the binary keeps local symbols.
has() {
  printf '%s\n' "$1" | grep -Eq "(name +0x[0-9a-f]+( +\(0x[0-9a-f]+\))? +$2\$)|([-+]\[[A-Za-z0-9_]+(\([A-Za-z0-9_]+\))? $2\]\$)"
}

status=0
for arch in $archs; do
  meta=$(dump "$arch" 2>/dev/null) || { echo "$arch: could not read the ObjC metadata" >&2; status=2; continue; }
  if ! has "$meta" "$control"; then
    echo "$arch: could not find $control, so this dump's method lists can't be read; check the script" >&2
    status=2
    continue
  fi
  for m in $methods; do
    if has "$meta" "$m"; then
      echo "$arch: $m is implemented"
    else
      echo "$arch: $m is not implemented (libwebrtc's categories were not linked; is -ObjC passed?)" >&2
      [ "$status" = 0 ] && status=1
    fi
  done
done
exit $status
