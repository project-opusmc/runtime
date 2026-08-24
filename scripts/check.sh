#!/usr/bin/env bash
set -euo pipefail

runtime_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
node "${runtime_root}/scripts/verify-native-ui-lock.mjs"
node --check "${runtime_root}/scripts/build-stock-oneconfig.mjs"
node --check "${runtime_root}/scripts/verify-native-ui-runtime-report.mjs"
node --check "${runtime_root}/scripts/test-native-ui-runtime-report.mjs"
node "${runtime_root}/scripts/test-native-ui-runtime-report.mjs"
exec "${runtime_root}/gradlew" -p "${runtime_root}" \
  test verifyRuntimeArtifacts --warning-mode all
