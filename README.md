# Opus Runtime

Opus Runtime contains the Java-side bootstrap, shared runtime services, and
the supported Minecraft Java Edition 1.8.9 Forge integration used by Opus
Launcher. This repository is independently buildable and publishes a
deterministic artifact contract for the Opus superproject.

## Current production boundary

```text
Opus Launcher -> Forge 1.8.9 + OptiFine HD U M5 -> OPUS Core Mod -> vanilla Minecraft UI
```

The Core Mod is the only OPUS mod staged into a production game directory. It
owns lifecycle/telemetry and narrowly verified non-UI patches; it must not own
a GUI, HUD product surface, key binding, browser process, or input route.

The legacy CEF/product-client source remains quarantined in the working tree
for later review. It is not built into the Runtime artifact set, bundled with
the desktop launcher, staged, or launched in production.

## Modules

- `bootstrap/` starts the managed game process and owns the launcher/runtime
  argument protocol.
- `common/` provides lifecycle and local telemetry services without product UI
  hooks.
- `legacy/1.8.9/` contains the supported Forge 1.8.9 runtime, mappings,
  and Core Mod transformers.
- `legacy/1.8.9/client/` is quarantined research source only. It is outside the
  production build/package/staging/launch contract.

All Java packages use the `org.polydevs.opusmc` namespace.

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
runtime-manifest.json
runtime-checksums.json
```

Launcher consumes the manifest and checksum files instead of discovering JARs
by filename or build-directory layout. A valid production manifest has exactly
those two OPUS artifacts. The managed Forge `mods/` directory must contain only
the verified OptiFine JAR and the verified OPUS Core Mod; stale
`opus-client-legacy-1.8.9.jar` files are removed before Forge scans it.

## License

Copyright (c) 2026 Polydevs. All rights reserved. See [LICENSE](LICENSE).
