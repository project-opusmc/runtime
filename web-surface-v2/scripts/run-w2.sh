#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
if grep -R -n -E 'net\.minecraft|ScaledResolution|GuiScale|guiScale' \
  "$root/web-surface-v2/coordinate/src/main/java"; then
  echo 'W2 failed: coordinate contract must not depend on Minecraft GUI scale.' >&2
  exit 1
fi
"$here/run-w2-coordinate-contract.sh"
"$here/run-w2-macos-metrics-probe.sh"
for iteration in 1 2 3; do
  echo "W2 CEF oracle iteration $iteration/3"
  "$here/run-w2-cef-coordinate-oracle.sh"
done
