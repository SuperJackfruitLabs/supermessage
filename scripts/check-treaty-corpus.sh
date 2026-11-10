#!/usr/bin/env bash
# The corpus files under fixtures/ecosystem-identity/ are copies of treaty's, published to npm as
# @superjackfruitlabs/treaty-ecosystem-identity. Rust reads them with include_str!, so they stay
# vendored here. This fails when a copy differs from the pinned treaty version.
#
# To move to a newer corpus: change TREATY_VERSION, copy the files from that version's package
# (its corpus/ folder), and fix whatever the Rust tests then say.
set -euo pipefail

TREATY_VERSION=0.0.1
PACKAGE=@superjackfruitlabs/treaty-ecosystem-identity

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

(cd "$work" && npm pack --silent "$PACKAGE@$TREATY_VERSION" >/dev/null)
tar -xzf "$work"/*.tgz -C "$work"

status=0
for copy in "$root"/fixtures/ecosystem-identity/*.json; do
  name=$(basename "$copy")
  upstream="$work/package/corpus/$name"
  if [ ! -f "$upstream" ]; then
    echo "::error::$name is not in $PACKAGE@$TREATY_VERSION"
    status=1
  elif ! cmp -s "$copy" "$upstream"; then
    echo "::error::fixtures/ecosystem-identity/$name differs from $PACKAGE@$TREATY_VERSION"
    status=1
  else
    echo "ok $name = $PACKAGE@$TREATY_VERSION"
  fi
done
exit "$status"
