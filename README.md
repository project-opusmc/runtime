# Opus Runtime

Opus Runtime contains the Java-side bootstrap, shared runtime services, and
the Minecraft Java Edition 1.8.9 Forge integration used by the Client and
Launcher. It publishes a deterministic artifact contract for the Opus
superproject.

> **Active product direction — October 7, 2026:** Opus Client + Opus Launcher,
> UI first, is the active mainline under
> [Decision 0008](../docs/decisions/0008-opus-client-launcher-ui-first-mainline.md).
> React/CEF is the in-game Client surface and Tauri/React remains the desktop
> Launcher surface. The `client-cef` profile is development-only; packaging
> remains blocked pending release signing and physical in-game acceptance.

## Current artifact and staging boundary

The active checkout builds the React UI into the five-role Runtime contract
below. The Core Mod and Client JARs are Forge mods; the standalone loader and
LWJGL compatibility JAR are staged on the game classpath. The CEF helper app is
staged separately beside the desktop runtime resources.

## Modules

- `bootstrap/` starts the managed game process and owns the launcher/runtime
  argument protocol.
- `common/` provides lifecycle and local telemetry services without product UI
  hooks.
- `legacy/1.8.9/` contains the supported Forge 1.8.9 runtime, mappings,
  and Core Mod transformers.
- `legacy/1.8.9/client/` contains retained Client/UI source that may inform the
  initial Forge + OptiFine reference-host work. It remains outside the current
  production build/package/staging/launch contract and must not be presented as
  release-ready or currently launched.

All Java packages use the `org.polydevs.opusmc` namespace.

## Build

The main Gradle build uses the configured JDK toolchain. The isolated
ForgeGradle lane downloads and verifies its pinned Java 8 and Gradle 2.14.1
toolchains on macOS.

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
artifacts/opus-standalone-loader-<version>.jar
artifacts/opus-lwjgl-macos-compat-2.9.2-nightly-20140822.jar
artifacts/opus-native-ui-1.8.9-0.1.0.jar
runtime-manifest.json
runtime-checksums.json
```

The five managed roles are `bootstrap`, `runtime-legacy-1.8.9`,
`standalone-loader`, `lwjgl-macos-compat`, and `native-ui`. The standalone
loader and LWJGL compatibility artifacts are staged on the game classpath;
the legacy runtime and React/CEF client are staged as Forge mods.

## License

Copyright (c) 2026 Polydevs. All rights reserved. See [LICENSE](LICENSE).
