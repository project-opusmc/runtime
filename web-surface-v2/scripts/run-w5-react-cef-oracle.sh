#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
repo="$(cd "$root/.." && pwd)"
client="$root/legacy/1.8.9/client"
ui="$repo/ui-v2"
cache="${OPUS_CEF_V2_CACHE:-$HOME/.cache/opus-cef-v2}"
app="${OPUS_CEF_HELPER_APP:-}"
if [[ -z "$app" ]]; then
  app="$($here/build-w1-helper.sh | tail -1)"
fi
jdk8="${OPUS_FORGE_UI_JAVA_HOME:-$HOME/.cache/opus-forge-ui/jdk8u502-b07/Contents/Home}"
gradle="${OPUS_FORGE_UI_GRADLE:-$HOME/.cache/opus-forge-ui/gradle-2.14.1/bin/gradle}"

if [ ! -d "$ui/node_modules" ]; then
  npm --prefix "$ui" ci
fi
npm --prefix "$ui" run check
npm --prefix "$ui" run build

cd "$client"
env OPUS_CEF_HELPER_APP="$app" OPUS_FORGE_UI_JAVAC="$jdk8/bin/javac" \
  GRADLE_USER_HOME="$cache/harness-gradle-home" JAVA_HOME="$jdk8" PATH="$jdk8/bin:$PATH" \
  "$gradle" --no-daemon harnessWebSurfaceReactV2
