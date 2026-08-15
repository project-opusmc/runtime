#!/usr/bin/env bash
set -euo pipefail

runtime_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "${runtime_root}/gradlew" -p "${runtime_root}" \
  test verifyRuntimeArtifacts --warning-mode all
