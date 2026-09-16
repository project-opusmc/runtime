#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
cef_root="$($here/bootstrap-cef-macos-arm64.sh | tail -1)"
CEF_ROOT="$cef_root" "$root/legacy/1.8.9/client/native/opus-cef-helper/build.sh"
app="$root/legacy/1.8.9/client/native/opus-cef-helper/build/opus-webview-helper.app"
guard="$app/Contents/Frameworks/opus-keychain-guard.dylib"
test -x "$app/Contents/MacOS/opus-webview-helper"
test -f "$guard"
lipo "$guard" -verify_arch arm64 x86_64
codesign --verify --deep --strict "$app"
printf '%s
' "$app"
