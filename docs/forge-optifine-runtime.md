# Pinned Forge + OptiFine 1.8.9 runtime

OPUS's supported game runtime is a single, locked Minecraft 1.8.9 profile:

| Part | Locked value |
| --- | --- |
| Minecraft | `1.8.9` |
| Forge | `1.8.9-11.15.1.2318-1.8.9` |
| Forge entry point | `net.minecraft.launchwrapper.Launch` with `FMLTweaker` |
| OptiFine | `OptiFine_1.8.9_HD_U_M5.jar` |
| OPUS integration | one verified `opus-forge-coremod` loaded by Forge |
| In-game UI | unmodified vanilla Minecraft UI and input |

The authoritative contract is
`launcher/opus-runtime/runtime-lock/forge-1.8.9-11.15.1.2318.lock.json`, parsed
by `src/forge.rs`. It pins the Forge profile identity, launch argument
template, every Forge library URL/path/size/SHA-1, and the accepted OptiFine
artifact. OPUS does not execute a Forge installer or trust a mutable launcher
profile as part of installation.

## Install and OptiFine import

`Installer` first installs and verifies the Mojang 1.8.9 base client, assets,
natives, and managed Java 8 runtime. It then resolves the Forge libraries from
the checked-in lock. Those downloads use the same HTTPS, size, SHA-1, temporary
file, and atomic-rename checks as every other managed artifact.

OptiFine is deliberately different:

- The player obtains the exact supported JAR from OptiFine through their own
  lawful route and accepts any applicable OptiFine terms.
- OPUS accepts a **local path only**, verifies the exact locked size and SHA-1,
  then copies the verified file into its isolated runtime at
  `<OPUS data root>/game/mods/OptiFine_1.8.9_HD_U_M5.jar`.
- OPUS never downloads, bundles, redistributes, modifies, uploads, or deletes
  the original OptiFine JAR. A failed verification leaves it unimported.

The desktop command is `import_optifine`; the CLI equivalent is
`opus import-optifine <path>`. Installation can finish while OptiFine is absent,
but no Forge game launch is allowed until the import is present and verifies
again from the managed copy.

## Launch path

The only supported product launch mode is `LaunchMode::ForgeBootstrap`:

1. The launcher renders the locked Forge game-argument template and transfers
   those arguments through the bounded stdin protocol. This keeps an
   authenticated access token off the operating-system command line.
2. The system classpath contains the small OPUS bootstrap JAR followed by the
   verified Forge and Minecraft classpath. The process entry is
   `org.polydevs.opusmc.bootstrap.ForgeBootstrapMain`.
3. The bridge validates its small control protocol, decodes stdin, ensures the
   Forge `FMLTweaker` is present, and calls
   `net.minecraft.launchwrapper.Launch.main`.
4. Forge's LaunchWrapper and `LaunchClassLoader` own Minecraft class loading,
   tweaker ordering, and class transformation. OptiFine participates through
   the imported managed mod; OPUS does not replace Forge with a second custom
   game classloader.
5. Before starting Forge, OPUS verifies and stages the bundled
   `opus-forge-coremod` beside OptiFine in the isolated `game/mods/` directory.
   It removes the known stale `opus-client-legacy-1.8.9.jar` before Forge scans
   this directory. The Core Mod's `FMLCorePlugin` manifest registers
   `org.polydevs.opusmc.forge.OpusLoadingPlugin`. No CEF helper, OPUS client
   mod, or legacy UI artifact may be packaged or staged.

`BootstrapMain`, `TransformingClassLoader`, and the standalone ServiceLoader
route remain quarantined compatibility source only. They are excluded from
production artifacts and cannot be selected by desktop QA or Premium launch
paths.

## Managed-mod policy

Production, QA, and any launcher smoke test use the same controlled
two-artifact Forge mod set:

1. the exact OptiFine JAR imported and verified above; and
2. the checksum-verified OPUS Forge Core Mod staged from the packaged launcher
   asset.

An unexpected visible entry in the managed `game/mods/` directory makes the
launcher fail before Forge starts. Do not place arbitrary user mods, modpacks,
test JARs, or prior OPUS client artifacts in QA data. This is both a
reproducibility rule and a diagnostic boundary: vanilla UI behavior must not be
confused with a stale client/UI mod.

Forge has more than one historical mod-discovery convention. Before adding a
new managed location or supporting another artifact, extend the validator to
cover every directory Forge can inspect, pin the artifact in an audited lock,
define its transformer/load ordering, and add an isolated QA smoke test. A
future mod is not supported merely because Forge happens to discover it.

## Changing the profile

A Forge, OptiFine, or OPUS Core Mod update is a runtime-contract change, not a
settings change. Update the lock and all relevant integrity values, regenerate
the artifact deliberately, verify Forge load order, run the Java/Rust tests,
and smoke-test a clean isolated root. Keep old profiles out of the new root
rather than mixing artifacts from different loader contracts.
