# Game side: Forge bridge, Core Mod, transformers, and current UI baseline

> **Implementation-contract notice — September 15, 2026:** when this Runtime is
> checked out through the Opus superproject,
> [Decision 0008](../../docs/decisions/0008-opus-client-launcher-ui-first-mainline.md)
> makes Client + Launcher, UI first, the active product mainline. This document
> records the current Forge/Core-Mod artifact contract and its no-product-UI
> constraints; it does not select the future UI renderer or claim a changed
> Runtime artifact. Existing `legacy/1.8.9/client/` source is retained
> reference material, not a current staged product surface.

Everything under this Runtime repository produces Java 8 bytecode for OPUS's pinned Forge +
OptiFine 1.8.9 runtime. Forge owns Minecraft classloading. OPUS joins that
loader through a small bootstrap bridge and a verified Forge coremod; it does
not run a second Minecraft classloader.

Every module builds with `options.release = 8`, UTF-8,
`-Xlint:all,-options`, `-Werror`, and locked dependencies. A warning is a build
failure.

## Modules and packaged artifacts

| Module | Depends on | Active role |
| --- | --- | --- |
| `core` | — | Lifecycle and local telemetry only. It contains no product renderer or input hook. |
| `bootstrap` | `core`, LaunchWrapper at compile time | `ForgeBootstrapMain`, bounded stdin argument protocol, and macOS lifecycle status file. |
| `forge` | `bootstrap`, `core`, `patches`, Forge/LaunchWrapper at compile time | Packages `opus-forge-coremod-*.jar` with the `FMLCorePlugin` manifest and telemetry-only Forge transformer adapter. |
| `patches` | `bootstrap`, `core`, `mappings`, ASM | Verified telemetry and lifecycle bytecode patches only; no product UI transformer or generated screen. |
| `mappings` | — | Obfuscated-name reference for the locked 1.8.9 target. |
| `legacy/1.8.9/client/` | legacy ForgeGradle 2.1 lane | Retained Client/UI reference source. It is not built, packaged, staged, or launched by the current production runtime. |

`./gradlew prepareBootstrap` builds and syncs exactly the launcher artifacts
needed by the Forge path into `build/bootstrap`:

- `bootstrap-*.jar`; and
- `opus-forge-coremod-*.jar`.

The managed runtime supplies Forge, LaunchWrapper, and Forge's ASM 5.0.3. They
must not be bundled into either OPUS Forge artifact. `scripts/prepare-desktop-assets.sh`
copies only those two OPUS artifacts into the Tauri resources.

> **Mappings caveat.** The properties file is a reference, not a runtime mapper.
> Telemetry patches still assert their locked obfuscated anchors (`ave`, `avi`,
> `bfk`, `bda`, `bcy`, `ek`, …). When a target changes, update the mapping
> reference, adapter target list, patch implementation, and tests together.

## Active startup sequence

1. Rust builds `LaunchMode::ForgeBootstrap` only after the locked Forge
   runtime, imported OptiFine JAR, bootstrap JAR, and Core Mod JAR have all
   been verified.
2. The JVM starts `org.polydevs.opusmc.bootstrap.ForgeBootstrapMain`. Its ordinary game
   arguments are a bounded, length-prefixed stdin payload; account data does
   not appear in the OS command line.
3. `ForgeBootstrapMain` installs the game-status shutdown hook, validates its
   small control protocol, decodes stdin, ensures
   `net.minecraftforge.fml.common.launcher.FMLTweaker` is present, and calls
   `net.minecraft.launchwrapper.Launch.main`.
4. Before Forge scans mods, the launcher stages exactly OptiFine and the OPUS
   Core Mod in the isolated `game/mods/` directory and removes the known stale
   `opus-client-legacy-1.8.9.jar` file if present. Forge LaunchWrapper then
   creates its `LaunchClassLoader`, applies the Forge and OptiFine tweakers, and
   discovers the OPUS Core Mod.
5. The coremod manifest registers `org.polydevs.opusmc.forge.OpusLoadingPlugin` as an
   `IFMLLoadingPlugin`. Its sorting index places OPUS's verified obfuscated-name
   patches before Forge's deobfuscation transformer while preserving OptiFine's
   earlier tweaker ordering.
6. `OpusLoadingPlugin` starts the local telemetry lifecycle and registers
   `OpusForgeClassTransformer`, an `IClassTransformer` adapter. The adapter
   maps Forge's raw/deobfuscated class names back to the locked 1.8.9 targets
   and forwards only those targets through OPUS's deterministic patch chain.
7. The first-frame lifecycle patch marks the OPUS core as running. Launcher and
   core shutdown hooks provide the terminal status/telemetry records. Minecraft
   continues to own title, pause/ESC, world selection, multiplayer, options,
   resize, and all normal GUI/input behavior. On macOS the launcher starts the
   foreground app stub with `open -n` and waits on this file; it never uses
   `open -W` because the stub intentionally execs the Java process.

The legacy `BootstrapMain` + `TransformingClassLoader` route and its
ServiceLoader discovery mechanism are retained only as quarantined source-level
compatibility evidence. They are excluded from the production bootstrap and
Core Mod JARs. Desktop QA and Premium can execute only `ForgeBootstrapMain`
with Forge's loader.

## Loader and transformer boundaries

Forge's `LaunchClassLoader` is the only loader that defines transformed
Minecraft classes. `OpusForgeClassTransformer` implements Forge's
`IClassTransformer` contract; it neither creates nor delegates to OPUS's former
child-first game loader.

The adapter deliberately restricts transformation to the known 1.8.9 telemetry
anchors. It handles both raw obfuscated names and Forge-provided mapped names,
then calls `TransformerChain`. A patch whose anchor is missing, duplicated, or
otherwise uncertain throws: a partial patch is worse than a visible launch
failure. No UI entry point is registered or injected by this Core Mod.

Current chain order is determined by `priority()` then `id()`:

| Priority | Id | Target class(es) | Effect |
| --- | --- | --- | --- |
| 105 | `opus.forge-lifecycle` | `ave` | Marks the telemetry lifecycle running at the verified frame-loop entry. |
| 110 | `opus.minecraft-telemetry` | `ave` | Frame-loop and tick timing hooks. |
| 115 | `opus.render-telemetry` | `bfk` | Render-scope timing. |
| 120 | `opus.combat-telemetry` | `bda`, `bcy` | Attack intent, queued attack packet, and entity-status correlation. |
| 130 | `opus.network-telemetry` | `ek` | Inbound/outbound packet counters and connection lifecycle. |

Look for `[OPUS/FORGE] coremod registered`, `[OPUS/FORGE] transforming`, and
`[OPUS/PATCH] applied …` in the per-session game output when diagnosing a Forge
startup or patch failure.

## Core: telemetry only

`ClientTelemetry` is local-only JSONL instrumentation. It uses a bounded queue
and a writer thread; hot frame/tick/render paths avoid allocation. It emits
roughly five-second `performance_window` records with p50/p95/p99/max timings,
memory and GC counters, plus a terminal `session_summary`. Network sampling is
payload-free and combat correlation is explicitly labelled an unverified
client-side signal. It records no username, chat, server address, packet
payload, or authentication material. See [diagnostics.md](diagnostics.md).

The Core Mod has no product UI boundary. It is limited to telemetry and narrowly
scoped non-UI bytecode patches. It must not replace a `GuiScreen`, register a
product key binding, launch CEF, render a browser texture, create a HUD product
surface, or intercept vanilla ESC/options/resize/input flows. The artifact
verifier rejects retired reflection UI hooks, generated options screens,
terminal transformers, and their service registration. It also rejects the
screen/key/input strings used by reflective or string-dispatched hooks
(`displayGuiScreen`, `currentScreen`, `onGuiOpening`, key-binding, keyboard,
and mouse handler names), so a UI path cannot bypass the direct class/package
checks.

The Core Mod uses only its lifecycle/diagnostic system properties:

| Property | Set by | Meaning |
| --- | --- | --- |
| `opus.game.statusFile` | macOS launch path | Lifecycle status file read by the launcher. |
| `opus.diagnostics.file` | every real launch | Local JSONL telemetry destination. |

## Tests and safe changes

Run `./gradlew check verifyCoremodArtifact verifyRuntimeArtifacts` from the
Runtime root, or the complete repository gates. Tests cover the Forge bridge
argument protocol, telemetry coremod adapter, lifecycle patch, transformer
anchors, artifact exclusion rules, and loadable transformed classes without
needing a user Minecraft installation.

When adding or changing an OPUS patch:

1. Update the explicit chain and `OpusForgeClassTransformer` target mapping.
2. Preserve deterministic priority and fail-closed anchor checks.
3. Add a bytecode test and a Forge-adapter test; do not rely on one manual
   launch.
4. Keep product UI, CEF, and Minecraft GUI replacement out of the Core Mod.
5. Do not add another Forge mod to the managed set unless the project owner
   explicitly re-authorizes that architectural change.
