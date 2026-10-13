#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
repo="$(cd "$root/.." && pwd)"
client="$root/legacy/1.8.9/client"
ui="$repo/ui-v2"
cache="${OPUS_CEF_V2_CACHE:-$HOME/.cache/opus-cef-v2}"
jdk8="${OPUS_FORGE_UI_JAVA_HOME:-$HOME/.cache/opus-forge-ui/jdk8u502-b07/Contents/Home}"
gradle="${OPUS_FORGE_UI_GRADLE:-$HOME/.cache/opus-forge-ui/gradle-2.14.1/bin/gradle}"

echo 'W6 React type/build gate'
npm --prefix "$ui" run check
npm --prefix "$ui" run build

echo 'W6 production compile/resource/bridge gate'
cd "$client"
env OPUS_FORGE_UI_JAVAC="$jdk8/bin/javac" \
  GRADLE_USER_HOME="$cache/harness-gradle-home" JAVA_HOME="$jdk8" PATH="$jdk8/bin:$PATH" \
  "$gradle" --no-daemon compileJava processResources harnessBridge harnessWebSurfaceHostNavigation \
    harnessHudCoordinates harnessScreenInput

screen="$client/src/main/java/org/polydevs/opusmc/client/OpusClientScreen.java"
# The current viewport helper uses framebuffer / device scale, independent of
# Minecraft GUI scale. The coordinate and real GuiScreen harnesses above verify
# that behavior; banning the helper name would reject this supported path.
if ! grep -q 'Display.getPixelScaleFactor' "$screen"; then
  echo 'ERROR: production Web Surface screen is not DPR-aware' >&2
  exit 1
fi
if ! grep -q 'Mouse.getEventX' "$screen" || ! grep -q 'Mouse.getEventY' "$screen"; then
  echo 'ERROR: production pointer buttons are not sourced from raw LWJGL events' >&2
  exit 1
fi
if ! grep -q 'OpusInputMapper' "$screen"; then
  echo 'ERROR: production input path does not use the W4 input mapper' >&2
  exit 1
fi

echo 'PASS production screen uses W2/W4 raw framebuffer contract'

resource_root="$client/build/resources/main/opusui"
[ -f "$resource_root/index.html" ] || { echo 'ERROR: packaged React index missing' >&2; exit 1; }
asset_js="$(find "$resource_root/assets" -maxdepth 1 -type f -name 'index-*.js' | head -1)"
[ -n "$asset_js" ] || { echo 'ERROR: packaged React JS missing' >&2; exit 1; }
if ! grep -q 'opus-v2' "$asset_js"; then
  echo 'ERROR: packaged /opusui is not the React V2 product bundle' >&2
  exit 1
fi
for route in title singleplayer multiplayer settings accounts game_menu quick_hub mods_catalog module_detail hud_editor; do
  grep -q "$route" "$asset_js" || { echo "ERROR: packaged React bundle missing route $route" >&2; exit 1; }
done

echo 'PASS packaged /opusui is canonical React V2'

cd "$root"
echo 'W6 input regression'
"$here/run-w4-input-contract.sh"
"$here/run-w4-cef-input-oracle.sh"
for iteration in 1 2 3; do
  echo "W6 React CEF production iteration $iteration/3"
  "$here/run-w5-react-cef-oracle.sh"
done

echo 'W6 PRODUCTION MIGRATION: ALL PASSED'
