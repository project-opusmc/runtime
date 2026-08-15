# Opus Runtime

Opus Runtime contains the Java-side bootstrap, shared runtime services, and
Minecraft Java Edition integrations used by Opus Launcher. This repository is
independently buildable and publishes a deterministic artifact contract for the
Opus superproject.

## Modules

- `bootstrap/` starts the managed game process and owns the launcher/runtime
  argument protocol.
- `common/` provides lifecycle, telemetry, and shared client hooks.
- `legacy/1.8.9/` contains the supported Forge 1.8.9 runtime, mappings,
  transformers, and typed client mod.

All Java packages use the `org.polydevs.opusmc` namespace. The Forge client mod
ID and resource namespace are `opusclient`.

## Build

The main build requires Java 21. The isolated ForgeGradle lane downloads and
verifies its pinned Java 8 and Gradle 2.14.1 toolchains on macOS.

```bash
./scripts/check.sh
```

For a local Java 8 installation, set `OPUS_FORGE_UI_JAVA_HOME`. The isolated
toolchain cache can be moved with `OPUS_FORGE_UI_CACHE_DIR`.

## Artifact contract

```bash
./gradlew verifyRuntimeArtifacts
```

Verified release inputs are written to `build/runtime/`:

```text
artifacts/opus-bootstrap-<version>.jar
artifacts/opus-runtime-legacy-1.8.9-<version>.jar
artifacts/opus-client-legacy-1.8.9-<version>.jar
runtime-manifest.json
runtime-checksums.json
```

Launcher consumes the manifest and checksum files instead of discovering JARs
by filename or build-directory layout.

## License

Copyright (c) 2026 Polydevs. All rights reserved. See [LICENSE](LICENSE).
