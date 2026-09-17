#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
surface="$root/legacy/1.8.9/client/src/main/java/org/polydevs/opusmc/client/embed/OpusWebTextureSurface.java"

"$here/run-w7-lifecycle.sh"

if rg -q 'GL11\.glFinish\s*\(' "$surface"; then
  echo 'ERROR: production Web Surface compositor still calls glFinish' >&2
  exit 1
fi
for marker in PBO_COUNT GL_PIXEL_UNPACK_BUFFER glBufferData GL_STREAM_DRAW; do
  rg -q "$marker" "$surface" || { echo "ERROR: PBO production marker missing: $marker" >&2; exit 1; }
done

echo 'PASS production compositor uses PBO staging without glFinish'

active_jar="$root/build/runtime/artifacts/opus-native-ui-1.8.9-0.1.0.jar"
if [[ -f "$active_jar" ]]; then
  jar tf "$active_jar" | rg -x 'org/polydevs/opusmc/client/OpusClientMod.class' >/dev/null
  jar tf "$active_jar" | rg -x 'opusui/index.html' >/dev/null
  if jar tf "$active_jar" | rg -x 'org/polydevs/opusmc/nativeui/OpusNativeUiMod.class' >/dev/null; then
    echo 'ERROR: retired Elementa UI owner is present in active runtime artifact' >&2
    exit 1
  fi
  echo 'PASS active runtime artifact owns the Web Surface client'
fi

echo 'W7 HARDENING: ALL PASSED'
