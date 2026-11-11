#!/usr/bin/env bash
# The corpus files under fixtures/ are copies of treaty's, published to npm. Rust reads them with
# include_str!, so they stay vendored here. This fails when a copy differs from its pinned treaty
# version.
#
# - fixtures/ecosystem-identity/*.json: @superjackfruitlabs/treaty-ecosystem-identity, corpus/.
# - fixtures/treaty-matrix-events/examples.json: @superjackfruitlabs/treaty-matrix-events, the
#   board-room event examples every type's schema is checked against.
#
# To move to a newer corpus: change the version below, copy the files from that version's package,
# and fix whatever the Rust tests then say.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
status=0

# check <package> <version> <folder in the package> <vendored folder>
check() {
  local package=$1 version=$2 inside=$3 vendored=$4
  local work
  work=$(mktemp -d)
  (cd "$work" && npm pack --silent "$package@$version" >/dev/null)
  tar -xzf "$work"/*.tgz -C "$work"
  for copy in "$root/$vendored"/*.json; do
    local name upstream
    name=$(basename "$copy")
    upstream="$work/package/$inside$name"
    if [ ! -f "$upstream" ]; then
      echo "::error::$name is not in $package@$version"
      status=1
    elif ! cmp -s "$copy" "$upstream"; then
      echo "::error::$vendored/$name differs from $package@$version"
      status=1
    else
      echo "ok $vendored/$name = $package@$version"
    fi
  done
  rm -rf "$work"
}

check @superjackfruitlabs/treaty-ecosystem-identity 0.0.1 corpus/ fixtures/ecosystem-identity
check @superjackfruitlabs/treaty-matrix-events 0.0.10 "" fixtures/treaty-matrix-events

exit "$status"
