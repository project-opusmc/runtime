#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
lock="$root/web-surface-v2/cef-lock.json"
cache="${OPUS_CEF_V2_CACHE:-$HOME/.cache/opus-cef-v2}"
version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["cefVersion"])' "$lock")"
file="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["file"])' "$lock")"
url="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["url"])' "$lock")"
sha1="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sha1"])' "$lock")"
size="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["size"])' "$lock")"
downloads="$cache/downloads"
dist="$cache/dist"
build="$cache/build"
archive="$downloads/$file"
dist_name="${file%_macosarm64.tar.bz2}_macosarm64"
cef_root="$dist/$dist_name"
mkdir -p "$downloads" "$dist"
valid=0
if [ -f "$archive" ]; then
  actual_size="$(stat -f '%z' "$archive")"
  actual_sha1="$(shasum -a 1 "$archive" | awk '{print $1}')"
  if [ "$actual_size" = "$size" ] && [ "$actual_sha1" = "$sha1" ]; then valid=1; fi
fi
if [ "$valid" -ne 1 ]; then
  if [ -f "$archive" ]; then mv "$archive" "$archive.invalid.$(date +%s)"; fi
  curl -fL "$url" -o "$archive"
  actual_size="$(stat -f '%z' "$archive")"
  actual_sha1="$(shasum -a 1 "$archive" | awk '{print $1}')"
  [ "$actual_size" = "$size" ]
  [ "$actual_sha1" = "$sha1" ]
fi
if [ ! -d "$cef_root/Release/Chromium Embedded Framework.framework" ]; then
  tar -xjf "$archive" -C "$dist"
fi
cmake -S "$cef_root" -B "$build" -G 'Unix Makefiles' -DPROJECT_ARCH=arm64 -DCMAKE_BUILD_TYPE=Release
cmake --build "$build" --target libcef_dll_wrapper -j"${OPUS_CEF_BUILD_JOBS:-4}"
if [ ! -e "$cef_root/build" ]; then ln -s "$build" "$cef_root/build"; fi
printf '%s
' "$cef_root"
