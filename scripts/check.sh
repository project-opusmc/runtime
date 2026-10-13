#!/usr/bin/env bash
set -euo pipefail

runtime_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
toolchain_cache="${OPUS_FORGE_UI_CACHE_DIR:-${HOME}/.cache/opus-forge-ui}"
export OPUS_CEF_V2_CACHE="${OPUS_CEF_V2_CACHE:-${HOME}/.cache/opus-cef-v2}"
export OPUS_FORGE_UI_JAVA_HOME="${OPUS_FORGE_UI_JAVA_HOME:-${toolchain_cache}/jdk8u502-b07/Contents/Home}"
export OPUS_FORGE_UI_GRADLE="${OPUS_FORGE_UI_GRADLE:-${toolchain_cache}/gradle-2.14.1/bin/gradle}"
"${runtime_root}/web-surface-v2/scripts/build-w1-helper.sh" >/dev/null
exec "${runtime_root}/gradlew" -p "${runtime_root}" \
  check verifyRuntimeArtifacts --warning-mode all
