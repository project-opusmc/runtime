#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo 'W5 regression: W1 lifecycle/navigation'
"$here/run-w1-harness.sh"

echo 'W5 regression: W2 coordinate/AppKit/CEF'
"$here/run-w2.sh"

echo 'W5 regression: focused W3 transport'
"$here/run-w3-frame-transport.sh"

echo 'W5 regression: W4 pure input + CEF input'
"$here/run-w4-input-contract.sh"
"$here/run-w4-cef-input-oracle.sh"

for iteration in 1 2 3; do
  echo "W5 React CEF oracle iteration $iteration/3"
  "$here/run-w5-react-cef-oracle.sh"
done
