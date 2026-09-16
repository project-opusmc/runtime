#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo 'W3 regression: W1 lifecycle/input baseline'
"$here/run-w1-harness.sh"

echo 'W3 regression: complete W2 coordinate gate'
"$here/run-w2.sh"

for iteration in 1 2 3; do
  echo "W3 frame transport iteration $iteration/3"
  "$here/run-w3-frame-transport.sh"
done
