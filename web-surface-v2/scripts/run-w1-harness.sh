#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
client="$root/legacy/1.8.9/client"
cache="${OPUS_CEF_V2_CACHE:-$HOME/.cache/opus-cef-v2}"
app="$($here/build-w1-helper.sh | tail -1)"
jdk8="${OPUS_FORGE_UI_JAVA_HOME:-$HOME/.cache/opus-forge-ui/jdk8u502-b07/Contents/Home}"
gradle="${OPUS_FORGE_UI_GRADLE:-$HOME/.cache/opus-forge-ui/gradle-2.14.1/bin/gradle}"
test -x "$jdk8/bin/java"
test -x "$jdk8/bin/javac"
test -x "$gradle"
cd "$client"
env OPUS_CEF_HELPER_APP="$app" OPUS_FORGE_UI_JAVAC="$jdk8/bin/javac" GRADLE_USER_HOME="$cache/harness-gradle-home" JAVA_HOME="$jdk8" PATH="$jdk8/bin:$PATH" "$gradle" --no-daemon harnessWebView
