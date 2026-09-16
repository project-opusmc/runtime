#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
jdk8="${OPUS_FORGE_UI_JAVA_HOME:-$HOME/.cache/opus-forge-ui/jdk8u502-b07/Contents/Home}"
out="$root/web-surface-v2/build/w4-input"
mkdir -p "$out"
find "$out" -type f -name '*.class' -delete
"$jdk8/bin/javac" -source 8 -target 8 -d "$out" \
  $(find "$root/web-surface-v2/coordinate/src/main/java" -name '*.java' -print) \
  $(find "$root/web-surface-v2/input/src/main/java" -name '*.java' -print) \
  $(find "$root/web-surface-v2/input/src/test/java" -name '*.java' -print)
"$jdk8/bin/java" -cp "$out" org.polydevs.opusmc.websurface.InputContractHarness
