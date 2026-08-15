#!/usr/bin/env bash
set -euo pipefail

runtime_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "${runtime_root}/gradlew" -p "${runtime_root}" test prepareBootstrap --warning-mode all
