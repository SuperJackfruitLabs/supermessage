#!/bin/sh
# Checks that a macOS SuperMessage binary carries libwebrtc's Objective-C categories.
#
# Calls link LiveKit's prebuilt libwebrtc, whose video codec factories call methods that
# libwebrtc adds to classes in categories (`RTCVideoCodecInfo (Private)`, `NSString (StdString)`).
# A static library's category-only objects are linked only with `-ObjC`. webrtc-sys asks for it
# with `cargo:rustc-link-arg`, which Cargo applies to webrtc-sys's own targets and never to the
# app, so the app's build script passes it (src-tauri/build.rs). Without it, v0.1.4's first call
# died with "unrecognized selector" when LiveKit built its peer connection factory.
#
# Usage: check-macos-objc.sh <Mach-O binary>   (each architecture slice is checked)
# Needs `otool` (macOS) or `llvm-objdump`.
set -eu

bin=${1:?usage: check-macos-objc.sh <Mach-O binary>}
[ -f "$bin" ] || { echo "no such file: $bin" >&2; exit 2; }

# A method each category defines, as the method lists of the ObjC metadata name it.
methods="nativeSdpVideoFormat stringForStdString:"

if command -v lipo >/dev/null 2>&1; then
  archs=$(lipo -archs "$bin")
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

status=0
for arch in $archs; do
  meta=$(dump "$arch" 2>/dev/null) || { echo "$arch: could not read the ObjC metadata" >&2; status=1; continue; }
  for m in $methods; do
    if printf '%s\n' "$meta" | grep -Eq "name +0x[0-9a-f]+ +$m\$"; then
      echo "$arch: $m is implemented"
    else
      echo "$arch: $m is called but not implemented (libwebrtc's categories were not linked; is -ObjC passed?)" >&2
      status=1
    fi
  done
done
exit $status
