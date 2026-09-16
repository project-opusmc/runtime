#!/usr/bin/env bash
# Builds the Opus CEF helper .app bundle (macOS arm64), Phase-1 de-risk.
# Prereqs: CEF dist at $CEF_ROOT with libcef_dll_wrapper already built.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$HERE" rev-parse --show-superproject-working-tree 2>/dev/null || true)"
if [[ -z "$REPO_ROOT" ]]; then
  REPO_ROOT="$(cd "$HERE/../../../../../.." && pwd)"
fi

if [[ -x "$REPO_ROOT/tools/no-keychain/check-console-unlocked.sh" ]] \
  && ! "$REPO_ROOT/tools/no-keychain/check-console-unlocked.sh"; then
  echo "Refusing CEF build: macOS console is locked; no password is requested." >&2
  exit 78
fi

CEF_ROOT="${CEF_ROOT:-$HOME/.cache/opus-cef/current}"
WRAPPER="$CEF_ROOT/build/libcef_dll_wrapper/libcef_dll_wrapper.a"
FRAMEWORK_SRC="$CEF_ROOT/Release/Chromium Embedded Framework.framework"
OUT="$HERE/build"
APP="$OUT/opus-webview-helper.app"
MAIN_BIN="opus-webview-helper"

[ -f "$WRAPPER" ] || { echo "ERROR: wrapper not built: $WRAPPER"; exit 1; }
[ -d "$FRAMEWORK_SRC" ] || { echo "ERROR: framework missing: $FRAMEWORK_SRC"; exit 1; }

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Frameworks"

CXXFLAGS=(-std=c++20 -arch arm64 -I"$CEF_ROOT" -O2 -Wall -Wno-unused-parameter -Wno-deprecated-declarations)
LDFLAGS=(-framework Cocoa -framework AppKit -framework Foundation -framework Security -Wl,-ObjC)
GUARD="$APP/Contents/Frameworks/opus-keychain-guard.dylib"

echo "== compile main =="
echo "== compile Keychain guard =="
clang++ -std=c++20 -arch arm64 -arch x86_64 -O2 -Wall -Wno-unused-parameter -Wno-deprecated-declarations -dynamiclib \
  "$HERE/src/opus_keychain_guard.mm" -framework Security \
  -install_name '@rpath/opus-keychain-guard.dylib' \
  -o "$GUARD"

clang++ "${CXXFLAGS[@]}" "$HERE/src/opus_main.mm" "$WRAPPER" "${LDFLAGS[@]}" \
  "$GUARD" -Wl,-rpath,@loader_path/../Frameworks \
  -o "$APP/Contents/MacOS/$MAIN_BIN"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleExecutable</key><string>$MAIN_BIN</string>
  <key>CFBundleIdentifier</key><string>dev.polydevs.opus.webviewhelper</string>
  <key>CFBundleName</key><string>$MAIN_BIN</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>LSUIElement</key><true/>
  <key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST

echo "== copy framework =="
cp -a "$FRAMEWORK_SRC" "$APP/Contents/Frameworks/"

# <!-- APPEND-HELPERS -->

echo "== compile helper subprocess =="
HELPER_TMP="$OUT/helper_bin"
clang++ "${CXXFLAGS[@]}" "$HERE/src/process_helper_mac.mm" "$WRAPPER" \
  -framework AppKit -framework Foundation -framework Security -Wl,-ObjC \
  "$GUARD" -Wl,-rpath,@loader_path/../../../ \
  -o "$HELPER_TMP"

# macOS CEF expects one helper .app per process type. All share the same binary.
make_helper() {
  local suffix="$1" idsuffix="$2"
  local name="$MAIN_BIN Helper$suffix"
  local dir="$APP/Contents/Frameworks/$name.app"
  mkdir -p "$dir/Contents/MacOS"
  cp "$HELPER_TMP" "$dir/Contents/MacOS/$name"
  cat > "$dir/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleExecutable</key><string>$name</string>
  <key>CFBundleIdentifier</key><string>dev.polydevs.opus.webviewhelper.helper$idsuffix</string>
  <key>CFBundleName</key><string>$name</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>LSUIElement</key><true/>
</dict></plist>
PLIST
}

make_helper ""            ""
make_helper " (GPU)"      ".gpu"
make_helper " (Plugin)"   ".plugin"
make_helper " (Renderer)" ".renderer"
make_helper " (Alerts)"   ".alerts"
rm -f "$HELPER_TMP"

echo "== ad-hoc codesign (inside-out, no hardened runtime so V8 JIT is allowed) =="
FW="$APP/Contents/Frameworks/Chromium Embedded Framework.framework"
codesign --force --sign - "$FW" >/dev/null 2>&1 || codesign --force --sign - "$FW"
codesign --force --sign - "$GUARD"
for h in "$APP/Contents/Frameworks/"*.app; do
  codesign --force --sign - "$h"
done
codesign --force --sign - "$APP"

echo "== done =="
echo "APP: $APP"
