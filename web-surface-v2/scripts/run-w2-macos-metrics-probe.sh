#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
out="$root/web-surface-v2/build/w2-macos-metrics-probe"
mkdir -p "$(dirname "$out")"
xcrun clang++ -std=c++17 -arch arm64 -framework Cocoa \
  "$root/web-surface-v2/native/macos/opus_display_metrics_probe.mm" -o "$out"
"$out"
