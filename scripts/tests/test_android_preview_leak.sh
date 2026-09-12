#!/usr/bin/env bash
#
# Previews and their fixtures must not compile into the release variant.
#
# `src/debug/kotlin` is what excludes them, and that is an *assumption about
# AGP's source-set discovery* rather than a guarantee — the whole reason
# `DebugSourceSetTest` exists is that a mis-wired debug source set fails
# silently. This asks the compiler instead: compile both variants and look for
# the marker in the classes each one produced.
#
# **Compiled classes, not an APK, and that is a deliberate trade.** An
# assembled release APK is the ground truth, but it drags in the JNI libraries
# for every ABI in the `splits` block and a signing config this project does
# not have — on a pull request CI builds one ABI, so the gate would be
# reporting on a packaging step rather than on the previews. `compileRelease*`
# answers the actual question, which is whether the release variant compiles
# this code at all.
#
# **The positive control is the important half.** Grepping for an absence
# proves nothing unless the same grep can find a presence: the iOS counterpart
# of this gate passed three deliberately leaked builds in a row because
# `strings | grep -q` under `set -o pipefail` turned every match into a
# SIGPIPE failure, and nothing in the test could tell that apart from "no
# leak". So this checks the debug classes contain the marker *before* it
# believes the release classes do not.
set -euo pipefail

MARKER="__supermessage_android_preview_fixture_do_not_ship__"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT/android"

echo "Compiling both variants…"
./gradlew --quiet :app:compileDebugKotlin :app:compileReleaseKotlin

# Discovered rather than hard-coded: the intermediate layout under
# `app/build` is AGP's business and has moved before.
find_classes() {
  find app/build -type f -name '*.class' -path "*/$1/*" 2>/dev/null || true
}

count_marker() {
  local variant="$1" n=0 files=0
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    files=$((files + 1))
    # `grep -c`, never `grep -q`: see this file's header. A `-q` here would
    # exit early, and under `pipefail` a match would read as a failure.
    if [ "$(grep -ac "$MARKER" "$f" || true)" -gt 0 ]; then
      n=$((n + 1))
      echo "    $f" >&2
    fi
  done < <(find_classes "$variant")
  echo "$files $n"
}

read -r debug_files debug_hits <<< "$(count_marker debug)"
read -r release_files release_hits <<< "$(count_marker release)"

if [ "$debug_files" -eq 0 ] || [ "$release_files" -eq 0 ]; then
  echo "FAIL: found no compiled classes (debug: $debug_files, release: $release_files)."
  echo "The gate scanned nothing and therefore proved nothing — AGP's"
  echo "intermediate layout has probably moved. Fix find_classes()."
  exit 1
fi

if [ "$debug_hits" -eq 0 ]; then
  echo "FAIL: the marker is not in the debug classes either."
  echo
  echo "This is the positive control, and it failing means one of two things,"
  echo "both of which matter more than a leak would:"
  echo "  - src/debug/kotlin is not being compiled at all, in which case the"
  echo "    previews are dead files (DebugSourceSetTest should also be red);"
  echo "  - or this script's grep does not work, in which case its PASS on the"
  echo "    release classes means nothing."
  exit 1
fi

if [ "$release_hits" -gt 0 ]; then
  echo "FAIL: preview fixtures compiled into the release variant."
  echo
  echo "Everything under app/src/debug/kotlin/dev/supermessage/previews must"
  echo "stay in that source set. A @Preview in src/main will also break the"
  echo "build, because compose-ui-tooling-preview is debug-scoped."
  exit 1
fi

echo "PASS: marker in $debug_hits debug class file(s), none of $release_files release ones."
