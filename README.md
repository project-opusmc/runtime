# Opus Runtime

Opus Runtime contains the Java-side bootstrap, shared runtime services, and
the supported Minecraft Java Edition 1.8.9 Forge integration used by Opus
Launcher. This repository is independently buildable and publishes a
deterministic artifact contract for the Opus superproject.

> **Product-direction notice — September 15, 2026:** in the Opus superproject,
> [Decision 0008](../docs/decisions/0008-opus-client-launcher-ui-first-mainline.md)
> makes Client + Launcher, UI first, the active product mainline. Forge +
> OptiFine 1.8.9 is the intended initial Client host/reference implementation.
> This document describes the current Runtime artifact/staging contract only;
> it does not claim that the UI-first artifact contract or product UI has
> already changed.

## Current artifact and staging boundary

```text
Opus Launcher -> Forge 1.8.9 + OptiFine HD U M5 -> OPUS Core Mod -> vanilla Minecraft UI
```

This is the currently packaged and staged implementation baseline. Its
Core-Mod-only restrictions are not a permanent product-architecture decision;
any UI-first change must first have an explicit implementation contract and
verification.

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
- `legacy/1.8.9/client/` contains retained Client/UI source that may inform the
  initial Forge + OptiFine reference-host work. It remains outside the current
  production build/package/staging/launch contract and must not be presented as
  release-ready or currently launched.

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
artifacts/opus-lwjgl-macos-compat-2.9.2-nightly-20140822.jar
artifacts/opus-native-ui-1.8.9-0.1.0.jar
artifacts/opus-oneconfig-1.8.9-forge-0.2.2-alpha228-full.jar
artifacts/opus-elementa-762.jar
artifacts/opus-universalcraft-1.8.9-forge-516.jar
runtime-manifest.json
runtime-checksums.json
```

Launcher consumes the manifest and checksum files instead of discovering JARs
by filename or build-directory layout. A valid production manifest has exactly
seven managed roles: `bootstrap`, `runtime-legacy-1.8.9`,
`lwjgl-macos-compat`, `native-ui`, `opusconfig`, `elementa`, and
`universalcraft`. The managed Forge `mods/` directory must contain only the
verified OptiFine JAR and the verified staged OPUS artifacts; stale
`opus-client-legacy-1.8.9.jar` files are removed before Forge scans it.

## License

Copyright (c) 2026 Polydevs. All rights reserved. See [LICENSE](LICENSE).
