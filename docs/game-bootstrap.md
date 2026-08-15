# Game side: Forge bridge, coremod, transformers, in-game UI

Everything under `game/` produces Java 8 bytecode for OPUS's pinned Forge +
OptiFine 1.8.9 runtime. Forge owns Minecraft classloading. OPUS joins that
loader through a small bootstrap bridge and a verified Forge coremod; it does
not run a second Minecraft classloader.

Every module builds with `options.release = 8`, UTF-8,
`-Xlint:all,-options`, `-Werror`, and locked dependencies. A warning is a build
failure.

## Modules and packaged artifacts

| Module | Depends on | Active role |
| --- | --- | --- |
| `core` | — | Lifecycle and local telemetry. Legacy reflection UI hooks remain only until the typed Forge client-mod cutover. |
| `bootstrap` | `core`, LaunchWrapper at compile time | `ForgeBootstrapMain`, bounded stdin argument protocol, and macOS lifecycle status file. |
| `forge` | `bootstrap`, `core`, `patches`, Forge/LaunchWrapper at compile time | Packages `opus-forge-coremod-*.jar` with the `FMLCorePlugin` manifest and Forge transformer adapter. |
| `patches` | `bootstrap`, `core`, `mappings`, ASM | Verified bytecode patch implementations and the generated options-screen support. |
| `mappings` | — | Obfuscated-name reference for the locked 1.8.9 target. |
| `client-mod` | legacy ForgeGradle 2.1 lane | Normal client-only FML mod. Phase 1 proves typed Forge loading only; it intentionally has no visible UI yet. |

`./game/gradlew -p game prepareBootstrap` builds and syncs exactly the launcher
artifacts needed by the Forge path into `game/build/bootstrap`:

- `bootstrap-*.jar`; and
- `opus-forge-coremod-*.jar`; and
- `opus-forge-client-*.jar`.

The managed runtime supplies Forge, LaunchWrapper, and Forge's ASM 5.0.3. They
must not be bundled into either OPUS Forge artifact. `scripts/prepare-desktop-assets.sh`
copies all three OPUS artifacts into the Tauri resources.

> **Mappings caveat.** The properties file is a reference, not a runtime mapper.
> Patches still assert their locked obfuscated anchors (`ave`, `axp`, `avo`,
> `bfk`, `bda`, `bcy`, `ek`, …). When a target changes, update the mapping
> reference, adapter target list, patch implementation, and tests together.

## Active startup sequence

1. Rust builds `LaunchMode::ForgeBootstrap` only after the locked Forge
   runtime, imported OptiFine JAR, bootstrap JAR, coremod JAR, and normal
   client-mod JAR have all been verified.
2. The JVM starts `org.polydevs.opusmc.bootstrap.ForgeBootstrapMain`. Its ordinary game
   arguments are a bounded, length-prefixed stdin payload; account data does
   not appear in the OS command line.
3. `ForgeBootstrapMain` installs the game-status shutdown hook, validates its
   small control protocol, decodes stdin, ensures
   `net.minecraftforge.fml.common.launcher.FMLTweaker` is present, and calls
   `net.minecraft.launchwrapper.Launch.main`.
4. Forge LaunchWrapper creates its `LaunchClassLoader`, applies the Forge and
   OptiFine tweakers, and discovers the OPUS coremod plus normal client mod from
   the isolated `game/mods/` directory.
5. The coremod manifest registers `org.polydevs.opusmc.forge.OpusLoadingPlugin` as an
   `IFMLLoadingPlugin`. Its sorting index places OPUS's verified obfuscated-name
   patches before Forge's deobfuscation transformer while preserving OptiFine's
   earlier tweaker ordering.
6. `OpusLoadingPlugin` starts the local telemetry lifecycle and registers
   `OpusForgeClassTransformer`, an `IClassTransformer` adapter. The adapter
   maps Forge's raw/deobfuscated class names back to the locked 1.8.9 targets
   and forwards only those targets through OPUS's deterministic patch chain.
7. The first-frame lifecycle patch marks the OPUS core as running. Launcher and
   core shutdown hooks provide the terminal status/telemetry records. On
   macOS the launcher starts the foreground app stub with `open -n` and waits
   on this file; it never uses `open -W` because the stub intentionally execs
   the Java process.

The legacy `BootstrapMain` + `TransformingClassLoader` route and its
ServiceLoader discovery mechanism are retained only for source-level
compatibility. They are retired for the Forge/OptiFine runtime: the desktop QA
and Premium launch paths must use `ForgeBootstrapMain` and Forge's loader.

## Loader and transformer boundaries

Forge's `LaunchClassLoader` is the only loader that defines transformed
Minecraft classes. `OpusForgeClassTransformer` implements Forge's
`IClassTransformer` contract; it neither creates nor delegates to OPUS's former
child-first game loader.

The adapter deliberately restricts transformation to the known 1.8.9 classes
and the OPUS options-screen placeholder. It handles both raw obfuscated names
and Forge-provided mapped names, then calls `TransformerChain`. A patch whose
anchor is missing, duplicated, or otherwise uncertain throws: a partial patch
is worse than a visible launch failure.

Current chain order is determined by `priority()` then `id()`:

| Priority | Id | Target class(es) | Effect |
| --- | --- | --- | --- |
| 100 | `opus.window-title` | `ave` | Replaces the locked window-title anchor. |
| 105 | `opus.forge-lifecycle` | `ave` | Marks the telemetry lifecycle running at the verified frame-loop entry. |
| 110 | `opus.minecraft-telemetry` | `ave` | Frame-loop and tick timing hooks. |
| 112 | `opus.client-options` | `ave`, `axp`, `avo` | Right-Shift entry, pause-menu button, HUD hook, and Utilities screen entry. |
| 115 | `opus.render-telemetry` | `bfk` | Render-scope timing. |
| 120 | `opus.combat-telemetry` | `bda`, `bcy` | Attack intent, queued attack packet, and entity-status correlation. |
| 130 | `opus.network-telemetry` | `ek` | Inbound/outbound packet counters and connection lifecycle. |

Look for `[OPUS/FORGE] coremod registered`, `[OPUS/FORGE] transforming`, and
`[OPUS/PATCH] applied …` in the per-session game output when diagnosing a Forge
startup or patch failure.

## Core: telemetry and UI

`ClientTelemetry` is local-only JSONL instrumentation. It uses a bounded queue
and a writer thread; hot frame/tick/render paths avoid allocation. It emits
roughly five-second `performance_window` records with p50/p95/p99/max timings,
memory and GC counters, plus a terminal `session_summary`. Network sampling is
payload-free and combat correlation is explicitly labelled an unverified
client-side signal. It records no username, chat, server address, packet
payload, or authentication material. See [diagnostics.md](diagnostics.md).

`ClientUiHooks` remains the boundary between injected bytecode and OPUS UI code.
It uses `Object`/reflection at Minecraft-facing boundaries so the coremod does
not compile against a bundled Minecraft JAR. A hook failure is reported once
and degrades the overlay rather than crashing the game.

`ClientConfigUi` reads the desktop-owned utility settings and brand wordmark
from these system properties:

| Property | Set by | Meaning |
| --- | --- | --- |
| `opus.game.statusFile` | macOS launch path | Lifecycle status file read by the launcher. |
| `opus.diagnostics.file` | every real launch | Local JSONL telemetry destination. |
| `opus.utility.settings.file` | desktop launcher | HUD utility preferences. |
| `opus.brand.wordmark.file` | desktop launcher | Branding image for the in-game UI. |

## Tests and safe changes

Run `./game/gradlew -p game test prepareBootstrap`, or the complete gate with
`scripts/check.sh`. Tests cover the Forge bridge argument protocol, coremod
adapter, lifecycle patch, transformer anchors, and loadable transformed
classes without needing a user Minecraft installation.

When adding or changing an OPUS patch:

1. Update the explicit chain and `OpusForgeClassTransformer` target mapping.
2. Preserve deterministic priority and fail-closed anchor checks.
3. Add a bytecode test and a Forge-adapter test; do not rely on one manual
   launch.
4. Keep Minecraft references out of the coremod's compile-time surface unless
   they are supplied by the locked Forge runtime.
5. If the change adds a mod rather than a transformer, follow the pinned
   managed-mod process in [forge-optifine-runtime.md](forge-optifine-runtime.md)
   instead of dropping a JAR into QA data.
