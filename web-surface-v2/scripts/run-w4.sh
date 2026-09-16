#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo 'W4 regression: W1 lifecycle/navigation baseline'
"$here/run-w1-harness.sh"

echo 'W4 regression: complete W2 coordinate gate'
"$here/run-w2.sh"

echo 'W4 regression: focused W3 frame transport'
"$here/run-w3-frame-transport.sh"

echo 'W4 pure input contract'
"$here/run-w4-input-contract.sh"

for iteration in 1 2 3; do
  echo "W4 CEF input oracle iteration $iteration/3"
  "$here/run-w4-cef-input-oracle.sh"
done
